package lastai.readaloud.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Decides who reads what: the narrator, or the character in the dialog.
 * Pure (no game classes), so it is unit tested.
 */
public final class VoiceParts {
    public static final String NARRATOR = "narrator";
    public static final String MALE = "male";
    public static final String FEMALE = "female";

    /** Quoted speech in the character's voice, the rest by the narrator. */
    public static final String MODE_QUOTES = "quotes";
    /** All text in the character's voice when someone is talking, else the narrator. */
    public static final String MODE_CHARACTER = "character";
    /** One reader (the narrator) for everything. */
    public static final String MODE_SINGLE = "single";

    /** A run of text for one voice. Paragraphs inside it are separated by a blank line. */
    public static final class Part {
        public final String role;
        public final String text;

        public Part(String role, String text) {
            this.role = role;
            this.text = text;
        }

        @Override
        public String toString() {
            return role + ":" + text;
        }
    }

    private VoiceParts() {}

    /** The character role for a speaker gender: female for "female", otherwise male. */
    public static String characterRole(String gender) {
        return FEMALE.equals(gender) ? FEMALE : MALE;
    }

    /**
     * @param paragraphs cleaned paragraphs (see {@link TextCleaner#paragraphs})
     * @param gender     the dialog person's gender: "male", "female" or ""
     * @param hasSpeaker true if a person is on the other end of the dialog
     */
    public static List<Part> split(List<String> paragraphs, String mode, String gender, boolean hasSpeaker) {
        if (paragraphs == null || paragraphs.isEmpty()) return Collections.emptyList();
        String character = characterRole(gender);
        Builder b = new Builder();
        for (String p : paragraphs) {
            if (MODE_QUOTES.equals(mode)) {
                quotes(p, character, b);
            } else {
                b.add(MODE_CHARACTER.equals(mode) && hasSpeaker ? character : NARRATOR, p);
            }
            b.endParagraph();
        }
        return b.parts;
    }

    /** Splits one paragraph at its quotation marks. An unclosed quote runs to the paragraph's end. */
    private static void quotes(String p, String character, Builder b) {
        StringBuilder run = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            boolean open = c == '“' || (c == '"' && !inQuote);
            boolean close = c == '”' || (c == '"' && inQuote);
            if ((open && !inQuote) || (close && inQuote)) {
                b.add(inQuote ? character : NARRATOR, run.toString());
                run.setLength(0);
                inQuote = !inQuote;
            } else if (open || close) {
                // a stray curly quote that doesn't change state: drop it
            } else {
                run.append(c);
            }
        }
        b.add(inQuote ? character : NARRATOR, run.toString());
    }

    /** Collects parts, joining neighbours with the same voice. */
    private static final class Builder {
        final List<Part> parts = new ArrayList<Part>();
        private boolean newParagraph;

        void add(String role, String raw) {
            String t = tidy(raw, NARRATOR.equals(role));
            if (t.isEmpty()) return;
            int n = parts.size();
            if (n > 0 && parts.get(n - 1).role.equals(role)) {
                parts.set(n - 1, new Part(role, parts.get(n - 1).text + (newParagraph ? "\n\n" : " ") + t));
            } else {
                parts.add(new Part(role, t));
            }
            newParagraph = false;
        }

        void endParagraph() {
            newParagraph = true;
        }
    }

    /**
     * Trims a piece of text. Narration between quotes often starts with the punctuation that
     * closed the quote (", she says"); that is dropped. Pieces with no words are dropped.
     */
    static String tidy(String s, boolean narration) {
        String t = s.trim();
        if (narration) t = t.replaceFirst("^[\\s,;:—–-]+", "").trim();
        for (int i = 0; i < t.length(); i++) if (Character.isLetterOrDigit(t.charAt(i))) return t;
        return "";
    }
}
