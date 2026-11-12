package lastai.readaloud.test;

import lastai.readaloud.Config;
import lastai.readaloud.dialog.SpeakerButton;
import lastai.readaloud.speech.SpeechRequest;
import lastai.readaloud.speech.SystemVoices;
import lastai.readaloud.speech.VoiceSamples;
import lastai.readaloud.text.TextCleaner;
import lastai.readaloud.text.VoiceParts;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Who reads what: quote splitting, voice settings, samples, and the system voice lists. */
public final class VoiceTests {
    static List<String> show(List<VoiceParts.Part> parts) {
        List<String> out = new ArrayList<String>();
        for (VoiceParts.Part p : parts) out.add(p.toString());
        return out;
    }

    static List<String> quotes(String gender, String... paragraphs) {
        return show(VoiceParts.split(Arrays.asList(paragraphs), VoiceParts.MODE_QUOTES, gender, true));
    }

    public static void quotesGoToTheCharacter() {
        T.eq(Arrays.asList("female:You've got a lot of nerve showing your face here, captain,",
                        "narrator:the station administrator says, leaning back in her chair."),
                quotes("female", "\"You've got a lot of nerve showing your face here, captain,\" the station administrator says, leaning back in her chair."),
                "quote then narration (leading comma dropped)");
        T.eq(Arrays.asList("narrator:He shrugs.", "male:Fine,", "narrator:he says, then adds,", "male:Go."),
                quotes("male", "He shrugs. “Fine,” he says, then adds, “Go.”"), "curly quotes");
        T.eq(Collections.singletonList("narrator:The comm link closes."), quotes("male", "The comm link closes."), "no quotes");
        T.eq(Collections.singletonList("male:Hello."), quotes("", "\"Hello.\""), "unknown gender -> male character voice");
    }

    /** Prose convention: a quote running over paragraphs reopens each one and closes only at the end. */
    public static void quoteOverSeveralParagraphs() {
        T.eq(Arrays.asList("narrator:She sighs.", "female:First, the fuel.\n\nThen the crew.", "narrator:She looks away."),
                quotes("female", "She sighs. \"First, the fuel.", "\"Then the crew.\" She looks away."), "joined across paragraphs");
    }

    public static void neighboursWithTheSameVoiceAreJoined() {
        T.eq(Collections.singletonList("narrator:Your fleet approaches.\n\nThe station looms."),
                quotes("male", "Your fleet approaches.", "The station looms."), "paragraphs kept apart by a blank line");
        T.eq(Collections.<String>emptyList(), quotes("male", "\"...\""), "nothing speakable");
    }

    public static void characterAndSingleModes() {
        List<String> paras = Arrays.asList("You connect.", "\"Captain.\" She nods.");
        T.eq(Collections.singletonList("female:You connect.\n\n\"Captain.\" She nods."),
                show(VoiceParts.split(paras, VoiceParts.MODE_CHARACTER, "female", true)), "character: everything by her");
        T.eq(Collections.singletonList("narrator:You connect.\n\n\"Captain.\" She nods."),
                show(VoiceParts.split(paras, VoiceParts.MODE_CHARACTER, "female", false)), "character mode without a person: narrator");
        T.eq(Collections.singletonList("narrator:You connect.\n\n\"Captain.\" She nods."),
                show(VoiceParts.split(paras, VoiceParts.MODE_SINGLE, "female", true)), "single reader");
    }

