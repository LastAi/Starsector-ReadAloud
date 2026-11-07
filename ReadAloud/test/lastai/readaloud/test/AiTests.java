package lastai.readaloud.test;

import lastai.readaloud.Config;
import lastai.readaloud.ReadAloud;
import lastai.readaloud.speech.AiMenu;
import lastai.readaloud.speech.CompanionMonitor;
import lastai.readaloud.speech.CompanionStatus;
import lastai.readaloud.text.VoiceParts;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** The AI voice add-on: status words, its settings page, the monitor, and the add-on's own files. */
public final class AiTests {
    static Config cfg(String backend) {
        Config c = new Config();
        c.backend = backend;
        return c;
    }

    static JSONObject kitten() throws Exception {
        return new JSONObject().put("ok", true).put("engine_id", "kitten").put("engine", "Kitten TTS").put("state", "ready")
                .put("voice_type", "name").put("port", 47700).put("use_pools", true)
                .put("voices", new JSONArray(Arrays.asList("Bella", "Jasper", "Luna", "Bruno", "Rosie", "Hugo", "Kiki", "Leo")))
                .put("clips", new JSONArray())
                .put("slots", new JSONObject().put("default", "Jasper").put("male", "Bruno").put("female", "Bella"))
                .put("pools", new JSONObject().put("male", new JSONArray(Arrays.asList("Hugo", "Leo"))).put("female", new JSONArray()));
    }

    // ---- status ----

    public static void statusSaysWhetherARestartIsNeeded() throws Exception {
        Config auto = cfg("auto");
        String noMod = CompanionStatus.of(false, auto, false, false, null).text();
        T.yes(noMod.contains("isn't enabled") && noMod.contains("needs a game restart"), noMod);
        CompanionStatus[] noRestart = {
                CompanionStatus.of(true, cfg("system"), true, false, null),
                CompanionStatus.of(true, auto, false, false, null),
                CompanionStatus.of(true, auto, true, true, null),
                CompanionStatus.of(true, auto, true, false, null),
                CompanionStatus.of(true, auto, true, false, new JSONObject().put("state", "loading").put("engine", "Kitten TTS")),
                CompanionStatus.of(true, auto, true, false, new JSONObject().put("state", "error").put("error", "not installed")),
                CompanionStatus.of(true, auto, true, false, new JSONObject().put("ready", true).put("engine", "Kitten TTS")),
        };
        CompanionStatus.Kind[] kinds = {CompanionStatus.Kind.NOT_USED, CompanionStatus.Kind.NOT_SET_UP, CompanionStatus.Kind.STARTING,
                CompanionStatus.Kind.NOT_RUNNING, CompanionStatus.Kind.LOADING, CompanionStatus.Kind.ERROR, CompanionStatus.Kind.READY};
        for (int i = 0; i < kinds.length; i++) {
            T.eq(kinds[i], noRestart[i].kind, "kind " + i);
            T.yes(noRestart[i].text().toLowerCase().contains("no game restart needed"), noRestart[i].text());
        }
        T.yes(noRestart[1].text().contains("start.cmd"), "not set up: says what to run");
        T.yes(noRestart[5].text().contains("not installed") && noRestart[5].text().contains("http://127.0.0.1:47700/"), "error and where to fix it");
        Config off = cfg("auto");
        off.companionAutostart = false;
        T.yes(CompanionStatus.of(true, off, true, false, null).text().contains("automatically is off"), "autostart off");
        T.eq(CompanionStatus.Kind.READY, CompanionStatus.of(false, auto, false, false, new JSONObject().put("ready", true)).kind,
                "an app that answers is ready even without the add-on (started by hand)");
    }

    public static void noticesForReadsThatCantUseTheApp() throws Exception {
        Config auto = cfg("auto"), comp = cfg("companion");
        T.eq(null, CompanionStatus.of(false, auto, false, false, null).notice(true), "no add-on, auto: the system voice is normal");
        String nomod = CompanionStatus.of(false, comp, false, false, null).notice(false);
        T.yes(nomod.contains("nothing is read") && nomod.contains("needs a game restart") && nomod.contains("no restart needed"), nomod);
        String down = CompanionStatus.of(true, auto, true, false, null).notice(true);
        T.yes(down.contains("system voice is reading") && down.contains("No game restart needed"), down);
        T.yes(CompanionStatus.of(true, comp, true, false, null).notice(false).contains("nothing is read"), "companion only");
        T.yes(CompanionStatus.of(true, auto, false, false, null).notice(true).contains("aren't set up"), "not set up");
        T.eq(null, CompanionStatus.of(true, auto, true, false, new JSONObject().put("ready", true)).notice(true), "ready: nothing to say");
    }

    // ---- the add-on's page ----

    static AiMenu.Field field(List<AiMenu.Field> fs, String id) {
        for (AiMenu.Field f : fs) if (f.id.equals(id)) return f;
        return null;
    }

