package lastai.readaloud;

import lastai.readaloud.speech.SystemVoices;
import lastai.readaloud.text.VoiceParts;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Settings from data/config/readaloud.json, optionally overridden by LunaLib.
 * Every value has a safe default; a bad value is logged and replaced by its default.
 */
public final class Config {
    public static final String BACKEND_AUTO = "auto";
    public static final String BACKEND_COMPANION = "companion";
    public static final String BACKEND_SYSTEM = "system";
    public static final String BACKEND_OFF = "off";
    public static final String PLACE_END_OF_TEXT = "endOfText";
    public static final String PLACE_CORNER = "corner";

    public static final String PREVIEW_CHANGED = "changed";
    public static final String PREVIEW_ALL = "all";
    public static final String PREVIEW_OFF = "off";
    /** LunaLib fields added at startup with this computer's voice list (not in LunaSettings.csv). */
    public static final String FIELD_VOICE_HEADER = "readaloud_voiceHeader";
    public static final String FIELD_VOICE_NARRATOR = "readaloud_voiceNarrator";
    public static final String FIELD_VOICE_MALE = "readaloud_voiceMale";
    public static final String FIELD_VOICE_FEMALE = "readaloud_voiceFemale";
    public static final String FIELD_VOICE_POOLS = "readaloud_systemVoicePools";

    /** LWJGL Keyboard.KEY_F8. */
    public static final int DEFAULT_HOTKEY = 66;

    public String backend = BACKEND_AUTO;
    public int companionPort = 47700;
    public boolean companionAutostart = true;
    public boolean autoRead = false;
    public boolean readOptions = false;
    public boolean stopOnClose = true;
    public boolean showButton = true;
    public float buttonSize = 22f;
    public String buttonPlacement = PLACE_END_OF_TEXT;
    /** LWJGL key code; 0 = no hotkey. */
    public int hotkey = DEFAULT_HOTKEY;
    public int systemRate = 0;
    public int systemVolume = 100;
    /** Narrator system voice; "" = automatic. */
    public String systemVoice = "";
    /** System voice for male characters; "" = automatic (an installed male voice). */
    public String systemVoiceMale = "";
    /** System voice for female characters; "" = automatic (an installed female voice). */
    public String systemVoiceFemale = "";
    /** System voice pools: each named character keeps one installed voice of their gender. */
    public boolean systemVoicePools = false;
    /** Pool voices by name; empty = every usable installed voice of that gender. */
    public List<String> systemPoolMale = Collections.emptyList();
    public List<String> systemPoolFemale = Collections.emptyList();
    /** Who reads what: VoiceParts.MODE_QUOTES, MODE_CHARACTER or MODE_SINGLE. */
    public String voiceMode = VoiceParts.MODE_QUOTES;
    /** Samples played when settings are saved: PREVIEW_CHANGED, PREVIEW_ALL or PREVIEW_OFF. */
    public String voicePreview = PREVIEW_CHANGED;
    public List<Replacement> replacements = Collections.emptyList();

    /** A regex replacement applied to the text before it is spoken. */
    public static final class Replacement {
        public final Pattern pattern;
        public final String with;

        public Replacement(Pattern pattern, String with) {
            this.pattern = pattern;
            this.with = with;
        }
    }

    /** Turns a key name (LWJGL style, e.g. "F8") into a key code. Returns -1 if unknown. */
    public interface KeyNames {
        int codeOf(String name);
    }

    /** Values from LunaLib. Each getter returns null when the value isn't available. */
    public interface Overrides {
        String getString(String field);

        Boolean getBoolean(String field);

        Integer getInt(String field);
    }