    /** Speech only (requested by Rook999): quotes in the character's voice, narration silent. */
    public static void speechModeSkipsTheNarrator() {
        T.eq(Arrays.asList("male:Fine, Go."),
                show(VoiceParts.split(Arrays.asList("He shrugs. “Fine,” he says, then adds, “Go.”"),
                        VoiceParts.MODE_SPEECH, "male", true)), "quotes kept and joined, narration dropped");
        T.eq(Arrays.asList("female:First, the fuel.\n\nThen the crew."),
                show(VoiceParts.split(Arrays.asList("She sighs. \"First, the fuel.", "\"Then the crew.\" She looks away."),
                        VoiceParts.MODE_SPEECH, "female", true)), "across paragraphs");
        T.eq(Collections.<String>emptyList(),
                show(VoiceParts.split(Arrays.asList("Your fleet approaches.", "The station looms."),
                        VoiceParts.MODE_SPEECH, "male", false)), "only narration: nothing to read");

        Config c = new Config();
        c.voiceMode = VoiceParts.MODE_SPEECH;
        List<VoiceParts.Part> parts = SpeakerButton.buildParts(
                TextCleaner.paragraphs(Arrays.asList("\"Luddic pilgrims,\" he says")),
                TextCleaner.choices(Arrays.asList("1. Leave [Esc]")), c, "male", true);
        T.eq(Arrays.asList("male:Luddic pilgrims,", "narrator:Your choices:", "narrator:Leave."), show(parts),
                "choices are still read when asked for");
    }

    static List<String> speech(int pauseMs, String... paragraphs) {
        return show(VoiceParts.split(Arrays.asList(paragraphs), VoiceParts.MODE_SPEECH, "male", true, pauseMs));
    }

    /** Speech mode with a pause: skipped narration between quotes becomes one fixed silence. */
    public static void speechModePausesWhereNarrationWas() {
        T.eq(Arrays.asList("male:Hello Commander", "(600ms)male:What can I do for you?"),
                speech(600, "\"Hello Commander\" the station manager's voice comes in over the comms. \"What can I do for you?\""),
                "the example");
        T.eq(Collections.singletonList("male:Hello Commander What can I do for you?"),
                speech(0, "\"Hello Commander\" the voice says. \"What can I do for you?\""), "pause 0: joined as before");
        T.eq(Arrays.asList("male:A", "(500ms)male:B"),
                speech(500, "Static. \"A\" he says.", "The line crackles.", "Then: \"B\" Silence."),
                "narration at start/end: no pause; several narration runs in a row: one pause");
        T.eq(Collections.singletonList("male:A B"), speech(500, "\"A\" \"B\""), "nothing between the quotes: no pause");
        T.eq(Collections.singletonList("male:First.\n\nSecond."), speech(500, "\"First.", "\"Second.\""),
                "a quote over two paragraphs is not interrupted");
        T.eq(Collections.singletonList("narrator:He nods. \"A\" Then \"B\""),
                show(VoiceParts.split(Arrays.asList("He nods. \"A\" Then \"B\""), VoiceParts.MODE_SINGLE, "male", true, 500)),
                "other modes ignore the pause");
    }

    public static void pauseSettingAndRequest() throws Exception {
        T.eq(500, new Config().narrationPause, "default");
        T.eq(1200, Config.fromJson(new JSONObject("{narrationPause:1200}"), null).narrationPause, "from the file");
        T.eq(Config.MAX_NARRATION_PAUSE, Config.fromJson(new JSONObject("{narrationPause:99999}"), null).narrationPause, "clamped");
        Config c = new Config();
        FakeLuna luna = new FakeLuna();
        luna.v.put("readaloud_narrationPause", 0);
        c.applyOverrides(luna);
        T.eq(0, c.narrationPause, "from Luna");

        c.voiceMode = VoiceParts.MODE_SPEECH;
        c.narrationPause = 700;
        c.replacements = Collections.singletonList(new Config.Replacement(Pattern.compile("Luddic"), "Luh-dick"));
        List<VoiceParts.Part> parts = SpeakerButton.buildParts(
                TextCleaner.paragraphs(Arrays.asList("\"Hi.\" He waves. \"Luddic?\"")), new ArrayList<String>(), c, "male", true);
        T.eq(Arrays.asList("male:Hi.", "(700ms)male:Luh-dick?"), show(parts), "replacements keep the pause");
        JSONObject j = new SpeechRequest(parts).toJson();
        T.no(j.getJSONArray("parts").getJSONObject(0).has("pause"), "no pause field without a pause");
        T.eq(700, j.getJSONArray("parts").getJSONObject(1).getInt("pause"), "pause in ms for the companion");
    }