    static List<String> ids(List<AiMenu.Field> fs) {
        List<String> out = new ArrayList<String>();
        for (AiMenu.Field f : fs) out.add(f.id);
        return out;
    }

    public static void pageWithoutTheAppIsJustTheStatus() {
        List<AiMenu.Field> fs = AiMenu.aiPage(CompanionStatus.of(true, cfg("auto"), true, false, null));
        T.eq(Arrays.asList(AiMenu.STATUS, AiMenu.MORE), ids(fs), "fields");
        T.yes(field(fs, AiMenu.STATUS).text.contains("isn't running"), field(fs, AiMenu.STATUS).text);
        T.yes(AiMenu.AI_IDS.containsAll(ids(fs)), "only managed ids");
        List<AiMenu.Field> ra = AiMenu.readAloudPage(CompanionStatus.of(false, cfg("auto"), false, false, null));
        T.eq(AiMenu.RA_IDS, ids(ra), "Read Aloud's page");
        T.yes(ra.get(1).text.contains("needs a game restart"), ra.get(1).text);
    }

    public static void pageListsTheAppsVoices() throws Exception {
        List<AiMenu.Field> fs = AiMenu.aiPage(CompanionStatus.of(true, cfg("auto"), true, false, kitten()));
        T.eq(Arrays.asList(AiMenu.STATUS, AiMenu.VOICES_HEADER, AiMenu.SUMMARY, AiMenu.NARRATOR, AiMenu.MALE, AiMenu.FEMALE,
                AiMenu.POOLS, AiMenu.MORE), ids(fs), "fields");
        T.yes(AiMenu.AI_IDS.containsAll(ids(fs)), "only managed ids");
        T.eq("Voices: Kitten TTS", field(fs, AiMenu.VOICES_HEADER).text, "engine");
        String sum = field(fs, AiMenu.SUMMARY).text;
        T.yes(sum.contains("Narrator: Jasper") && sum.contains("a pool of 2 (Hugo, Leo), else Bruno") && sum.contains("Female characters: Bella"), sum);
        AiMenu.Field male = field(fs, AiMenu.MALE);
        T.eq("Bruno", male.value, "chosen voice selected");
        T.eq("Engine default,Jasper,Bruno,Bella,Hugo,Leo,Luna,Rosie,Kiki", male.options, "chosen and pool voices first");
        T.eq("true", field(fs, AiMenu.POOLS).value, "pools on");
        T.yes(field(fs, AiMenu.MORE).text.contains("http://127.0.0.1:47700/"), "page address");
    }

    public static void longVoiceListsAreCut() throws Exception {
        JSONObject a = kitten();
        JSONArray many = new JSONArray();
        for (int i = 0; i < 115; i++) many.put("Voice" + i);
        many.put("Has, comma");
        a.put("voices", many).put("slots", new JSONObject().put("default", "Voice100").put("male", "").put("female", "Voice7"))
                .put("pools", new JSONObject());
        List<AiMenu.Field> fs = AiMenu.aiPage(CompanionStatus.of(true, cfg("auto"), true, false, a));
        String[] opts = field(fs, AiMenu.NARRATOR).options.split(",");
        T.eq(21, opts.length, "Engine default + 20");
        T.eq("Voice100", opts[1], "the chosen voice is in the list");
        T.eq("Engine default", field(fs, AiMenu.MALE).value, "empty slot");
        T.yes(field(fs, AiMenu.SUMMARY).text.contains("20 of 116 voices"), field(fs, AiMenu.SUMMARY).text);
    }

    public static void clipEnginesShowFileNames() throws Exception {
        JSONObject a = kitten().put("engine_id", "f5").put("engine", "F5-TTS").put("voice_type", "clip").put("voices", new JSONArray())
                .put("clips", new JSONArray(Arrays.asList("C:\\rt\\voices\\anna.wav", "C:\\rt\\voices\\bo.wav")))
                .put("slots", new JSONObject().put("default", "C:\\rt\\voices\\anna.wav").put("male", "").put("female", ""))
                .put("pools", new JSONObject());
        CompanionStatus s = CompanionStatus.of(true, cfg("auto"), true, false, a);
        List<AiMenu.Field> fs = AiMenu.aiPage(s);
        T.eq("Engine default,anna.wav,bo.wav", field(fs, AiMenu.NARRATOR).options, "file names");
        T.eq("anna.wav", field(fs, AiMenu.NARRATOR).value, "selected");
        Map<String, String> radios = new HashMap<String, String>();
        radios.put(AiMenu.NARRATOR, "anna.wav");
        radios.put(AiMenu.FEMALE, "bo.wav");
        AiMenu.Change c = AiMenu.change(s, radios, null);
        T.eq("C:\\rt\\voices\\bo.wav", c.body.getJSONObject("engines").getJSONObject("f5").getJSONObject("voices")
                .getJSONObject("female").getString("ref"), "clip path sent");
        T.eq(Arrays.asList(VoiceParts.FEMALE), c.roles, "sample for the changed voice only");
        a.put("clips", new JSONArray());
        T.yes(field(AiMenu.aiPage(CompanionStatus.of(true, cfg("auto"), true, false, a)), AiMenu.NARRATOR) == null, "no clips: no lists");
    }

