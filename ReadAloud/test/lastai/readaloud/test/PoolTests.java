package lastai.readaloud.test;

import lastai.readaloud.Config;
import lastai.readaloud.speech.Speech;
import lastai.readaloud.speech.SpeechRequest;
import lastai.readaloud.speech.SystemBackend;
import lastai.readaloud.speech.SystemVoices;
import lastai.readaloud.speech.VoicePool;
import lastai.readaloud.text.VoiceParts;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Voice pools: each character keeps one voice, from a hash of who they are; nothing is saved. */
public final class PoolTests {
    static final List<String> POOL = Arrays.asList("Bruno", "Hugo", "Leo", "Jasper");

    /** The companion app (server.py fnv64, PoolTests.VECTORS) must hash exactly alike. */
    public static void hashMatchesTheCompanionApp() {
        T.eq(Long.parseUnsignedLong("17280346270528514342"), VoicePool.hash(""), "empty");
        T.eq(Long.parseUnsignedLong("9413272369427828315"), VoicePool.hash("a"), "a");
        T.eq(Long.parseUnsignedLong("2101149729552114462"), VoicePool.hash("person_1\nBruno"), "key + voice");
        T.eq(Long.parseUnsignedLong("17774750531867304293"), VoicePool.hash("Luddé"), "UTF-8");
        T.eq("Leo", VoicePool.pick("person_1", POOL), "same pick as the app");
        T.eq("Hugo", VoicePool.pick("person_2", POOL), "same pick as the app");
    }

    public static void picksAreStableSpreadAndOrderFree() {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        List<String> reversed = new ArrayList<String>(POOL);
        Collections.reverse(reversed);
        for (int i = 0; i < 2000; i++) {
            String v = VoicePool.pick("p" + i, POOL);
            T.eq(v, VoicePool.pick("p" + i, reversed), "order");
            counts.put(v, counts.containsKey(v) ? counts.get(v) + 1 : 1);
        }
        T.eq(4, counts.size(), "every voice used: " + counts);
        for (int n : counts.values()) T.yes(n > 400 && n < 600, "even spread: " + counts);
        T.eq(null, VoicePool.pick("", POOL), "no key");
        T.eq(null, VoicePool.pick("x", Collections.<String>emptyList()), "no pool");
    }

    public static void addingAVoiceOnlyMovesSomeCharacters() {
        List<String> bigger = new ArrayList<String>(POOL);
        bigger.add("Kai");
        int moved = 0;
        for (int i = 0; i < 1000; i++) {
            String before = VoicePool.pick("p" + i, POOL), after = VoicePool.pick("p" + i, bigger);
            if (!after.equals(before)) {
                T.eq("Kai", after, "moved only to the new voice");
                moved++;
            }
        }
        T.yes(moved > 120 && moved < 280, "about a fifth moved: " + moved);
    }

    public static void keyIsTheIdThenTheName() {
        SpeechRequest r = new SpeechRequest("x");
        T.eq("", VoicePool.key(r), "nobody");
        r.speakerName = "Ana";
        T.eq("Ana", VoicePool.key(r), "name");
        r.speakerId = " person_7 ";
        T.eq("person_7", VoicePool.key(r), "id wins");
    }

    static final List<SystemVoices.Voice> INSTALLED = SystemVoices.parseWindows("#default|Microsoft David Desktop\r\n"
            + "Microsoft David Desktop|Male|en-US\r\nMicrosoft Mark|Male|en-US\r\nMicrosoft George|Male|en-GB\r\n"
            + "Microsoft Ichiro|Male|ja-JP\r\nMicrosoft Guy Online|Male|en-US\r\n"
            + "Microsoft Zira Desktop|Female|en-US\r\nMicrosoft Hazel Desktop|Female|en-GB\r\n");

    public static void systemPoolsFromInstalledVoices() {
        T.eq("Microsoft David Desktop", SystemVoices.parseWindowsDefault("#default|Microsoft David Desktop\r\nx|Male|en"), "default line");
        T.eq(7, INSTALLED.size(), "default line is not a voice");
        T.eq(Arrays.asList("Microsoft Mark", "Microsoft George"),
                VoicePool.systemPool(INSTALLED, "male", null, "Microsoft David Desktop"),
                "English, offline, not the narrator's voice");
        T.eq(Arrays.asList("Microsoft Zira Desktop", "Microsoft Hazel Desktop"),
                VoicePool.systemPool(INSTALLED, "female", null, "Microsoft David Desktop"), "female");
        T.eq(Collections.singletonList("Microsoft Zira Desktop"),
                VoicePool.systemPool(SystemVoices.parseWindows("Microsoft Zira Desktop|Female|en-US"), "female", null,
                        "Microsoft Zira Desktop"), "the narrator's voice stays when it is the only one");
        T.eq(Arrays.asList("Microsoft Mark", "Microsoft Ichiro"),
                VoicePool.systemPool(INSTALLED, "male", Arrays.asList("microsoft mark", "Microsoft Ichiro", "Not Installed"), ""),
                "a listed pool: installed names only, any language");
    }

    public static void systemBackendUsesTheSpeakersPoolVoice() {
        SystemBackend b = new SystemBackend("Windows 11", 0, 100, "", "Microsoft David Desktop", "Microsoft Zira Desktop")
                .withPools(Arrays.asList("Microsoft Mark", "Microsoft George"), Collections.singletonList("Microsoft Hazel Desktop"));
        SpeechRequest r = new SpeechRequest(Arrays.asList(new VoiceParts.Part(VoiceParts.NARRATOR, "He waits."),
                new VoiceParts.Part(VoiceParts.MALE, "Well?")));
        r.speakerId = "person_1";
        String expect = VoicePool.pick("person_1", Arrays.asList("Microsoft Mark", "Microsoft George"));
        String script = b.commands(r).get(0).get(6);
        T.yes(script.contains("(Pick '" + expect + "' 'Male'"), "pool voice for the male part: " + expect);
        T.yes(b.commands(r).get(0).get(6).equals(script), "same character, same script");
        r.speakerId = "";
        T.yes(b.commands(r).get(0).get(6).contains("(Pick 'Microsoft David Desktop' 'Male'"), "unknown speaker: the male voice");
        SystemBackend linux = new SystemBackend("Linux", 0, 100, "", "", "").withPools(Collections.singletonList("en+m7"),
                Collections.<String>emptyList());
        r.speakerId = "person_2";
        T.yes(linux.commands(r).get(0).contains("en+m7"), "linux pool voice: " + linux.commands(r).get(0));
    }

    public static void poolSettings() throws Exception {
        Config c = Config.fromJson(new JSONObject("{systemVoicePools:true, systemPoolMale:['A',' A ','', 'B'], systemVoice:'Microsoft Zira Desktop'}"), null);
        T.yes(c.systemVoicePools, "on");
        T.eq(Arrays.asList("A", "B"), c.systemPoolMale, "names cleaned");
        T.eq(Collections.<String>emptyList(), c.systemPoolFemale, "female: all installed");
        T.no(new Config().systemVoicePools, "off by default");
        List<String>[] pools = Speech.systemPools(Config.fromJson(new JSONObject("{systemVoice:'Microsoft Mark'}"), null), INSTALLED);
        T.eq(Arrays.asList("Microsoft David Desktop", "Microsoft George"), pools[0], "the chosen narrator is left out");
    }
}
