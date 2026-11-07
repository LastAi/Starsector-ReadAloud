package lastai.readaloud.text;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the "latest portion" of a dialog's text.
 *
 * When the player picks an option, the game prints that option's text into the text
 * panel as its own paragraph (the "echo"), then the response follows. So the latest
 * portion is everything after the last echo. An echo is recognised when a paragraph
 * first appears and its text equals an option that was on screen in that frame or
 * the frame before.
 *
 * Fed once per frame with the panel's labels (any objects; compared by identity) and
 * their texts. Pure: no game classes, so it is unit tested.
 */
public final class SegmentTracker {
    /** Label identity -> true if it is an option echo. Decided once, when first seen. */
    private final Map<Object, Boolean> seen = new IdentityHashMap<Object, Boolean>();
    private Set<String> previousOptions = Collections.emptySet();

    private final List<String> segment = new ArrayList<String>();
    private Object segmentLastLabel;
    private Object segmentMarker;
    /** Last label of the whole panel at the latest update. */
    private Object lastLabel;
    /** Last label when markSettled() was called; consumed by the next update. */
    private Object settledMark;
    /** The portion starts after this label (a pause boundary), unless a later echo exists. */
    private Object pauseMark;
    private int segmentId;
    private String segmentText = "";
    private int revision;

    public void update(List<?> labels, List<String> texts, Collection<String> options) {
        Set<String> optionKeys = new HashSet<String>();
        if (options != null) for (String o : options) {
            String k = TextCleaner.key(o);
            if (!k.isEmpty()) optionKeys.add(k);
        }
        Set<String> echoKeys = new HashSet<String>(optionKeys);
        echoKeys.addAll(previousOptions);

        // Forget labels that are gone (panel cleared), so the map can't grow forever.
        if (seen.size() > labels.size()) {
            Map<Object, Boolean> keep = new IdentityHashMap<Object, Boolean>();
            for (Object l : labels) {
                Boolean b = seen.get(l);
                if (b != null) keep.put(l, b);
            }
            seen.clear();
            seen.putAll(keep);
        }

        int lastEcho = -1;
        int pauseAt = -1;
        int settledAt = -1;
        for (int i = 0; i < labels.size(); i++) {
            Object l = labels.get(i);
            Boolean echo = seen.get(l);
            if (echo == null) {
                String k = TextCleaner.key(texts.get(i));
                echo = !k.isEmpty() && echoKeys.contains(k);
                seen.put(l, echo);
            }
            if (echo) lastEcho = i;
            if (l == pauseMark) pauseAt = i;
            if (l == settledMark) settledAt = i;
        }
        if (pauseMark != null && pauseAt < 0) pauseMark = null; // panel cleared
        // Text that arrives after the panel sat unchanged (no option was picked, e.g. a contact
        // chosen in the comm directory) starts a new portion, as an echo would.
        if (settledAt >= 0 && settledAt > pauseAt && hasWordsAfter(texts, settledAt)) {
            pauseMark = labels.get(settledAt);
            pauseAt = settledAt;
        }
        settledMark = null;
        int start = Math.max(lastEcho, pauseAt);

        segment.clear();
        segmentLastLabel = null;
        for (int i = start + 1; i < labels.size(); i++) {
            String t = texts.get(i);
            if (TextCleaner.clean(t).isEmpty()) continue;
            segment.add(t);
            segmentLastLabel = labels.get(i);
        }

        Object marker = start >= 0 ? labels.get(start) : (labels.isEmpty() ? null : labels.get(0));
        lastLabel = labels.isEmpty() ? null : labels.get(labels.size() - 1);
        if (marker != segmentMarker) {
            segmentMarker = marker;
            segmentId++;
        }
        String joined = String.valueOf(segment);
        if (!joined.equals(segmentText)) {
            segmentText = joined;
            revision++;
        }
        previousOptions = optionKeys;
    }

    /**
     * Tells the tracker the text has stopped changing (the caller decides how long counts).
     * Paragraphs added after this, if no option is picked in between, form a new portion.
     */
    public void markSettled() {
        settledMark = lastLabel;
    }

    private static boolean hasWordsAfter(List<String> texts, int index) {
        for (int i = index + 1; i < texts.size(); i++) if (!TextCleaner.clean(texts.get(i)).isEmpty()) return true;
        return false;
    }

    /** Paragraph texts of the latest portion (raw, not cleaned), in reading order. */
    public List<String> segment() {
        return Collections.unmodifiableList(segment);
    }

    /** The last label of the latest portion, or null if it is empty. */
    public Object segmentLastLabel() {
        return segmentLastLabel;
    }

    /** Changes whenever a new portion starts (an option was picked, or the panel was cleared). */
    public int segmentId() {
        return segmentId;
    }

    /** Changes whenever the latest portion's text changes (including when a new one starts). */
    public int revision() {
        return revision;
    }

    public void reset() {
        seen.clear();
        previousOptions = Collections.emptySet();
        segment.clear();
        segmentLastLabel = null;
        segmentMarker = null;
        lastLabel = null;
        settledMark = null;
        pauseMark = null;
        segmentText = "";
        segmentId++;
        revision++;
    }
}
