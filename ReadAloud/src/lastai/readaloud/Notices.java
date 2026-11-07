package lastai.readaloud;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Short messages for the player (the AI voice app isn't running, ...). Any thread posts;
 * the game thread shows them (next to the speaker button and in the campaign message log).
 * The same message is shown at most once per {@link #REPEAT_MS}.
 */
public final class Notices {
    static final long REPEAT_MS = 10 * 60 * 1000L;
    private static final ConcurrentLinkedQueue<String> QUEUE = new ConcurrentLinkedQueue<String>();
    private static final Map<String, Long> SHOWN = new HashMap<String, Long>();

    private Notices() {}

    /** Queues {@code text} unless the same key was posted recently. Also logged. */
    public static void post(String key, String text) {
        if (text == null || text.isEmpty()) return;
        long now = System.currentTimeMillis();
        synchronized (SHOWN) {
            Long last = SHOWN.get(key);
            if (last != null && now - last < REPEAT_MS) return;
            SHOWN.put(key, now);
        }
        ReadAloudLog.info("notice: " + text);
        QUEUE.offer(text);
    }

    /** Forgets that {@code key} was shown, so the next post shows again (the problem went away). */
    public static void reset(String keyPrefix) {
        synchronized (SHOWN) {
            for (Iterator<String> it = SHOWN.keySet().iterator(); it.hasNext(); ) {
                if (it.next().startsWith(keyPrefix)) it.remove();
            }
        }
    }

    /** The next message to show, or null. */
    public static String poll() {
        return QUEUE.poll();
    }

    /** Tests. */
    public static void clear() {
        QUEUE.clear();
        synchronized (SHOWN) {
            SHOWN.clear();
        }
    }
}