    /** What the button sends: voice parts, then each choice as its own narrator part; replacements everywhere. */
    public static void buildPartsAddsChoicesAndReplacements() throws Exception {
        Config c = new Config();
        c.replacements = Collections.singletonList(new Config.Replacement(Pattern.compile("\\bLuddic\\b"), "Luh-dick"));
        List<VoiceParts.Part> parts = SpeakerButton.buildParts(
                TextCleaner.paragraphs(Arrays.asList("\"Luddic pilgrims,\" he says")),
                TextCleaner.choices(Arrays.asList("1. Leave [Esc]", "2. Ask about the Luddic Church")), c, "male", true);
        T.eq(Arrays.asList("male:Luh-dick pilgrims,", "narrator:he says.", "narrator:Your choices:", "narrator:Leave.",
                "narrator:Ask about the Luh-dick Church."), show(parts), "parts");
        SpeechRequest r = new SpeechRequest(parts);
        T.eq("Luh-dick pilgrims,\n\nhe says.\n\nYour choices:\n\nLeave.\n\nAsk about the Luh-dick Church.", r.text, "joined text for old apps");
        T.eq(5, r.toJson().getJSONArray("parts").length(), "parts in the JSON");
        T.eq("male", r.toJson().getJSONArray("parts").getJSONObject(0).getString("role"), "role in the JSON");
    }

    // ---- settings ----

    static final class FakeLuna implements Config.Overrides {
        final Map<String, Object> v = new HashMap<String, Object>();

        @Override
        public String getString(String f) {
            return (String) v.get(f);
        }

        @Override
        public Boolean getBoolean(String f) {
            return (Boolean) v.get(f);
        }

        @Override
        public Integer getInt(String f) {
            return (Integer) v.get(f);
        }
    }

    public static void voiceSettingsFromFileAndLuna() throws Exception {
        Config c = Config.fromJson(new JSONObject("{voiceMode:'character', systemVoice:'Microsoft Zira Desktop',"
                + " systemVoiceMale:'Automatic', systemVoiceFemale:' Microsoft Hazel Desktop ', voicePreview:'all'}"), null);
        T.eq("character", c.voiceMode, "mode");
        T.eq("Microsoft Zira Desktop", c.systemVoice, "narrator");
        T.eq("", c.systemVoiceMale, "Automatic = automatic");
        T.eq("Microsoft Hazel Desktop", c.systemVoiceFemale, "trimmed");
        T.eq("all", c.voicePreview, "preview");
        T.eq("quotes", Config.fromJson(new JSONObject("{voiceMode:'loud'}"), null).voiceMode, "bad mode -> default");
        T.eq("quotes", new Config().voiceMode, "default mode");
        T.eq("speech", Config.fromJson(new JSONObject("{voiceMode:'speech'}"), null).voiceMode, "speech mode from the file");
        Config sp = new Config();
        FakeLuna lunaSpeech = new FakeLuna();
        lunaSpeech.v.put("readaloud_voiceMode", "speech");
        sp.applyOverrides(lunaSpeech);
        T.eq("speech", sp.voiceMode, "speech mode from Luna");

        FakeLuna luna = new FakeLuna();
        luna.v.put("readaloud_voiceMode", "single");
        luna.v.put(Config.FIELD_VOICE_NARRATOR, "Automatic");
        luna.v.put(Config.FIELD_VOICE_MALE, "Microsoft David Desktop");
        c.applyOverrides(luna);
        T.eq("single", c.voiceMode, "mode from Luna");
        T.eq("", c.systemVoice, "Luna 'Automatic' wins over the file");
        T.eq("Microsoft David Desktop", c.systemVoiceMale, "male from Luna");
        T.eq("Microsoft Hazel Desktop", c.systemVoiceFemale, "no Luna menu (null): file value stays");
        T.eq("Microsoft David Desktop", c.systemVoiceFor("male"), "by role");
    }

    // ---- samples on save ----

    static List<String> sample(Config old, Config now) {
        SpeechRequest r = VoiceSamples.forChange(old, now);
        return r == null ? Collections.<String>emptyList() : show(r.parts());
    }

