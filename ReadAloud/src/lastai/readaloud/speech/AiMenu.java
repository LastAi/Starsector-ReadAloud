package lastai.readaloud.speech;

import lastai.readaloud.text.VoiceParts;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The settings-menu fields made from what the AI voice app reports: its status, engine,
 * voices, chosen voices and pools. They go on the add-on's own LunaLib page (and a status
 * line on Read Aloud's page) and are rebuilt whenever that changes. Pure, so it is unit tested;
 * LunaBridge turns the fields into LunaLib settings.
 */
public final class AiMenu {
    /** One LunaLib field. type: Header, Text, Radio or Boolean. */
    public static final class Field {
        public final String type;
        public final String id;
        public final String name;
        /** Header / Text: the text shown. Radio / Boolean: the tooltip. */
        public final String text;
        /** Radio: the selected entry. Boolean: "true" or "false". */
        public final String value;
        /** Radio: entries, comma-separated. */
        public final String options;

        Field(String type, String id, String name, String text, String value, String options) {
            this.type = type;
            this.id = id;
            this.name = name;
            this.text = text;
            this.value = value;
            this.options = options;
        }

        @Override
        public String toString() {
            return type + " " + id + "=" + value + " [" + options + "] " + text;
        }
    }

    public static final String STATUS = "readaloudai_status";
    public static final String VOICES_HEADER = "readaloudai_voicesHeader";
    public static final String SUMMARY = "readaloudai_summary";
    public static final String NARRATOR = "readaloudai_narrator";
    public static final String MALE = "readaloudai_male";
    public static final String FEMALE = "readaloudai_female";
    public static final String POOLS = "readaloudai_pools";
    public static final String MORE = "readaloudai_more";
    /** Every field this class may put on the add-on's page (so old ones can be taken off). */
    public static final List<String> AI_IDS = Collections.unmodifiableList(Arrays.asList(
            STATUS, VOICES_HEADER, SUMMARY, NARRATOR, MALE, FEMALE, POOLS, MORE));

    /** On Read Aloud's own page. */
    public static final String RA_HEADER = "readaloud_aiHeader";
    public static final String RA_STATUS = "readaloud_aiStatus";
    public static final List<String> RA_IDS = Collections.unmodifiableList(Arrays.asList(RA_HEADER, RA_STATUS));

    /** The entry for the slot's empty value. */
    public static final String ENGINE_DEFAULT = "Engine default";
    /** Most voices in one menu list; more is unusable (Kitten TTS 2 has 115). */
    static final int MAX_CHOICES = 20;

    /** Radio field id -> the app's voice slot. */
    static final Map<String, String> SLOTS = new LinkedHashMap<String, String>();

    static {
        SLOTS.put(NARRATOR, "default");
        SLOTS.put(MALE, "male");
        SLOTS.put(FEMALE, "female");
    }

    private AiMenu() {}

    /** The status line for Read Aloud's page. */
    public static List<Field> readAloudPage(CompanionStatus s) {
        return Arrays.asList(new Field("Header", RA_HEADER, "AI voices", "AI voices", "", ""),
                new Field("Text", RA_STATUS, "AI voice status", s.text() + " The " + lastai.readaloud.ReadAloud.AI_MOD_NAME
                        + " page has the voice settings. Reopen this menu to see a newer status.", "", ""));
    }

    /** The add-on's page: status, then (when the app answers) its voices. */
    public static List<Field> aiPage(CompanionStatus s) {
        List<Field> out = new ArrayList<Field>();
        out.add(new Field("Text", STATUS, "Status", s.text() + " (Reopen this menu to see a newer status.)", "", ""));
        JSONObject a = s.answer;
        if (a != null) {
            String engine = a.optString("engine", "");
            out.add(new Field("Header", VOICES_HEADER, "Voices", "Voices: " + (engine.isEmpty() ? "?" : engine), "", ""));
            List<String> all = entries(a);
            out.add(new Field("Text", SUMMARY, "Voices in use", summary(a, all.size()), "", ""));
            if (!all.isEmpty()) {
                List<String> shown = menuEntries(a, all);
                String options = join(labels(shown));
                for (Map.Entry<String, String> e : SLOTS.entrySet()) {
                    String slot = e.getValue();
                    out.add(new Field("Radio", e.getKey(), slotTitle(slot), slotTooltip(slot),
                            label(slots(a).optString(slot, "")), options));
                }
            }
            out.add(new Field("Boolean", POOLS, "Voice pools",
                    "Each character gets their own voice from the pool of their gender, and keeps it. The pools are"
                            + " ticked on the app's page. Off: every male character uses the male voice, every female"
                            + " character the female voice.", String.valueOf(a.optBoolean("use_pools", true)), ""));
        }
        int port = a != null ? a.optInt("port", s.port) : s.port;
        out.add(new Field("Text", MORE, "More settings", "Engines, models, speed, voice pools and every voice are on the app's page,"
                + " http://127.0.0.1:" + port + "/ (" + CompanionStatus.START + " opens it). Changing a voice here and saving"
                + " plays a sample in the AI voice.", "", ""));
        return out;
    }

    private static String slotTitle(String slot) {
        if ("male".equals(slot)) return "Male character voice";
        if ("female".equals(slot)) return "Female character voice";
        return "Narrator voice";
    }

    private static String slotTooltip(String slot) {
        if ("default".equals(slot)) return "Reads the narration (and everything, with one reader). " + ENGINE_DEFAULT
                + ": the engine's own voice.";
        String who = "male".equals(slot) ? "male" : "female";
        return "Reads what " + who + " characters say (with voice pools on: those the game can't tell apart, and"
                + " everyone when the pool is empty). " + ENGINE_DEFAULT + ": the narrator's voice.";
    }

