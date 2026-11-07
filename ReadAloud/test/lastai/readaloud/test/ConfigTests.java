package lastai.readaloud.test;

import lastai.readaloud.Config;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ConfigTests {
    static String modFile(String rel) throws Exception {
        return new String(Files.readAllBytes(Paths.get(System.getProperty("readaloud.modDir"), rel)), StandardCharsets.UTF_8);
    }

    static final Config.KeyNames KEYS = new Config.KeyNames() {
        @Override
        public int codeOf(String name) {
            if (name.equals("F8")) return 66;
            if (name.equals("R")) return 19;
            return -1;
        }
    };

    /** Removes # comments outside strings, as the game's loader does before handing the text to org.json. */
    static String stripComments(String s) {
        StringBuilder out = new StringBuilder();
        boolean inString = false, comment = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (comment) {
                if (c == '\n') {
                    comment = false;
                    out.append(c);
                }
                continue;
            }
            if (inString && c == '\\' && i + 1 < s.length()) {
                out.append(c).append(s.charAt(++i));
                continue;
            }
            if (c == '"') inString = !inString;
            if (c == '#' && !inString) {
                comment = true;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    public static void shippedFileParsesToDefaults() throws Exception {
        // org.json (json.jar) accepts the trailing commas; comments are stripped first, as in game.
        Config c = Config.fromJson(new JSONObject(stripComments(modFile("data/config/readaloud.json"))), KEYS);
        Config d = new Config();
        T.eq(d.backend, c.backend, "backend");
        T.eq(d.companionPort, c.companionPort, "port");
        T.eq(d.autoRead, c.autoRead, "autoRead");
        T.eq(d.hotkey, c.hotkey, "hotkey");
        T.eq(d.buttonPlacement, c.buttonPlacement, "placement");
        T.eq(d.buttonSize, c.buttonSize, "size");
        T.eq(0, c.replacements.size(), "replacements");
    }

    /** The game's JSON loader passes numbers as text; in game every number setting was ignored. */
    public static void numbersGivenAsTextAreAccepted() throws Exception {
        JSONObject j = new JSONObject();
        j.put("companionPort", "47710");
        j.put("buttonSize", "30");
        j.put("systemRate", "-2.0");
        j.put("systemVolume", " 80 ");
        Config c = Config.fromJson(j, KEYS);
        T.eq(47710, c.companionPort, "port");
        T.eq(30f, c.buttonSize, "size");
        T.eq(-2, c.systemRate, "rate");
        T.eq(80, c.systemVolume, "volume");
    }

    public static void badValuesFallBackToDefaults() throws Exception {
        JSONObject j = new JSONObject("{backend:'loud', companionPort:'x', systemRate:99, hotkey:'NOPE', buttonPlacement:'left',"
                + " replacements:[['(', 'x'], ['ok', 'fine'], 'junk']}");
        Config c = Config.fromJson(j, KEYS);
        T.eq("auto", c.backend, "backend");
        T.eq(47700, c.companionPort, "port");
        T.eq(10, c.systemRate, "rate clamped");
        T.eq(66, c.hotkey, "hotkey default");
        T.eq("endOfText", c.buttonPlacement, "placement");
        T.eq(1, c.replacements.size(), "only the valid replacement");
    }

    public static void emptyHotkeyDisablesIt() throws Exception {
        T.eq(0, Config.fromJson(new JSONObject("{hotkey:''}"), KEYS).hotkey, "none");
        T.eq(19, Config.fromJson(new JSONObject("{hotkey:'R'}"), KEYS).hotkey, "R");
        T.eq("system", Config.fromJson(new JSONObject("{backend:'SYSTEM'}"), KEYS).backend, "case-insensitive");
    }

    /** Every LunaLib field in the CSV is read by Config, and Config reads no field the CSV lacks. */
    public static void lunaFieldsMatchTheCsv() throws Exception {
        Set<String> csv = new HashSet<String>();
        List<String> lines = Files.readAllLines(Paths.get(System.getProperty("readaloud.modDir"), "data/config/LunaSettings.csv"), StandardCharsets.UTF_8);
        for (String line : lines.subList(1, lines.size())) {
            if (line.trim().isEmpty()) continue;
            String[] cols = line.split(",", 4);
            if (!cols[2].equals("Header")) csv.add(cols[0]);
        }
        // Made in code at startup (they list this computer's voices), so not in the CSV.
        csv.addAll(Arrays.asList(Config.FIELD_VOICE_NARRATOR, Config.FIELD_VOICE_MALE, Config.FIELD_VOICE_FEMALE,
                Config.FIELD_VOICE_POOLS));
        final Set<String> asked = new HashSet<String>();
        Config c = new Config();
        c.applyOverrides(new Config.Overrides() {
            @Override
            public String getString(String field) {
                asked.add(field);
                return field.equals("readaloud_backend") ? "off" : null;
            }

            @Override
            public Boolean getBoolean(String field) {
                asked.add(field);
                return field.equals("readaloud_autoRead") ? Boolean.TRUE : null;
            }

            @Override
            public Integer getInt(String field) {
                asked.add(field);
                return field.equals("readaloud_systemRate") ? -50 : null;
            }
        });
        T.eq(csv, asked, "LunaLib field ids");
        T.eq("off", c.backend, "override applied");
        T.yes(c.autoRead, "boolean override");
        T.eq(-10, c.systemRate, "int override clamped");
        T.yes(c.speechOff(), "off");
    }
}
