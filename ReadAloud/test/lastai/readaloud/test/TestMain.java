package lastai.readaloud.test;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Runs every public static no-arg method of the test classes. Exit code 1 on any failure. */
public final class TestMain {
    public static void main(String[] args) throws Exception {
        BasicConfigurator.configure();
        Logger.getRootLogger().setLevel(System.getenv("READALOUD_TEST_LOG") != null ? Level.INFO : Level.OFF);

        Class<?>[] suites = {
                TextTests.class,
                SegmentTests.class,
                VoiceTests.class,
                PoolTests.class,
                ConfigTests.class,
                PlacementTests.class,
                UiTreeTests.class,
                BackendTests.class,
                SpeechTests.class,
                AiTests.class,
                SandboxTests.class,
                LiveCompanionTests.class,
        };
        int passed = 0, failed = 0;
        for (Class<?> suite : suites) {
            for (Method m : suite.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers()) || !Modifier.isPublic(m.getModifiers()) || m.getParameterTypes().length != 0) continue;
                String name = suite.getSimpleName() + "." + m.getName();
                try {
                    m.invoke(null);
                    passed++;
                    System.out.println("  ok   " + name);
                } catch (InvocationTargetException e) {
                    failed++;
                    System.out.println("  FAIL " + name + ": " + e.getCause());
                    if (!(e.getCause() instanceof AssertionError)) e.getCause().printStackTrace(System.out);
                }
            }
        }
        System.out.println(passed + " passed, " + failed + " failed, " + T.checks + " checks");
        if (failed > 0) System.exit(1);
    }
}
