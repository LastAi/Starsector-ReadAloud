package lastai.readaloud.text;

import lastai.readaloud.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns dialog paragraphs into speakable text. Pure: no game classes, so it is unit tested. */
public final class TextCleaner {
    private TextCleaner() {}

    /** Cleans one paragraph. Returns "" if nothing speakable is left. */
    public static String clean(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') sb.append(' ');
            else if (c < 32 || c == 0x7f) continue;          // control characters
            else if (c == '•' || c == '●') sb.append(' '); // bullets
            else sb.append(c);
        }
        String s = credits(sb.toString()).replaceAll(" {2,}", " ").trim();
        // A paragraph made only of punctuation or symbols (dividers, "...") isn't worth speaking.
        for (int i = 0; i < s.length(); i++) if (Character.isLetterOrDigit(s.charAt(i))) return s;
        return "";
    }

    /** Starsector's credit symbol (Strings.C in the API). Voices read it as "cents". */
    private static final char CREDIT = '¢';
    /** "1,000¢" / "1,000 ¢" (the game's Misc.getDGSCredits form) and "¢1,000" (some mods). */
    private static final Pattern AMOUNT = Pattern.compile(
            "(\\d(?:[\\d,.]*\\d)?) ?¢|¢ ?(\\d(?:[\\d,.]*\\d)?)");
    /** A symbol right after a word or number that isn't a plain amount, e.g. "10k¢". */
    private static final Pattern GLUED = Pattern.compile("(?<=[\\p{L}\\p{N}])¢");

    /** Speaks the credit symbol as "credits" ("1,000¢" -> "1,000 credits", "1¢" -> "1 credit"). */
    static String credits(String s) {
        if (s.indexOf(CREDIT) < 0) return s;
        Matcher m = AMOUNT.matcher(s);
        StringBuffer sb = new StringBuffer(s.length() + 16);
        while (m.find()) {
            String n = m.group(1) != null ? m.group(1) : m.group(2);
            m.appendReplacement(sb, Matcher.quoteReplacement(n + ("1".equals(n) ? " credit" : " credits")));
        }
        m.appendTail(sb);
        return GLUED.matcher(sb.toString()).replaceAll(" credits").replace(String.valueOf(CREDIT), "credits");
    }

    /** An option label for speaking: without its "1. " number and "[Esc]"-style key hints. */
    public static String option(String raw) {
        String s = clean(raw).replaceFirst("^\\d{1,2}[.)]\\s+", "").replaceAll("\\s*\\[[^\\]\\s]{1,8}\\]", "");
        return clean(s);
    }

    /**
     * The dialog's options as separate sentences ("Your choices:", "Open the comm directory.",
     * "Leave."), so voices pause between them. Empty if no option has any words.
     */
    public static List<String> choices(List<String> options) {
        List<String> out = new ArrayList<String>();
        for (String o : options) {
            String spoken = option(o);
            if (!spoken.isEmpty()) out.add(spoken);
        }
        if (out.isEmpty()) return out;
        out.add(0, "Your choices:");
        return paragraphs(out);
    }

    /**
     * Cleans paragraphs and drops empty ones. A paragraph without closing punctuation
     * gets a period so voices pause after it.
     */
    public static List<String> paragraphs(List<String> raw) {
        List<String> out = new ArrayList<String>();
        for (String p : raw) {
            String c = clean(p);
            if (c.isEmpty()) continue;
            char last = c.charAt(c.length() - 1);
            out.add(Character.isLetterOrDigit(last) ? c + "." : c);
        }
        return out;
    }

    /**
     * Joins cleaned paragraphs into one text, separated by blank lines (see {@link #paragraphs}),
     * and applies the replacements.
     */
    public static String join(List<String> paragraphs, List<Config.Replacement> replacements) {
        StringBuilder sb = new StringBuilder();
        for (String c : paragraphs(paragraphs)) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(c);
        }
        return replace(sb.toString(), replacements);
    }

    /** Applies the replacements in order; a failing one leaves the text as it was. */
    public static String replace(String text, List<Config.Replacement> replacements) {
        String out = text;
        if (replacements != null) {
            for (Config.Replacement r : replacements) {
                try {
                    out = r.pattern.matcher(out).replaceAll(r.with);
                } catch (RuntimeException e) {
                    // e.g. a bad group reference in the replacement; keep the text unchanged
                }
            }
        }
        return out;
    }

    /** Normalizes an option or paragraph for comparison: lower case, single spaces, no "1. " prefix. */
    public static String key(String s) {
        if (s == null) return "";
        String k = s.toLowerCase().replaceAll("\\s+", " ").trim();
        k = k.replaceFirst("^\\d{1,2}[.)]\\s+", "");
        return k;
    }
}
