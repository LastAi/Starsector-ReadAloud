package lastai.readaloud;

import org.apache.log4j.Logger;

import java.util.HashSet;
import java.util.Set;

/** One logger for the mod. Repeated problems are logged once, so a per-frame failure can't flood the log. */
public final class ReadAloudLog {
    private static final Logger LOG = Logger.getLogger("lastai.readaloud");
    private static final Set<String> ONCE = new HashSet<String>();

    private ReadAloudLog() {}

    public static void info(String msg) { LOG.info("[ReadAloud] " + msg); }

    public static void warn(String msg) { LOG.warn("[ReadAloud] " + msg); }

    public static void error(String msg, Throwable t) { LOG.error("[ReadAloud] " + msg, t); }

    /** Logs a warning only the first time {@code key} is seen. */
    public static synchronized void warnOnce(String key, String msg) {
        if (ONCE.add(key)) warn(msg);
    }

    /** Logs an info line only the first time {@code key} is seen. */
    public static synchronized void infoOnce(String key, String msg) {
        if (ONCE.add(key)) info(msg);
    }

    /** Logs an error only the first time {@code key} is seen. */
    public static synchronized void errorOnce(String key, String msg, Throwable t) {
        if (ONCE.add(key)) error(msg, t);
    }
}
