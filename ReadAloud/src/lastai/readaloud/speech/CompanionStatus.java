package lastai.readaloud.speech;

import lastai.readaloud.Config;
import lastai.readaloud.ReadAloud;
import org.json.JSONObject;

/**
 * Where the AI voice app stands, in words for the player: for the settings pages and for
 * the notices shown when a read can't use it. Also says whether a game restart is needed
 * (only for enabling the add-on mod itself; the app can start, stop and change any time).
 * Pure, so it is unit tested.
 */
public final class CompanionStatus {
    public enum Kind {
        /** The add-on mod isn't enabled. */
        NO_MOD,
        /** The Voice setting is "system" or "off". */
        NOT_USED,
        /** The app has never been run, so the game won't start it. */
        NOT_SET_UP,
        NOT_RUNNING,
        /** We started it a moment ago and it hasn't answered yet. */
        STARTING,
        /** Running; its engine is loading. */
        LOADING,
        /** Running; its engine failed. */
        ERROR,
        READY
    }

    static final String START = "companion/start.cmd (start.sh on Linux and macOS) in the " + ReadAloud.AI_MOD_NAME + " mod folder";
    static final String NO_RESTART = "No game restart needed.";

    public final Kind kind;
    /** The app's engine name ("" when unknown). */
    public final String engine;
    public final String error;
    public final int port;
    public final boolean autostart;
    /** What the app answered (status or voices), or null. */
    public final JSONObject answer;

    private CompanionStatus(Kind kind, String engine, String error, int port, boolean autostart, JSONObject answer) {
        this.kind = kind;
        this.engine = engine;
        this.error = error;
        this.port = port;
        this.autostart = autostart;
        this.answer = answer;
    }

    /**
     * @param answer the app's /api/status or /api/voices answer, or null if it didn't answer
     * @param starting the game started the app moments ago
     */
    public static CompanionStatus of(boolean modEnabled, Config c, boolean setUp, boolean starting, JSONObject answer) {
        String engine = answer == null ? "" : answer.optString("engine", "");
        String error = answer == null ? "" : answer.optString("error", "");
        Kind k;
        if (answer != null) {
            String state = answer.optString("state", "");
            if (answer.optBoolean("ready", false) || "ready".equals(state)) k = Kind.READY;
            else if ("error".equals(state)) k = Kind.ERROR;
            else k = Kind.LOADING;
        } else if (!modEnabled) {
            k = Kind.NO_MOD;
        } else if (c.speechOff() || Config.BACKEND_SYSTEM.equals(c.backend)) {
            k = Kind.NOT_USED;
        } else if (!setUp) {
            k = Kind.NOT_SET_UP;
        } else if (starting) {
            k = Kind.STARTING;
        } else {
            k = Kind.NOT_RUNNING;
        }
        if (k == Kind.READY && (c.speechOff() || Config.BACKEND_SYSTEM.equals(c.backend))) k = Kind.NOT_USED;
        return new CompanionStatus(k, engine, error, c.companionPort, c.companionAutostart, answer);
    }

    public boolean ready() {
        return kind == Kind.READY;
    }

    /** Changes when anything shown about the app changes. */
    public String key() {
        return kind + "|" + engine + "|" + error + "|" + (answer == null ? "" : answer.toString());
    }

    private String engineName() {
        return engine.isEmpty() ? "its voice engine" : engine;
    }

    private String page() {
        return "http://127.0.0.1:" + port + "/";
    }

    /** One paragraph for the settings pages. */
    public String text() {
        switch (kind) {
            case NO_MOD:
                return "AI voices: the " + ReadAloud.AI_MOD_NAME + " mod isn't enabled, so the system voice reads. To use AI"
                        + " voices, enable it in the game's launcher: enabling or disabling a mod needs a game restart.";
            case NOT_USED:
                return "AI voices: not used, because Voice is set to \"system\" or \"off\" on the Read Aloud page. Set it to"
                        + " \"auto\" or \"companion\" to use them. " + NO_RESTART;
            case NOT_SET_UP:
                return "AI voices: not set up yet. Run " + START + " once and install an engine on the page that opens."
                        + " The game finds the app on its next read. " + NO_RESTART;
            case STARTING:
                return "AI voices: the app is starting (a few seconds). " + NO_RESTART;
            case NOT_RUNNING:
                return "AI voices: the app isn't running. " + (autostart
                        ? "The game starts it when you load a save or on the next read, or run " + START + ". "
                        : "Starting it automatically is off, so run " + START + ". ")
                        + NO_RESTART;
            case LOADING:
                return "AI voices: the app is running and loading " + engineName() + "; the system voice reads until it is"
                        + " ready. " + NO_RESTART;
            case ERROR:
                return "AI voices: the app is running, but " + engineName() + " failed" + (error.isEmpty() ? "" : " (" + error + ")")
                        + ". Open " + page() + " to fix it (start " + START + " if it isn't open). " + NO_RESTART;
            default:
                return "AI voices: ready (" + engineName() + "). Changes in the app or here apply at once; " + NO_RESTART.toLowerCase();
        }
    }

    /**
     * The message for a read that couldn't use the app, or null when there is nothing to say
     * (no add-on and Voice "auto": that's just the system voice working as intended).
     * @param fallback true when the system voice reads instead; false when nothing is read
     */
    public String notice(boolean fallback) {
        String what = fallback ? "the system voice is reading" : "nothing is read (Voice is \"companion\")";
        switch (kind) {
            case NO_MOD:
                return fallback ? null : "Read Aloud: Voice is \"companion\", but the " + ReadAloud.AI_MOD_NAME + " mod isn't"
                        + " enabled, so nothing is read. Set Voice to \"auto\" (no restart needed), or enable the mod in the"
                        + " launcher (that needs a game restart).";
            case NOT_SET_UP:
                return "Read Aloud: AI voices aren't set up yet, so " + what + ". Run " + START + " once. " + NO_RESTART;
            case STARTING:
            case NOT_RUNNING:
                return "Read Aloud: the AI voice app isn't answering" + (kind == Kind.STARTING ? " yet (still starting)" : "")
                        + ", so " + what + ". " + (autostart ? "The game tries starting it again on a later read; or run "
                        : "Start it with ") + START + ". " + NO_RESTART;
            case LOADING:
                return "Read Aloud: the AI voice app is still loading " + engineName() + ", so " + what + ". " + NO_RESTART;
            case ERROR:
                return "Read Aloud: the AI voice app has a problem" + (error.isEmpty() ? "" : " (" + error + ")") + ", so "
                        + what + ". Open " + page() + " to fix it. " + NO_RESTART;
            default:
                return null;
        }
    }
}
