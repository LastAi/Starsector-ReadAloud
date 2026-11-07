package lastai.readaloud.test;

import lastai.readaloud.Config;
import lastai.readaloud.Notices;
import lastai.readaloud.speech.CompanionBackend;
import lastai.readaloud.speech.Speech;
import lastai.readaloud.speech.SpeechBackend;
import lastai.readaloud.speech.SpeechRequest;

import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** The speech service's backend choice and state, with the real HTTP client against a fake app. */
public final class SpeechTests {
    static final class FakeSystem implements SpeechBackend {
        final List<String> spoken = new CopyOnWriteArrayList<String>();
        volatile boolean speaking;
        volatile int stops;

        @Override
        public String name() {
            return "system voice";
        }

        @Override
        public void speak(SpeechRequest r) {
            spoken.add(r.text);
            speaking = true;
        }

        @Override
        public void stop() {
            stops++;
            speaking = false;
        }

        @Override
        public boolean isSpeaking() {
            return speaking;
        }
    }

    static Speech speech(Config c, final FakeSystem sys, Speech.Launcher launcher) {
        return new Speech(c, launcher, new Speech.Backends() {
            @Override
            public CompanionBackend companion(Config cfg) {
                return new CompanionBackend(cfg.companionPort);
            }

            @Override
            public SpeechBackend system(Config cfg) {
                return sys;
            }
        });
    }

    static Config config(String backend, int port) {
        Config c = new Config();
        c.backend = backend;
        c.companionPort = port;
        return c;
    }

