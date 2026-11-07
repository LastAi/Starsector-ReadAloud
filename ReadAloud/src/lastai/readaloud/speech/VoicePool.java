package lastai.readaloud.speech;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Voice pools: each character keeps one voice from a list, without anything being saved.
 * The voice is the pool entry with the highest hash of (character key, voice name)
 * ("rendezvous hashing"), so adding or removing a voice only moves the characters that
 * had that voice. The companion app (server.py pool_pick) uses the same hash.
 */
public final class VoicePool {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private VoicePool() {}

    /** FNV-1a 64 over UTF-8, then MurmurHash3's fmix64. Compare as unsigned. */
    public static long hash(String s) {
        long h = 0xcbf29ce484222325L;
        for (byte b : s.getBytes(UTF8)) {
            h ^= (b & 0xff);
            h *= 0x100000001b3L;
        }
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }

    /** The voice for this character, or null when the key or the pool is empty. */
    public static String pick(String key, List<String> pool) {
        if (key == null || key.trim().isEmpty() || pool == null || pool.isEmpty()) return null;
        String best = null;
        long bestScore = 0;
        for (String v : pool) {
            long score = hash(key.trim() + "\n" + v);
            if (best == null || Long.compareUnsigned(score, bestScore) > 0) {
                best = v;
                bestScore = score;
            }
        }
        return best;
    }

    /** Who a character is: the game's person id, else the name ("" = unknown: no pool voice). */
    public static String key(SpeechRequest r) {
        if (r.speakerId != null && !r.speakerId.trim().isEmpty()) return r.speakerId.trim();
        return r.speakerName == null ? "" : r.speakerName.trim();
    }

    /**
     * The system voices a pool of this gender is drawn from: the names in {@code only} that are
     * installed (all usable installed voices of that gender when {@code only} is empty), without
     * slow cloud voices, preferring English ones, and without the narrator's voice if others remain.
     */
    public static List<String> systemPool(List<SystemVoices.Voice> installed, String gender, List<String> only, String narrator) {
        List<SystemVoices.Voice> usable = new ArrayList<SystemVoices.Voice>();
        for (SystemVoices.Voice v : installed) {
            if (v.name.endsWith(" Online")) continue;
            if (only != null && !only.isEmpty()) {
                if (containsIgnoreCase(only, v.name)) usable.add(v);
            } else if (gender.equals(v.gender)) {
                usable.add(v);
            }
        }
        List<String> english = new ArrayList<String>(), all = new ArrayList<String>();
        for (SystemVoices.Voice v : usable) {
            all.add(v.name);
            if (v.lang.toLowerCase().startsWith("en")) english.add(v.name);
        }
        List<String> pool = (only == null || only.isEmpty()) && !english.isEmpty() ? english : all;
        if (narrator != null && !narrator.isEmpty() && pool.size() > 1) {
            List<String> without = new ArrayList<String>();
            for (String n : pool) if (!n.equalsIgnoreCase(narrator)) without.add(n);
            if (!without.isEmpty()) pool = without;
        }
        return pool;
    }

    private static boolean containsIgnoreCase(List<String> names, String n) {
        for (String s : names) if (s.trim().equalsIgnoreCase(n)) return true;
        return false;
    }
}
