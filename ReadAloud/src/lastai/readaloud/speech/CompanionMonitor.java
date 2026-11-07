package lastai.readaloud.speech;

import lastai.readaloud.Config;
import lastai.readaloud.ReadAloudLog;
import org.json.JSONObject;

/**
 * Asks the AI voice app every few seconds how it is doing (GET /api/voices) on its own
 * daemon thread, and reports each change, so the settings pages always say whether it is
 * running and which voices it uses. Never starts the app itself.
 */
public final class CompanionMonitor {
    /** What the monitor needs from the game. */
    public interface Env {
        Config config();

        boolean modEnabled();

        boolean setUp();

        /** The game started the app moments ago. */
        boolean starting();
    }

    public interface Listener {
        void changed(CompanionStatus s);

        /** After every check, changed or not. */
        default void checked(CompanionStatus s) {}
    }

    private final Env env;
    private final Listener listener;
    private final long intervalMs;
    private final Object wake = new Object();
    private volatile boolean running = true;
    private volatile CompanionStatus last;
    private Thread thread;

    public CompanionMonitor(Env env, Listener listener, long intervalMs) {
        this.env = env;
        this.listener = listener;
        this.intervalMs = intervalMs;
    }

    public CompanionMonitor start() {
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        check();
                    } catch (Throwable t) {
                        ReadAloudLog.errorOnce("monitor", "checking the AI voice app failed (it keeps trying)", t);
                    }
                    synchronized (wake) {
                        try {
                            wake.wait(intervalMs);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }
        }, "ReadAloud-companion-monitor");
        thread.setDaemon(true);
        thread.start();
        return this;
    }

    /** Checks again now (after the player changed something). */
    public void poke() {
        synchronized (wake) {
            wake.notifyAll();
        }
    }

    public void shutdown() {
        running = false;
        poke();
    }

    /** The last status seen, or null before the first check. */
    public CompanionStatus last() {
        return last;
    }

    /** One check; reports it if anything changed. Public for tests. */
    public CompanionStatus check() {
        Config c = env.config();
        if (c == null) return null;
        JSONObject answer = new CompanionBackend(c.companionPort).voices();
        boolean enabled = env.modEnabled();
        CompanionStatus s = CompanionStatus.of(enabled, c, answer != null || env.setUp(), env.starting(), answer);
        CompanionStatus before = last;
        if (before == null || !before.key().equals(s.key())) {
            last = s;
            if (before == null || before.kind != s.kind) ReadAloudLog.info("AI voice app: " + s.kind + (s.engine.isEmpty() ? "" : " (" + s.engine + ")"));
            listener.changed(s);
        }
        listener.checked(s);
        return s;
    }
}