    static int closedPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /**
     * Saving settings queues the new config and then, at once, a voice sample. Only the newest
     * command is kept, so the config must not be a queued command that the sample replaces.
     */
    public static void settingsChangeIsNotLostToTheNextCommand() throws Exception {
        final FakeSystem sys = new FakeSystem();
        Speech s = speech(config("off", closedPort()), sys, null);
        try {
            for (int i = 0; i < 20; i++) {
                s.configure(config(i % 2 == 0 ? "system" : "off", closedPort()));
                s.configure(config("system", closedPort()));
                s.speak(new SpeechRequest("Try " + i + "."));
            }
            T.eventually(3000, "the last speak used the new config", new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    return sys.spoken.contains("Try 19.");
                }
            });
        } finally {
            s.shutdown();
        }
    }

    /** Voice samples use the system voice even when the backend is the companion. */
    public static void previewAlwaysUsesTheSystemVoice() throws Exception {
        final FakeSystem sys = new FakeSystem();
        Speech s = speech(config("companion", closedPort()), sys, null);
        try {
            s.preview(new SpeechRequest("This is the narrator."));
            T.eventually(3000, "sample spoken", new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    return sys.spoken.contains("This is the narrator.");
                }
            });
        } finally {
            s.shutdown();
        }
    }

    public static void autoPrefersTheCompanion() throws Exception {
        try (FakeCompanion fake = new FakeCompanion()) {
            final FakeSystem sys = new FakeSystem();
            Speech s = speech(config("auto", fake.port()), sys, null);
            try {
                s.speak(new SpeechRequest("Hello."));
                T.yes(s.isSpeaking(), "speaking at once (button feedback)");
                T.eventually(3000, "companion got it", new Callable<Boolean>() {
                    @Override
                    public Boolean call() {
                        return fake.spoken.size() == 1;
                    }
                });
                T.yes(sys.spoken.isEmpty(), "system voice unused");
                fake.speaking = false; // the app finished
                final Speech fs = s;
                T.eventually(3000, "speaking clears when the app is done", new Callable<Boolean>() {
                    @Override
                    public Boolean call() {
                        return !fs.isSpeaking();
                    }
                });
            } finally {
                s.shutdown();
            }
        }
    }

    /**
     * In-game report: the first read after starting the game came in the old Windows voice,
     * because the app was launched by that read and still loading. Now the read waits for it.
     */
    public static void firstReadWaitsForTheStartingApp() throws Exception {
        try (final FakeCompanion fake = new FakeCompanion()) {
            fake.ready = false; // running, model still loading
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(1200);
                    } catch (InterruptedException ignored) {
                    }
                    fake.ready = true;
                }
            }).start();
            final FakeSystem sys = new FakeSystem();
            Speech s = speech(config("auto", fake.port()), sys, null);
            try {
                s.speak(new SpeechRequest("Your fleet approaches the station."));
                T.eventually(5000, "the app got the first read", new Callable<Boolean>() {
                    @Override
                    public Boolean call() {
                        return fake.spoken.size() == 1;
                    }
                });
                T.eq(0, sys.spoken.size(), "the system voice stayed quiet");
            } finally {
                s.shutdown();
            }
        }
    }

    /** Nothing running: the read starts the app and waits for it instead of using the system voice. */
    public static void readStartsTheAppAndWaits() throws Exception {
        final int port = closedPort();
        final FakeCompanion[] app = new FakeCompanion[1];
        final AtomicInteger launches = new AtomicInteger();
        final FakeSystem sys = new FakeSystem();
        Speech s = speech(config("auto", port), sys, new Speech.Launcher() {
            @Override
            public boolean launch() {
                launches.incrementAndGet();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Thread.sleep(800);
                            app[0] = new FakeCompanion(port);
                        } catch (Exception ignored) {
                        }
                    }
                }).start();
                return true;
            }
        });
        try {
            s.speak(new SpeechRequest("Your fleet approaches the station."));
            T.eventually(6000, "the started app got the read", new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    return app[0] != null && app[0].spoken.size() == 1;
                }
            });
            T.eq(0, sys.spoken.size(), "system voice quiet");
            T.eq(1, launches.get(), "started once");
            T.yes(s.isStartingCompanion(), "counts as starting for a few seconds");
        } finally {
            s.shutdown();
            if (app[0] != null) app[0].close();
        }
    }

    /** Stopping while the read waits for the app: nothing is spoken by anyone. */
    public static void stopWhileWaitingForTheAppSaysNothing() throws Exception {
        try (FakeCompanion fake = new FakeCompanion()) {
            fake.ready = false; // never becomes ready
            final FakeSystem sys = new FakeSystem();
            Speech s = speech(config("auto", fake.port()), sys, new Speech.Launcher() {
                @Override
                public boolean launch() {
                    return true;
                }
            });
            try {
                s.speak(new SpeechRequest("Hello."));
                Thread.sleep(600);
                s.stop();
                Thread.sleep(800);
                T.eq(0, sys.spoken.size(), "system voice silent");
                T.eq(0, fake.spoken.size(), "app silent");
                T.no(s.isSpeaking(), "not speaking");
            } finally {
                s.shutdown();
            }
        }
    }

    /** warmUp() at game load starts the app before any dialog. */
    public static void warmUpLaunchesTheApp() throws Exception {
        final AtomicInteger launches = new AtomicInteger();
        Speech s = speech(config("auto", closedPort()), new FakeSystem(), new Speech.Launcher() {
            @Override
            public boolean launch() {
                launches.incrementAndGet();
                return true;
            }
        });
        try {
            s.warmUp();
            T.eventually(3000, "launched", new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    return launches.get() == 1;
                }
            });
        } finally {
            s.shutdown();
        }
        Speech off = speech(config("system", closedPort()), new FakeSystem(), new Speech.Launcher() {
            @Override
            public boolean launch() {
                launches.incrementAndGet();
                return true;
            }
        });
        try {
            off.warmUp();
            Thread.sleep(500);
            T.eq(1, launches.get(), "backend system: no launch");
        } finally {
            off.shutdown();
        }
    }

    public static void autoFallsBackAndLaunchesOnce() throws Exception {
        final FakeSystem sys = new FakeSystem();
        final AtomicInteger launches = new AtomicInteger();
        Speech s = speech(config("auto", closedPort()), sys, new Speech.Launcher() {
            @Override
            public boolean launch() {
                launches.incrementAndGet();
                return false; // not set up: nothing to wait for
            }
        });
        try {
            s.speak(new SpeechRequest("One."));
            T.eventually(3000, "system spoke", new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    return sys.spoken.size() == 1;
                }
            });
            s.speak(new SpeechRequest("Two."));
            T.eventually(3000, "system spoke again", new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    return sys.spoken.size() == 2;
                }
            });
            T.eq(1, launches.get(), "launcher ran once");
            T.eq("system voice", s.lastBackend(), "last backend");
        } finally {
            s.shutdown();
        }
    }

    static List<String> notices() {
        List<String> out = new java.util.ArrayList<String>();
        for (String n; (n = Notices.poll()) != null; ) out.add(n);
        return out;
    }

    /** A read the app can't take tells the player why, and whether a restart is needed; once, not every read. */
    public static void fallbackTellsThePlayerOnce() throws Exception {
        Notices.clear();
        final FakeSystem sys = new FakeSystem();
        Speech s = speech(config("auto", closedPort()), sys, new Speech.Launcher() {
            @Override
            public boolean launch() {
                return false;
            }

            @Override
            public boolean isSetUp() {
                return false;
            }
        });
        try {
            s.speak(new SpeechRequest("One."));
            T.eventually(3000, "system spoke", new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    return sys.spoken.size() == 1;
                }
            });
            s.speak(new SpeechRequest("Two."));
            T.eventually(3000, "system spoke again", new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    return sys.spoken.size() == 2;
                }
            });
            List<String> n = notices();
            T.eq(1, n.size(), "one notice: " + n);
            T.yes(n.get(0).contains("aren't set up") && n.get(0).contains("No game restart needed"), n.get(0));
        } finally {
            s.shutdown();
            Notices.clear();
        }
    }

    /** Voice "companion" without the add-on mod: nothing is read, and the notice says enabling it needs a restart. */
    public static void companionWithoutTheAddOnSaysSo() throws Exception {
        Notices.clear();
        final FakeSystem sys = new FakeSystem();
        final Speech s = speech(config("companion", closedPort()), sys, new Speech.Launcher() {
            @Override
            public boolean launch() {
                return false;
            }

            @Override
            public boolean modEnabled() {
                return false;
            }
        });
        try {
            s.speak(new SpeechRequest("Hello."));
            T.eventually(3000, "speaking flag drops", new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    return !s.isSpeaking();
                }
            });
            List<String> n = notices();
            T.eq(1, n.size(), "notice: " + n);
            T.yes(n.get(0).contains("isn't enabled") && n.get(0).contains("needs a game restart"), n.get(0));
            T.yes(sys.spoken.isEmpty(), "nothing read");
        } finally {
            s.shutdown();
            Notices.clear();
        }
    }

    /** After a notice, the first read the app takes says AI voices are back. */
    public static void appComingBackIsAnnounced() throws Exception {
        Notices.clear();
        try (FakeCompanion fake = new FakeCompanion()) {
            fake.ready = false;
            fake.state = "error"; // no waiting: an error isn't going to fix itself in 15 s
            fake.error = "model missing";
            final FakeSystem sys = new FakeSystem();
            Speech s = speech(config("auto", fake.port()), sys, null);
            try {
                s.speak(new SpeechRequest("One."));
                T.eventually(3000, "system spoke", new Callable<Boolean>() {
                    @Override
                    public Boolean call() {
                        return sys.spoken.size() == 1;
                    }
                });
                List<String> n = notices();
                T.yes(n.size() == 1 && n.get(0).contains("model missing"), "error notice: " + n);
                fake.ready = true;
                s.speak(new SpeechRequest("Two."));
                T.eventually(3000, "the app read it", new Callable<Boolean>() {
                    @Override
                    public Boolean call() {
                        return fake.spoken.size() == 1;
                    }
                });
                n = notices();
                T.yes(n.size() == 1 && n.get(0).contains("AI voices are ready"), "back: " + n);
            } finally {
                s.shutdown();
                Notices.clear();
            }
        }
    }

    /** Samples for the AI voice page go to the app only. */
    public static void companionPreviewUsesTheApp() throws Exception {
        try (final FakeCompanion fake = new FakeCompanion()) {
            final FakeSystem sys = new FakeSystem();
            Speech s = speech(config("auto", fake.port()), sys, null);
            try {
                s.previewCompanion(new SpeechRequest("This is the narrator."));
                T.eventually(3000, "app got the sample", new Callable<Boolean>() {
                    @Override
                    public Boolean call() {
                        return fake.spoken.size() == 1;
                    }
                });
                T.yes(sys.spoken.isEmpty(), "system voice unused");
            } finally {
                s.shutdown();
            }
        }
    }

    public static void companionOnlyStaysSilentWithoutTheApp() throws Exception {
        final FakeSystem sys = new FakeSystem();
        final Speech s = speech(config("companion", closedPort()), sys, null);
        try {
            s.speak(new SpeechRequest("Hello."));
            T.eventually(3000, "speaking flag drops", new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    return !s.isSpeaking();
                }
            });
            Thread.sleep(100);
            T.yes(sys.spoken.isEmpty(), "no fallback");
        } finally {
            s.shutdown();
        }
    }

    public static void systemOnlyNeverCallsTheApp() throws Exception {
        try (FakeCompanion fake = new FakeCompanion()) {
            final FakeSystem sys = new FakeSystem();
            Speech s = speech(config("system", fake.port()), sys, null);
            try {
                s.speak(new SpeechRequest("Hi."));
                T.eventually(3000, "system spoke", new Callable<Boolean>() {
                    @Override
                    public Boolean call() {
                        return sys.spoken.size() == 1;
                    }
                });
                T.yes(fake.spoken.isEmpty(), "app unused");
            } finally {
                s.shutdown();
            }
        }
    }

    public static void stopReachesTheActiveBackend() throws Exception {
        try (final FakeCompanion fake = new FakeCompanion()) {
            final FakeSystem sys = new FakeSystem();
            final Speech s = speech(config("auto", fake.port()), sys, null);
            try {
                s.speak(new SpeechRequest("Long text."));
                T.eventually(3000, "spoken", new Callable<Boolean>() {
                    @Override
                    public Boolean call() {
                        return fake.spoken.size() == 1;
                    }
                });
                s.stop();
                T.no(s.isSpeaking(), "flag drops at once");
                T.eventually(3000, "stop sent", new Callable<Boolean>() {
                    @Override
                    public Boolean call() {
                        return fake.stops >= 1;
                    }
                });
            } finally {
                s.shutdown();
            }
        }
    }

    public static void offAndEmptyTextSpeakNothing() throws Exception {
        final FakeSystem sys = new FakeSystem();
        Speech s = speech(config("off", closedPort()), sys, null);
        try {
            s.speak(new SpeechRequest("Hello."));
            s.speak(new SpeechRequest("   "));
            Thread.sleep(300);
            T.yes(sys.spoken.isEmpty(), "nothing");
            T.no(s.isSpeaking(), "not speaking");
        } finally {
            s.shutdown();
        }
    }
}
