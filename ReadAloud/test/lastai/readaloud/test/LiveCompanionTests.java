package lastai.readaloud.test;

import lastai.readaloud.Config;
import lastai.readaloud.speech.CompanionBackend;
import lastai.readaloud.speech.Speech;
import lastai.readaloud.speech.SpeechRequest;
import org.json.JSONObject;

import java.util.concurrent.Callable;

/**
 * The mod's real client against the real companion app (Python), started by
 * build.sh --all with the beep engine and no sound. Skipped unless -Dreadaloud.livePort is set.
 */
public final class LiveCompanionTests {
    static int port() {
        return Integer.getInteger("readaloud.livePort", 0);
    }

    public static void clientAndAppAgree() throws Exception {
        if (port() == 0) {
            System.out.println("       (skipped: no live companion)");
            return;
        }
        final CompanionBackend b = new CompanionBackend(port());
        T.eventually(30000, "app ready", new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return b.ready();
            }
        });
        JSONObject s = b.status();
        T.eq("Test beeps", s.getString("engine"), "engine name");
        SpeechRequest r = new SpeechRequest("\"Captain,\" she said — café au lait. Second sentence here.");
        r.speakerGender = "female";
        b.speak(r);
        T.yes(b.isSpeaking(), "speaking");
        T.eventually(10000, "finished", new Callable<Boolean>() {
            @Override
            public Boolean call() {
                b.poll();
                return !b.isSpeaking();
            }
        });
    }

    /** The add-on's settings page from the real app's /api/voices, and a change sent back. */
    public static void voicePageFromTheRealApp() throws Exception {
        if (port() == 0) return;
        final CompanionBackend b = new CompanionBackend(port());
        T.eventually(30000, "app ready", new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return b.ready();
            }
        });
        Config c = new Config();
        c.companionPort = port();
        JSONObject v = b.voices();
        T.yes(v != null, "voices answered");
        lastai.readaloud.speech.CompanionStatus st = lastai.readaloud.speech.CompanionStatus.of(true, c, true, false, v);
        T.eq(lastai.readaloud.speech.CompanionStatus.Kind.READY, st.kind, "ready");
        java.util.List<lastai.readaloud.speech.AiMenu.Field> page = lastai.readaloud.speech.AiMenu.aiPage(st);
        T.yes(page.size() >= 4, "page: " + page);
        T.yes(b.saveSettings(new JSONObject().put("use_pools", false)), "settings taken");
        T.no(b.voices().getBoolean("use_pools"), "pools now off");
        T.yes(b.saveSettings(new JSONObject().put("use_pools", true)), "and back on");
    }

    public static void speechServiceUsesTheAppAndStops() throws Exception {
        if (port() == 0) return;
        Config c = new Config();
        c.companionPort = port();
        final Speech speech = new Speech(c, null);
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 80; i++) sb.append("Sentence number ").append(i).append(" is long enough. ");
            speech.speak(new SpeechRequest(sb.toString()));
            T.eventually(5000, "via companion", new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    return speech.lastBackend().startsWith("companion");
                }
            });
            Thread.sleep(400);
            T.yes(speech.isSpeaking(), "still speaking a long text");
            speech.stop();
            final CompanionBackend b = new CompanionBackend(port());
            T.eventually(3000, "app stopped", new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    b.poll();
                    return !b.isSpeaking();
                }
            });
        } finally {
            speech.shutdown();
        }
    }
}
