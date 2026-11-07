package lastai.readaloud;

import lastai.readaloud.speech.Speech;

/** Shared state for the running game: current settings and the speech service. */
public final class ReadAloud {
    public static final String MOD_ID = "lastai_readaloud";
    /** The optional add-on with the companion app (mods/ReadAloudAI). */
    public static final String AI_MOD_ID = "lastai_readaloud_ai";
    public static final String AI_MOD_NAME = "Read Aloud: AI Voices";

    private static volatile Config config;
    private static volatile Speech speech;

    private ReadAloud() {}

    /** Current settings, or null before the mod has loaded. */
    public static Config config() {
        return config;
    }

    /** The speech service, or null before the mod has loaded. */
    public static Speech speech() {
        return speech;
    }

    static void set(Config c, Speech s) {
        config = c;
        speech = s;
    }

    static void setConfig(Config c) {
        config = c;
    }
}
