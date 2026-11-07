package lastai.readaloud.test;

import java.util.Objects;

/** Tiny assertions. */
final class T {
    static int checks;

    private T() {}

    static void eq(Object expected, Object actual, String what) {
        checks++;
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    static void yes(boolean cond, String what) {
        checks++;
        if (!cond) throw new AssertionError(what);
    }

    static void no(boolean cond, String what) {
        yes(!cond, what);
    }

    /** Waits up to {@code ms} for {@code cond}. */
    static void eventually(long ms, String what, java.util.concurrent.Callable<Boolean> cond) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (Boolean.TRUE.equals(cond.call())) {
                checks++;
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out: " + what);
    }
}
