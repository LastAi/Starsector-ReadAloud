package lastai.readaloud.speech;

import lastai.readaloud.Config;
import lastai.readaloud.Notices;
import lastai.readaloud.ReadAloudLog;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Front door for speaking. The game thread only queues commands; one daemon worker
 * thread does all the network and process work, so the game never stutters.
 * Newer commands replace older ones that haven't started yet.
 */
public final class Speech {
    private static final long POLL_MS = 250;
    private static final long KEEPALIVE_MS = 60000;

    private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<Runnable>();
    private final Thread worker;
    private volatile boolean running = true;
    /** What the button shows. Set at once by the game thread, corrected by the worker. */
    private volatile boolean speaking;
    private volatile String lastBackend = "";

    private Config config;
    private volatile Config pendingConfig;
    private CompanionBackend companion;
    private SpeechBackend system;
    private SpeechBackend active;
    private final Launcher launcher;
    private final Backends backends;
    /** When we last tried to start the app (0 = never). Retried after RELAUNCH_MS, so setting the app up, or
     *  it quitting, never needs a game restart. */
    private long lastLaunchTry;
    static final long RELAUNCH_MS = 60000;
    /** When the app was last started by us (0 = not started, or it wasn't set up). */
    private volatile long launchedAt;
    /** Reads wait for the app from this moment (it was started, or found loading) for STARTUP_WAIT_MS; 0 = no wait. */
    private long waitFrom;
    /** How long after starting the app reads wait for it (uv + model load take ~5 s). */
    static final long STARTUP_WAIT_MS = 15000;
    private boolean companionSeen;
    private long lastKeepalive;
    /** A notice said the app wasn't usable; say so when it is again. */
    private boolean noticed;

    /** Starts the companion app in the background. */
    public interface Launcher {
        /** @return true if the app was started (false: not set up, or it failed) */
        boolean launch();

        /** The add-on mod with the app is enabled. */
        default boolean modEnabled() {
            return true;
        }

        /** The app has been run once (so the game may start it). */
        default boolean isSetUp() {
            return true;
        }
    }

    /** Makes the backends for a config. Replaceable in tests. */
    public interface Backends {
        CompanionBackend companion(Config c);

        SpeechBackend system(Config c);
    }

    public static final Backends REAL = new Backends() {
        @Override
        public CompanionBackend companion(Config c) {
            return new CompanionBackend(c.companionPort);
        }

        @Override
        public SpeechBackend system(Config c) {
            SystemBackend b = new SystemBackend(c.systemRate, c.systemVolume, c.systemVoice, c.systemVoiceMale, c.systemVoiceFemale);
            if (c.systemVoicePools) {
                List<String>[] pools = systemPools(c, SystemVoices.cached());
                b.withPools(pools[0], pools[1]);
            }
            return b;
        }
    };

    /** The male and female system voice pools for a config (empty lists: no pool). */
    @SuppressWarnings("unchecked")
    public static List<String>[] systemPools(Config c, List<SystemVoices.Voice> installed) {
        String narrator = c.systemVoice.isEmpty() ? SystemVoices.defaultName() : c.systemVoice;
        return new List[] {
                VoicePool.systemPool(installed, "male", c.systemPoolMale, narrator),
                VoicePool.systemPool(installed, "female", c.systemPoolFemale, narrator)};
    }

    /** @param launcher starts the companion app in the background, or null if that isn't possible */
    public Speech(Config config, Launcher launcher) {
        this(config, launcher, REAL);
    }

    public Speech(Config config, Launcher launcher, Backends backends) {
        this.launcher = launcher;
        this.backends = backends;
        apply(config);
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "ReadAloud-speech");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Uses new settings from now on. Applied by the worker before its next command, so a
     * command queued right after this (which replaces queued commands) can't cancel it.
     */
    public void configure(final Config c) {
        pendingConfig = c;
        speaking = false;
        submit(new Runnable() {
            @Override
            public void run() {
                // the new config was applied just before this ran; nothing else to do
            }
        });
    }