    public static void savingSendsOnlyWhatChanged() throws Exception {
        CompanionStatus s = CompanionStatus.of(true, cfg("auto"), true, false, kitten());
        Map<String, String> radios = new HashMap<String, String>();
        radios.put(AiMenu.NARRATOR, "Jasper");
        radios.put(AiMenu.MALE, "Bruno");
        radios.put(AiMenu.FEMALE, "Bella");
        T.eq(null, AiMenu.change(s, radios, true).body, "nothing changed");
        radios.put(AiMenu.NARRATOR, "Luna");
        radios.put(AiMenu.MALE, "Engine default");
        radios.put(AiMenu.FEMALE, "Gone From The List");
        AiMenu.Change c = AiMenu.change(s, radios, false);
        JSONObject v = c.body.getJSONObject("engines").getJSONObject("kitten").getJSONObject("voices");
        T.eq("Luna", v.getString("default"), "narrator");
        T.eq("", v.getString("male"), "engine default is empty");
        T.no(v.has("female"), "unknown entry ignored");
        T.no(c.body.getBoolean("use_pools"), "pools off");
        T.eq(Arrays.asList(VoiceParts.NARRATOR, VoiceParts.MALE), c.roles, "samples");
        T.eq(null, AiMenu.change(CompanionStatus.of(true, cfg("auto"), true, false, null), radios, false).body, "no app: nothing");
    }

    // ---- the monitor ----

    public static void monitorReportsChangesOnly() throws Exception {
        try (FakeCompanion fake = new FakeCompanion()) {
            fake.voicesView = kitten();
            fake.ready = false;
            final Config c = cfg("auto");
            c.companionPort = fake.port();
            final List<CompanionStatus> seen = new CopyOnWriteArrayList<CompanionStatus>();
            CompanionMonitor m = new CompanionMonitor(new CompanionMonitor.Env() {
                @Override
                public Config config() {
                    return c;
                }

                @Override
                public boolean modEnabled() {
                    return true;
                }

                @Override
                public boolean setUp() {
                    return true;
                }

                @Override
                public boolean starting() {
                    return false;
                }
            }, new CompanionMonitor.Listener() {
                @Override
                public void changed(CompanionStatus s) {
                    seen.add(s);
                }
            }, 1000);
            m.check();
            m.check();
            T.eq(1, seen.size(), "same state: one report");
            T.eq(CompanionStatus.Kind.LOADING, seen.get(0).kind, "loading");
            fake.ready = true;
            m.check();
            T.eq(2, seen.size(), "ready reported");
            T.eq(CompanionStatus.Kind.READY, m.last().kind, "ready");
            fake.voicesView = kitten().put("slots", new JSONObject().put("default", "Luna"));
            m.check();
            T.eq(3, seen.size(), "a voice changed in the app: reported");
            fake.close();
            m.check();
            T.eq(CompanionStatus.Kind.NOT_RUNNING, m.last().kind, "app gone");
        }
    }

    // ---- the add-on's files ----

    static Path aiMod() {
        return Paths.get(System.getProperty("readaloud.aiModDir", "../ReadAloudAI"));
    }

    public static void addOnModFiles() throws Exception {
        JSONObject info = new JSONObject(new String(Files.readAllBytes(aiMod().resolve("mod_info.json")), StandardCharsets.UTF_8));
        T.eq(ReadAloud.AI_MOD_ID, info.getString("id"), "mod id");
        T.eq(ReadAloud.AI_MOD_NAME, info.getString("name"), "name");
        T.eq(ReadAloud.MOD_ID, info.getJSONArray("dependencies").getJSONObject(0).getString("id"), "needs Read Aloud");
        T.no(info.has("jars"), "no code of its own");
        T.yes(Files.isRegularFile(aiMod().resolve("companion/start.cmd")), "the app is in the add-on");
        T.yes(Files.isRegularFile(aiMod().resolve("companion/app/server.py")), "server");
        List<String> lines = Files.readAllLines(aiMod().resolve("data/config/LunaSettings.csv"), StandardCharsets.UTF_8);
        for (String line : lines.subList(1, lines.size())) {
            if (line.trim().isEmpty()) continue;
            String id = line.split(",", 2)[0];
            T.yes(id.startsWith("readaloudai_"), "prefix: " + id);
            T.no(AiMenu.AI_IDS.contains(id), "a CSV field is never replaced in code: " + id);
        }
        byte[] cmd = Files.readAllBytes(aiMod().resolve("companion/start.cmd"));
        T.yes(new String(cmd, StandardCharsets.ISO_8859_1).contains("\r\n"), "start.cmd has CRLF line ends");
    }
}