    public static void samplesPlayForChangedVoices() {
        Config old = new Config(), now = new Config();
        T.eq(Collections.<String>emptyList(), sample(old, now), "nothing changed (e.g. a checkbox): no sample");
        now.systemVoiceFemale = "Microsoft Zira Desktop";
        T.eq(Collections.singletonList("female:This is the voice of female characters."), sample(old, now), "only the changed one");
        now = new Config();
        now.systemRate = 3;
        T.eq(3, sample(old, now).size(), "speed changed: all three");
        now = new Config();
        now.voicePreview = Config.PREVIEW_ALL;
        T.eq(3, sample(old, now).size(), "'all': every save");
        now.voiceMode = VoiceParts.MODE_SINGLE;
        T.eq(Collections.singletonList("narrator:This voice reads everything."), sample(old, now), "single reader: one sample");
        now = new Config();
        now.voicePreview = Config.PREVIEW_OFF;
        now.systemVoice = "X";
        T.eq(Collections.<String>emptyList(), sample(old, now), "off");
        now = new Config();
        now.backend = Config.BACKEND_OFF;
        now.systemVoice = "X";
        T.eq(Collections.<String>emptyList(), sample(old, now), "speech off");
    }

    // ---- voice lists ----

    public static void windowsVoiceListAndMenu() {
        List<SystemVoices.Voice> v = SystemVoices.parseWindows("Microsoft Ava Online|Female|en-US\r\n"
                + "Microsoft Zira Desktop|Female|en-US\r\nMicrosoft Haruka Desktop|Female|ja-JP\r\n"
                + "Microsoft David Desktop|Male|en-US\r\nOdd, Voice|Male|en-GB\r\n\r\njunk\r\n");
        T.eq(5, v.size(), "parsed");
        T.eq("male", v.get(3).gender, "gender");
        T.eq(Arrays.asList("Automatic", "Microsoft Zira Desktop", "Microsoft David Desktop"), SystemVoices.menuChoices(v),
                "English, no cloud voices, no commas");
        T.eq(Arrays.asList("Automatic", "Microsoft Haruka Desktop"),
                SystemVoices.menuChoices(SystemVoices.parseWindows("Microsoft Haruka Desktop|Female|ja-JP")), "no English: all");
        T.eq(Collections.singletonList("Automatic"), SystemVoices.menuChoices(Collections.<SystemVoices.Voice>emptyList()), "none");
    }

    public static void macAndEspeakVoiceLists() {
        List<SystemVoices.Voice> mac = SystemVoices.parseMac("Alex                en_US    # Most people recognize me by my voice.\n"
                + "Bad News            en_US    # The light you see at the end of the tunnel is the headlamp.\n"
                + "Amelie              fr_CA    # Bonjour, je m'appelle Amelie.\n");
        T.eq(3, mac.size(), "mac parsed");
        T.eq("Bad News", mac.get(1).name, "name with a space");
        T.eq(Arrays.asList("Automatic", "Alex", "Bad News"), SystemVoices.menuChoices(mac), "mac English");
        List<SystemVoices.Voice> es = SystemVoices.parseEspeak("Pty Language       Age/Gender VoiceName          File                 Other Languages\n"
                + " 2  en-gb           --/M      English_(Great_Britain) gmw/en               (en 2)\n"
                + " 5  en-us           --/F      English_(America)  gmw/en-US            (en 3)\n");
        T.eq(2, es.size(), "espeak parsed");
        T.eq("English_(America)", es.get(1).name, "voice name column");
        T.eq("female", es.get(1).gender, "gender");
    }

    /** On this machine (Windows): the real listing works and is quick enough for startup. */
    public static void realVoiceListing() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        long t0 = System.currentTimeMillis();
        List<SystemVoices.Voice> v = SystemVoices.list();
        long ms = System.currentTimeMillis() - t0;
        T.yes(!v.isEmpty(), "found voices");
        T.yes(ms < 5000, "took " + ms + " ms");
        T.yes(SystemVoices.menuChoices(v).size() > 1, "menu has real voices: " + SystemVoices.menuChoices(v));
    }
}