    /**
     * Starts the companion app now (if it is used, set up and not running), so it is ready by
     * the first dialog instead of that dialog being read by the system voice.
     */
    public void warmUp() {
        submitKeepingQueue(new Runnable() {
            @Override
            public void run() {
                Config c = config;
                if (c.speechOff() || Config.BACKEND_SYSTEM.equals(c.backend)) return;
                if (companion.status() != null) {
                    companionSeen = true;
                    lastKeepalive = System.currentTimeMillis();
                } else {
                    maybeLaunch(c);
                }
            }
        });
    }

    /** The game started the app moments ago and it may not answer yet. */
    public boolean isStartingCompanion() {
        long at = launchedAt;
        return at != 0 && System.currentTimeMillis() - at < STARTUP_WAIT_MS;
    }

    /** Speaks through the companion app only (voice samples for its settings page); nothing if it isn't ready. */
    public void previewCompanion(final SpeechRequest r) {
        if (r.text.trim().isEmpty()) return;
        speaking = true;
        submit(new Runnable() {
            @Override
            public void run() {
                if (active != null && active != companion) active.stop();
                try {
                    if (!companion.ready()) {
                        speaking = false;
                        return;
                    }
                    companion.speak(r);
                    active = companion;
                    lastBackend = companion.name();
                } catch (Exception e) {
                    ReadAloudLog.errorOnce("preview-companion", "playing the AI voice sample failed", e);
                    speaking = false;
                }
            }
        });
    }

    /** Speaks with the system voice whatever the backend setting (voice samples in the settings menu). */
    public void preview(final SpeechRequest r) {
        if (r.text.trim().isEmpty()) return;
        speaking = true;
        submit(new Runnable() {
            @Override
            public void run() {
                if (active != null && active != system) active.stop();
                try {
                    system.speak(r);
                    active = system;
                    lastBackend = system.name();
                } catch (Exception e) {
                    ReadAloudLog.errorOnce("preview", "playing the voice sample failed", e);
                    speaking = false;
                }
            }
        });
    }

    public void speak(final SpeechRequest r) {
        if (r.text.trim().isEmpty()) return;
        speaking = true;
        submit(new Runnable() {
            @Override
            public void run() {
                doSpeak(r);
            }
        });
    }

    public void stop() {
        speaking = false;
        submit(new Runnable() {
            @Override
            public void run() {
                stopAll();
            }
        });
    }

    public boolean isSpeaking() {
        return speaking;
    }

    /** Name of the backend used last ("" before the first request). For the button tooltip. */
    public String lastBackend() {
        return lastBackend;
    }

    public void shutdown() {
        running = false;
        worker.interrupt();
    }

    private void submit(Runnable r) {
        queue.clear(); // only the newest command matters
        queue.offer(r);
    }

    /** For housekeeping that must not cancel a queued speak or stop. */
    private void submitKeepingQueue(Runnable r) {
        queue.offer(r);
    }