    /** Voice names (name engines) or clip paths (clip engines) the app offers. */
    static List<String> entries(JSONObject a) {
        boolean clips = "clip".equals(a.optString("voice_type", "name"));
        return strings(a.optJSONArray(clips ? "clips" : "voices"));
    }

    static JSONObject slots(JSONObject a) {
        JSONObject s = a.optJSONObject("slots");
        return s == null ? new JSONObject() : s;
    }

    static List<String> pool(JSONObject a, String gender) {
        JSONObject p = a.optJSONObject("pools");
        return p == null ? Collections.<String>emptyList() : strings(p.optJSONArray(gender));
    }

    /**
     * What a menu list holds: the chosen voices and pool voices first, then the rest, at most
     * MAX_CHOICES; never entries with commas (LunaLib splits options at commas).
     */
    static List<String> menuEntries(JSONObject a, List<String> all) {
        List<String> first = new ArrayList<String>();
        for (String slot : SLOTS.values()) first.add(slots(a).optString(slot, ""));
        first.addAll(pool(a, "male"));
        first.addAll(pool(a, "female"));
        List<String> out = new ArrayList<String>();
        List<String> seenLabels = new ArrayList<String>();
        for (List<String> group : Arrays.asList(first, all)) {
            for (String v : group) {
                if (out.size() >= MAX_CHOICES) return out;
                String l = label(v);
                if (v.isEmpty() || l.contains(",") || seenLabels.contains(l)) continue;
                seenLabels.add(l);
                out.add(v);
            }
        }
        return out;
    }

    /** How a voice appears in a list: a clip's file name, a voice's name; "" is ENGINE_DEFAULT. */
    public static String label(String value) {
        if (value == null || value.isEmpty()) return ENGINE_DEFAULT;
        String v = value.replace('\\', '/');
        return v.contains("/") ? v.substring(v.lastIndexOf('/') + 1) : value;
    }

    static List<String> labels(List<String> values) {
        List<String> out = new ArrayList<String>();
        out.add(ENGINE_DEFAULT);
        for (String v : values) out.add(label(v));
        return out;
    }

    static String summary(JSONObject a, int total) {
        StringBuilder sb = new StringBuilder();
        JSONObject sl = slots(a);
        boolean pools = a.optBoolean("use_pools", true);
        sb.append("Narrator: ").append(label(sl.optString("default", ""))).append(". ");
        for (String g : Arrays.asList("male", "female")) {
            sb.append("male".equals(g) ? "Male" : "Female").append(" characters: ");
            List<String> p = pool(a, g);
            if (pools && !p.isEmpty()) {
                sb.append("a pool of ").append(p.size()).append(" (");
                for (int i = 0; i < p.size(); i++) {
                    if (i == 4) {
                        sb.append(", ...");
                        break;
                    }
                    if (i > 0) sb.append(", ");
                    sb.append(label(p.get(i)));
                }
                sb.append("), else ").append(label(sl.optString(g, "")));
            } else {
                sb.append(label(sl.optString(g, "")));
            }
            sb.append(". ");
        }
        if (!pools && (!pool(a, "male").isEmpty() || !pool(a, "female").isEmpty())) sb.append("Voice pools are off. ");
        if (total == 0) {
            sb.append("clip".equals(a.optString("voice_type")) ? "No voice clips yet: add them on the app's page."
                    : "The voice list appears once the engine is ready.");
        } else if (total > MAX_CHOICES) {
            sb.append("The lists below show ").append(MAX_CHOICES).append(" of ").append(total)
                    .append(" voices; the app's page has all of them.");
        }
        return sb.toString().trim();
    }

    /** What the player changed on the add-on's page, to send to the app. */
    public static final class Change {
        /** The body for POST /api/settings, or null when nothing changed. */
        public JSONObject body;
        /** Roles (VoiceParts.NARRATOR, MALE, FEMALE) whose voice changed, for samples. */
        public final List<String> roles = new ArrayList<String>();
    }

    /**
     * Compares the menu's values with what the app said.
     * @param radios field id -> selected entry (null entries: field not shown)
     */
    public static Change change(CompanionStatus s, Map<String, String> radios, Boolean pools) throws Exception {
        Change c = new Change();
        JSONObject a = s == null ? null : s.answer;
        if (a == null) return c;
        String eid = a.optString("engine_id", "");
        boolean clip = "clip".equals(a.optString("voice_type", "name"));
        List<String> all = entries(a);
        JSONObject voices = new JSONObject();
        for (Map.Entry<String, String> e : SLOTS.entrySet()) {
            String chosen = radios.get(e.getKey());
            if (chosen == null) continue;
            String value = valueOf(chosen, all);
            if (value == null) continue; // not in the list any more
            String slot = e.getValue();
            if (value.equals(slots(a).optString(slot, ""))) continue;
            voices.put(slot, clip ? new JSONObject().put("ref", value) : value);
            c.roles.add("default".equals(slot) ? VoiceParts.NARRATOR : slot);
        }
        JSONObject body = new JSONObject();
        if (voices.length() > 0 && !eid.isEmpty()) body.put("engines", new JSONObject().put(eid, new JSONObject().put("voices", voices)));
        if (pools != null && pools != a.optBoolean("use_pools", true)) body.put("use_pools", pools.booleanValue());
        c.body = body.length() > 0 ? body : null;
        return c;
    }

    /** The voice for a menu entry: "" for ENGINE_DEFAULT, null if none matches. */
    static String valueOf(String label, List<String> all) {
        if (ENGINE_DEFAULT.equals(label)) return "";
        for (String v : all) if (label(v).equals(label)) return v;
        return null;
    }

    private static List<String> strings(JSONArray arr) {
        List<String> out = new ArrayList<String>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        return sb.toString();
    }
}
