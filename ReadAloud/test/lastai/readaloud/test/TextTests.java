package lastai.readaloud.test;

import lastai.readaloud.Config;
import lastai.readaloud.text.TextCleaner;

import java.util.Arrays;
import java.util.Collections;
import java.util.regex.Pattern;

public final class TextTests {
    public static void cleanCollapsesWhitespaceAndControlChars() {
        T.eq("Hello there, captain.", TextCleaner.clean("  Hello\n\tthere,\u0007   captain.  "), "clean");
        T.eq("", TextCleaner.clean(null), "null");
        T.eq("", TextCleaner.clean("   "), "blank");
    }

    public static void cleanDropsPunctuationOnlyParagraphs() {
        T.eq("", TextCleaner.clean("..."), "ellipsis");
        T.eq("", TextCleaner.clean("- - -"), "divider");
        T.eq("Cost: 500", TextCleaner.clean("• Cost: 500"), "bullet");
    }

    public static void joinAddsPausesBetweenParagraphs() {
        String s = TextCleaner.join(Arrays.asList("The station looms", "", "\"Welcome,\" she says."), null);
        T.eq("The station looms.\n\n\"Welcome,\" she says.", s, "joined");
    }

    public static void joinAppliesReplacementsInOrder() {
        Config.Replacement r1 = new Config.Replacement(Pattern.compile("\\bLuddic\\b"), "Luh-dick");
        Config.Replacement r2 = new Config.Replacement(Pattern.compile("Luh"), "Loo");
        T.eq("The Loo-dick Church.", TextCleaner.join(Collections.singletonList("The Luddic Church."), Arrays.asList(r1, r2)), "replaced");
    }

    public static void badReplacementKeepsText() {
        Config.Replacement bad = new Config.Replacement(Pattern.compile("a"), "$9");
        T.eq("a.", TextCleaner.join(Collections.singletonList("a"), Collections.singletonList(bad)), "unchanged");
    }

    public static void choicesAreSpokenWithoutNumbersAndKeyHints() {
        T.eq("Trade goods or hire crew, buy or sell ships, or refit",
                TextCleaner.option("3. Trade goods or hire crew [I], buy or sell ships [F], or refit [R]"), "option");
        T.eq("Leave", TextCleaner.option("6. Leave [Esc]"), "leave");
        T.eq("Pay [500 credits] now", TextCleaner.option("Pay [500 credits] now"), "bracketed words stay");
        // Separate sentences, so voices pause between choices (one long "a; b; c" ran together).
        T.eq(Arrays.asList("Your choices:", "Open the comm directory.", "Leave."),
                TextCleaner.choices(Arrays.asList("1. Open the comm directory", "...", "2. Leave [Esc]")), "choices");
        T.eq(Collections.<String>emptyList(), TextCleaner.choices(Arrays.asList("...", "")), "nothing to say");
    }

    public static void keyNormalizesOptions() {
        T.eq("ask about work", TextCleaner.key("  Ask   about\nwork "), "spaces + case");
        T.eq("leave", TextCleaner.key("1. Leave"), "number prefix");
        T.eq("2001 problems", TextCleaner.key("2001 problems"), "a number that isn't a prefix");
    }
}