    /** Reads the JSON file's values. Unknown keys are ignored; bad values are logged and defaulted. */
    public static Config fromJson(JSONObject json, KeyNames keys) {
        Config c = new Config();
        if (json == null) return c;
        c.backend = oneOf(json.optString("backend", c.backend), c.backend, "backend",
                BACKEND_AUTO, BACKEND_COMPANION, BACKEND_SYSTEM, BACKEND_OFF);
        c.companionPort = clampInt(json, "companionPort", c.companionPort, 1, 65535);
        c.companionAutostart = json.optBoolean("companionAutostart", c.companionAutostart);
        c.autoRead = json.optBoolean("autoRead", c.autoRead);
        c.readOptions = json.optBoolean("readOptions", c.readOptions);
        c.stopOnClose = json.optBoolean("stopOnClose", c.stopOnClose);
        c.showButton = json.optBoolean("showButton", c.showButton);
        c.buttonSize = clampInt(json, "buttonSize", (int) c.buttonSize, 12, 64);
        c.buttonPlacement = oneOf(json.optString("buttonPlacement", c.buttonPlacement), c.buttonPlacement,
                "buttonPlacement", PLACE_END_OF_TEXT, PLACE_CORNER);
        if (json.has("hotkey")) {
            String name = json.optString("hotkey", "").trim();
            if (name.isEmpty()) {
                c.hotkey = 0;
            } else {
                int code = keys == null ? -1 : keys.codeOf(name);
                if (code > 0) c.hotkey = code;
                else ReadAloudLog.warn("readaloud.json: unknown hotkey \"" + name + "\"; using F8");
            }
        }
        c.systemRate = clampInt(json, "systemRate", c.systemRate, -10, 10);
        c.systemVolume = clampInt(json, "systemVolume", c.systemVolume, 0, 100);
        c.systemVoice = voiceName(json.optString("systemVoice", c.systemVoice));
        c.systemVoiceMale = voiceName(json.optString("systemVoiceMale", c.systemVoiceMale));
        c.systemVoiceFemale = voiceName(json.optString("systemVoiceFemale", c.systemVoiceFemale));
        c.systemVoicePools = json.optBoolean("systemVoicePools", c.systemVoicePools);
        c.systemPoolMale = names(json.optJSONArray("systemPoolMale"));
        c.systemPoolFemale = names(json.optJSONArray("systemPoolFemale"));
        c.voiceMode = oneOf(json.optString("voiceMode", c.voiceMode), c.voiceMode, "voiceMode",
                VoiceParts.MODE_QUOTES, VoiceParts.MODE_CHARACTER, VoiceParts.MODE_SINGLE);
        c.voicePreview = oneOf(json.optString("voicePreview", c.voicePreview), c.voicePreview, "voicePreview",
                PREVIEW_CHANGED, PREVIEW_ALL, PREVIEW_OFF);
        c.replacements = parseReplacements(json.optJSONArray("replacements"));
        return c;
    }

