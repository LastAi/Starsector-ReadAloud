package lastai.readaloud.test;

import lastai.readaloud.text.SegmentTracker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Simulates a text panel frame by frame, the way the game fills it. */
public final class SegmentTests {
    /** A label: identity matters, text can change (replaceLastParagraph). */
    static final class L {
        String text;

        L(String text) {
            this.text = text;
        }
    }

    static final class Panel {
        final List<L> labels = new ArrayList<L>();
        final SegmentTracker tracker = new SegmentTracker();

        L add(String t) {
            L l = new L(t);
            labels.add(l);
            return l;
        }

        void frame(String... options) {
            List<String> texts = new ArrayList<String>();
            for (L l : labels) texts.add(l.text);
            tracker.update(labels, texts, Arrays.asList(options));
        }
    }

    public static void openingTextIsTheFirstSegment() {
        Panel p = new Panel();
        p.add("You approach the station.");
        p.add("The comms officer greets you.");
        p.frame("Trade", "Leave");
        T.eq(Arrays.asList("You approach the station.", "The comms officer greets you."), p.tracker.segment(), "segment");
    }

    /**
     * In-game report: station -> "Open the comm directory" -> pick a contact. The contact's
     * text arrives without an option echo, so it was merged into the comm portion, and
     * auto-read (which fires on a new portion) stayed silent.
     */
    public static void textAfterAPauseWithoutAnEchoIsANewSegment() {
        Panel p = new Panel();
        p.add("Your fleet approaches Jangala Station.");
        p.frame("Open the comm directory", "Leave");
        p.add("Open the comm directory");
        p.add("You connect to the local comm directory.");
        p.frame("Back");
        int commId = p.tracker.segmentId();
        p.tracker.markSettled(); // the player browses the directory for a while
        p.frame("Back");
        p.tracker.markSettled();
        T.eq(Collections.singletonList("You connect to the local comm directory."), p.tracker.segment(), "settling keeps the portion");
        T.eq(commId, p.tracker.segmentId(), "settling alone is not a new portion");

        p.add("Administrator Jorge Kim appears on screen.");
        L last = p.add("\"What can I do for you, captain?\"");
        p.frame("Ask about work", "Cut the comm link");
        T.eq(Arrays.asList("Administrator Jorge Kim appears on screen.", "\"What can I do for you, captain?\""),
                p.tracker.segment(), "only the contact's text");
        T.yes(p.tracker.segmentId() != commId, "new portion, so auto-read fires");
        T.yes(p.tracker.segmentLastLabel() == last, "button goes under it");
        int contactId = p.tracker.segmentId();

        // Settling again with nothing new keeps that portion (not empty, not merged back).
        p.tracker.markSettled();
        p.frame("Ask about work", "Cut the comm link");
        T.eq(2, p.tracker.segment().size(), "still the contact's text");
        T.eq(contactId, p.tracker.segmentId(), "same id");

        // An option picked afterwards still splits at its echo.
        p.add("Ask about work");
        p.add("\"Nothing right now.\"");
        p.frame("Cut the comm link");
        T.eq(Collections.singletonList("\"Nothing right now.\""), p.tracker.segment(), "echo still wins");
    }

    /** Text added in quick steps (no pause) stays one portion. */
    public static void textAddedWithoutAPauseStaysOnePortion() {
        Panel p = new Panel();
        p.add("You approach the station.");
        p.frame("Leave");
        int id = p.tracker.segmentId();
        p.add("A patrol hails you.");
        p.frame("Leave");
        T.eq(2, p.tracker.segment().size(), "merged");
        T.eq(id, p.tracker.segmentId(), "same portion");
    }

    public static void pickingAnOptionStartsANewSegment() {
        Panel p = new Panel();
        p.add("You approach the station.");
        p.frame("Trade", "Leave");
        int id = p.tracker.segmentId();
        // Same frame: options replaced, echo + response printed.
        p.add("Trade");
        L resp = p.add("The market is busy.");
        p.frame("Back");
        T.eq(Collections.singletonList("The market is busy."), p.tracker.segment(), "only the response");
        T.yes(p.tracker.segmentId() != id, "new segment id");
        T.yes(p.tracker.segmentLastLabel() == resp, "last label");

        // A second exchange.
        p.add("Back");
        p.add("You step away.");
        p.add("The crowd thins.");
        p.frame("Trade", "Leave");
        T.eq(Arrays.asList("You step away.", "The crowd thins."), p.tracker.segment(), "second response");
    }

    public static void echoIsMatchedAgainstLastFramesOptions() {
        // Options already cleared in the frame the echo appears (the plugin cleared first).
        Panel p = new Panel();
        p.add("Hello.");
        p.frame("Ask about work");
        p.add("Ask about work");
        p.add("\"I might have something,\" she says.");
        p.frame();
        T.eq(Collections.singletonList("\"I might have something,\" she says."), p.tracker.segment(), "segment");
    }

    public static void textEqualToAnOldOptionIsNotAnEcho() {
        Panel p = new Panel();
        p.add("Hello.");
        p.frame("Leave");
        p.frame("Continue"); // two frames later "Leave" is no longer on screen
        p.add("Leave");
        p.frame("Continue");
        T.eq(Arrays.asList("Hello.", "Leave"), p.tracker.segment(), "kept as text");
    }

    public static void editsToTheLastParagraphUpdateTheSegmentNotItsId() {
        Panel p = new Panel();
        L l = p.add("Loading");
        p.frame("Ok");
        int id = p.tracker.segmentId();
        int rev = p.tracker.revision();
        l.text = "Loading complete.";
        p.frame("Ok");
        T.eq(Collections.singletonList("Loading complete."), p.tracker.segment(), "updated text");
        T.eq(id, p.tracker.segmentId(), "same segment");
        T.yes(p.tracker.revision() != rev, "revision changed");
        int rev2 = p.tracker.revision();
        p.frame("Ok");
        T.eq(rev2, p.tracker.revision(), "no change, same revision");
    }

    public static void clearingThePanelStartsOver() {
        Panel p = new Panel();
        p.add("Old text.");
        p.frame("Next");
        p.add("Next");
        p.add("Middle.");
        p.frame("Next");
        int id = p.tracker.segmentId();
        p.labels.clear();
        p.add("A fresh scene.");
        p.frame("Next");
        T.eq(Collections.singletonList("A fresh scene."), p.tracker.segment(), "after clear");
        T.yes(p.tracker.segmentId() != id, "new id");
    }

    public static void numberedOptionButtonsStillMatch() {
        Panel p = new Panel();
        p.add("Intro.");
        p.frame("1. Leave");
        p.add("Leave");
        p.add("You leave.");
        p.frame();
        T.eq(Collections.singletonList("You leave."), p.tracker.segment(), "segment");
    }

    public static void emptyParagraphsAreSkipped() {
        Panel p = new Panel();
        p.add("");
        p.add("...");
        p.add("Real text.");
        p.frame();
        T.eq(Collections.singletonList("Real text."), p.tracker.segment(), "segment");
    }

    public static void emptyPanelHasEmptySegment() {
        Panel p = new Panel();
        p.frame("Leave");
        T.yes(p.tracker.segment().isEmpty(), "empty");
        T.eq(null, p.tracker.segmentLastLabel(), "no last label");
    }

    public static void echoOnlyPanelHasEmptySegment() {
        Panel p = new Panel();
        p.add("Hi.");
        p.frame("Go");
        p.add("Go");
        p.frame();
        T.yes(p.tracker.segment().isEmpty(), "nothing after the echo yet");
    }
}