    /**
     * After the app was started, waits (up to STARTUP_WAIT_MS) for it to answer, so the
     * first lines aren't read by the system voice just because the app is still loading.
     * Gives up at once if a newer command arrives.
     */
    private boolean waitForStartingCompanion() {
        if (waitFrom == 0) return false;
        long deadline = waitFrom + STARTUP_WAIT_MS;
        while (System.currentTimeMillis() < deadline && queue.isEmpty() && running) {
            if (companion.ready()) return true;
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    private void apply(Config c) {
        config = c;
        companion = backends.companion(c);
        system = backends.system(c);
        active = null;
    }

    private void loop() {
        while (running) {
            try {
                Runnable cmd = queue.poll(POLL_MS, TimeUnit.MILLISECONDS);
                Config next = pendingConfig;
                if (next != null) {
                    pendingConfig = null;
                    stopAll();
                    apply(next);
                }
                if (cmd != null) {
                    cmd.run();
                } else {
                    refresh();
                }
            } catch (InterruptedException e) {
                if (!running) return;
            } catch (Throwable t) {
                ReadAloudLog.errorOnce("speech-loop:" + t.getClass().getName(), "speech worker error (it keeps running)", t);
                speaking = false;
            }
        }
        stopAll();
    }

    private void refresh() {
        SpeechBackend a = active;
        if (a == companion) companion.poll();
        boolean now = a != null && a.isSpeaking();
        if (queue.isEmpty()) speaking = now;
        if (companionSeen && a != companion && System.currentTimeMillis() - lastKeepalive > KEEPALIVE_MS) {
            lastKeepalive = System.currentTimeMillis();
            companion.status(); // keeps a background-started app from exiting while the game runs
        }
    }

    private void doSpeak(SpeechRequest r) {
        Config c = config;
        if (c.speechOff()) {
            speaking = false;
            return;
        }
        SpeechBackend use;
        if (Config.BACKEND_SYSTEM.equals(c.backend)) {
            use = system;
        } else if (companionUsable(c)) {
            use = companion;
            companionSeen = true;
            lastKeepalive = System.currentTimeMillis();
            if (noticed) {
                noticed = false;
                Notices.reset(NOTICE_PREFIX);
                Notices.post(NOTICE_PREFIX + "ready", "Read Aloud: AI voices are ready" + (companion.engine().isEmpty() ? "."
                        : " (" + companion.engine() + ")."));
            }
        } else {
            if (!queue.isEmpty() || !running) return; // superseded (stopped, a newer read, shut down) while waiting
            boolean fallback = !Config.BACKEND_COMPANION.equals(c.backend);
            CompanionStatus st = CompanionStatus.of(launcher == null || launcher.modEnabled(), c,
                    launcher == null || launcher.isSetUp(), isStartingCompanion(), companion.status());
            String notice = st.notice(fallback);
            if (notice != null) {
                Notices.post(NOTICE_PREFIX + st.kind, notice);
                noticed = true;
            }
            if (!fallback) {
                ReadAloudLog.warnOnce("companion-missing", "the companion app is not usable on port " + c.companionPort
                        + " (backend is \"companion\", so nothing is spoken): " + st.text());
                speaking = false;
                return;
            }
            use = system;
        }
        if (active != null && active != use) active.stop();
        try {
            use.speak(r);
            active = use;
            lastBackend = use.name();
        } catch (Exception e) {
            ReadAloudLog.errorOnce("speak:" + use.name(), "speaking with the " + use.name() + " failed", e);
            if (use == companion && !Config.BACKEND_COMPANION.equals(c.backend)) {
                try {
                    system.speak(r);
                    active = system;
                    lastBackend = system.name();
                    return;
                } catch (Exception e2) {
                    ReadAloudLog.errorOnce("speak:fallback", "the system voice failed too", e2);
                }
            }
            speaking = false;
        }
    }

    static final String NOTICE_PREFIX = "ai-";

    /**
     * True if the app can read this now. If it isn't running it is started (when allowed); if it was
     * just started, or is loading its engine, the read waits for it (once per STARTUP_WAIT_MS), so
     * the first lines aren't read by the system voice only because the app is still loading.
     */
    private boolean companionUsable(Config c) {
        org.json.JSONObject st = companion.status();
        if (st != null && st.optBoolean("ready", false)) {
            waitFrom = 0;
            return true;
        }
        long now = System.currentTimeMillis();
        if (st == null) {
            if (maybeLaunch(c)) waitFrom = launchedAt;
        } else if ("loading".equals(st.optString("state", "")) && (waitFrom == 0 || now - waitFrom > 4 * STARTUP_WAIT_MS)) {
            waitFrom = now; // found loading: wait for it once, then the system voice reads until it's ready
        }
        return waitForStartingCompanion();
    }

    /** Starts the app if allowed and not tried in the last RELAUNCH_MS. True if it was started now. */
    private boolean maybeLaunch(Config c) {
        if (launcher == null || !c.companionAutostart) return false;
        long now = System.currentTimeMillis();
        if (lastLaunchTry != 0 && now - lastLaunchTry < RELAUNCH_MS) return false;
        lastLaunchTry = now;
        try {
            if (launcher.launch()) {
                launchedAt = System.currentTimeMillis();
                return true;
            }
        } catch (Throwable t) {
            ReadAloudLog.errorOnce("launch", "starting the companion app failed", t);
        }
        return false;
    }

    private void stopAll() {
        if (companion != null && (active == companion || companionSeen)) companion.stop();
        if (system != null) system.stop();
        active = null;
        speaking = false;
    }
}