    /** Voice names from a JSON array; blanks skipped. */
    private static List<String> names(JSONArray arr) {
        if (arr == null) return Collections.emptyList();
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "").trim();
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
        }
        return out;
    }

    /** "" for automatic ("" or the menu's "Automatic"), else the trimmed name. */
    static String voiceName(String s) {
        String t = s == null ? "" : s.trim();
        return t.equalsIgnoreCase(SystemVoices.AUTOMATIC) ? "" : t;
    }

    /** The system voice name for a role ("" = automatic). */
    public String systemVoiceFor(String role) {
        if (VoiceParts.MALE.equals(role)) return systemVoiceMale;
        if (VoiceParts.FEMALE.equals(role)) return systemVoiceFemale;
        return systemVoice;
    }

    /** Applies LunaLib values on top of this config (null values are skipped). */
    public void applyOverrides(Overrides o) {
        if (o == null) return;
        String s = o.getString("readaloud_backend");
        if (s != null) backend = oneOf(s, backend, "readaloud_backend", BACKEND_AUTO, BACKEND_COMPANION, BACKEND_SYSTEM, BACKEND_OFF);
        Boolean b;
        if ((b = o.getBoolean("readaloud_autoRead")) != null) autoRead = b;
        if ((b = o.getBoolean("readaloud_readOptions")) != null) readOptions = b;
        if ((b = o.getBoolean("readaloud_stopOnClose")) != null) stopOnClose = b;
        if ((b = o.getBoolean("readaloud_showButton")) != null) showButton = b;
        if ((b = o.getBoolean("readaloud_companionAutostart")) != null) companionAutostart = b;
        if ((b = o.getBoolean(FIELD_VOICE_POOLS)) != null) systemVoicePools = b;
        s = o.getString("readaloud_buttonPlacement");
        if (s != null) buttonPlacement = oneOf(s, buttonPlacement, "readaloud_buttonPlacement", PLACE_END_OF_TEXT, PLACE_CORNER);
        Integer i;
        if ((i = o.getInt("readaloud_hotkey")) != null && i >= 0) hotkey = i;
        if ((i = o.getInt("readaloud_systemRate")) != null) systemRate = Math.max(-10, Math.min(10, i));
        if ((i = o.getInt("readaloud_systemVolume")) != null) systemVolume = Math.max(0, Math.min(100, i));
        s = o.getString("readaloud_voiceMode");
        if (s != null) voiceMode = oneOf(s, voiceMode, "readaloud_voiceMode",
                VoiceParts.MODE_QUOTES, VoiceParts.MODE_CHARACTER, VoiceParts.MODE_SINGLE);
        s = o.getString("readaloud_voicePreview");
        if (s != null) voicePreview = oneOf(s, voicePreview, "readaloud_voicePreview", PREVIEW_CHANGED, PREVIEW_ALL, PREVIEW_OFF);
        // The voice menus exist only when the voice list could be read; without them (null) the file's value stays.
        if ((s = o.getString(FIELD_VOICE_NARRATOR)) != null) systemVoice = voiceName(s);
        if ((s = o.getString(FIELD_VOICE_MALE)) != null) systemVoiceMale = voiceName(s);
        if ((s = o.getString(FIELD_VOICE_FEMALE)) != null) systemVoiceFemale = voiceName(s);
    }

    public boolean speechOff() {
        return BACKEND_OFF.equals(backend);
    }

    private static List<Replacement> parseReplacements(JSONArray arr) {
        if (arr == null) return Collections.emptyList();
        List<Replacement> out = new ArrayList<Replacement>();
        for (int i = 0; i < arr.length(); i++) {
            JSONArray pair = arr.optJSONArray(i);
            if (pair == null || pair.length() != 2) {
                ReadAloudLog.warn("readaloud.json: replacements[" + i + "] must be [regex, replacement]; skipped");
                continue;
            }
            try {
                out.add(new Replacement(Pattern.compile(pair.getString(0)), pair.getString(1)));
            } catch (Exception e) {
                ReadAloudLog.warn("readaloud.json: replacements[" + i + "] is not a valid regex (" + e.getMessage() + "); skipped");
            }
        }
        return out;
    }

    private static int clampInt(JSONObject json, String key, int def, int min, int max) {
        if (!json.has(key)) return def;
        Object v = json.opt(key);
        Double d = null;
        // The game's JSON loader hands numbers over as text, so both forms are accepted.
        if (v instanceof Number) d = ((Number) v).doubleValue();
        else if (v instanceof String) {
            try {
                d = Double.parseDouble(((String) v).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (d == null || d.isNaN() || d.isInfinite()) {
            ReadAloudLog.warn("readaloud.json: \"" + key + "\" must be a number; using " + def);
            return def;
        }
        int n = (int) Math.round(d);
        if (n < min || n > max) {
            int c = Math.max(min, Math.min(max, n));
            ReadAloudLog.warn("readaloud.json: \"" + key + "\" must be " + min + ".." + max + "; using " + c);
            return c;
        }
        return n;
    }

    private static String oneOf(String value, String def, String key, String... allowed) {
        for (String a : allowed) if (a.equalsIgnoreCase(value.trim())) return a;
        ReadAloudLog.warn("readaloud settings: \"" + key + "\" can't be \"" + value + "\"; using \"" + def + "\"");
        return def;
    }
}
