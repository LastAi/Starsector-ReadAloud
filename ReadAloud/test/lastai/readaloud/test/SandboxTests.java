package lastai.readaloud.test;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The game loads mod classes through a class loader that refuses some JDK classes
 * (com.fs.starfarer.loading.scripts.B in 0.98a): anything in java.io except a short
 * list of stream classes, java.nio.file.File*, java.lang.reflect (but a few types),
 * javax.script and java.util.prefs. A mod referencing one fails when the class loads,
 * in game. This test reads every class we ship and applies the same rules, so that
 * mistake fails the build instead.
 */
public final class SandboxTests {
    static final Set<String> IO_ALLOWED = new HashSet<String>(Arrays.asList(
            "java.io.BufferedInputStream", "java.io.BufferedReader", "java.io.FilterInputStream", "java.io.InputStreamReader",
            "java.io.Reader", "java.io.Serializable", "java.io.InvalidClassException", "java.io.ObjectStreamException",
            "java.io.InputStream", "java.io.IOException", "java.io.PrintStream", "java.io.PrintWriter",
            "java.io.ByteArrayInputStream", "java.io.FilterOutputStream", "java.io.OutputStream", "java.io.Closeable",
            "java.io.Flushable", "java.io.StringReader", "java.io.FileReader"));
    static final Set<String> REFLECT_ALLOWED = new HashSet<String>(Arrays.asList(
            "java.lang.reflect.AnnotatedElement", "java.lang.reflect.InvocationTargetException",
            "java.lang.reflect.Type", "java.lang.reflect.GenericDeclaration"));

    static final Pattern CLASS_REF = Pattern.compile("(?:^|L|\\[)((?:java|javax|sun)/[A-Za-z0-9_/$]+)");

    /** Mirrors the game's rule for one class name (dotted form). */
    public static boolean blocked(String name) {
        if (name.startsWith("java.io") || name.startsWith("java.nio.file.File")) return !IO_ALLOWED.contains(name);
        if (name.startsWith("java.lang.reflect")) return !REFLECT_ALLOWED.contains(name);
        return name.startsWith("javax.script") || name.startsWith("java.util.prefs") || name.equals("sun.reflect.misc.MethodUtil");
    }

    public static void ruleMatchesTheGame() {
        T.yes(blocked("java.io.File"), "File");
        T.yes(blocked("java.io.ByteArrayOutputStream"), "ByteArrayOutputStream");
        T.yes(blocked("java.nio.file.Files"), "Files");
        T.yes(blocked("java.lang.reflect.Method"), "Method");
        T.yes(blocked("java.lang.reflect.Modifier"), "Modifier");
        T.no(blocked("java.io.InputStream"), "InputStream ok");
        T.no(blocked("java.net.HttpURLConnection"), "net ok");
        T.no(blocked("java.lang.ProcessBuilder"), "ProcessBuilder ok");
        T.no(blocked("java.lang.invoke.MethodHandles"), "invoke ok");
        T.no(blocked("java.nio.charset.Charset"), "charset ok");
    }

    public static void shippedClassesUseOnlyAllowedJdkClasses() throws Exception {
        Path root = Paths.get(System.getProperty("readaloud.classes"));
        List<Path> classes;
        try (Stream<Path> s = Files.walk(root)) {
            classes = s.filter(p -> p.toString().endsWith(".class")).collect(Collectors.toList());
        }
        T.yes(classes.size() > 10, "found the classes: " + classes.size());
        Set<String> bad = new TreeSet<String>();
        for (Path p : classes) {
            for (String utf : utf8Constants(Files.readAllBytes(p))) {
                Matcher m = CLASS_REF.matcher(utf);
                while (m.find()) {
                    String name = m.group(1).replace('/', '.');
                    if (blocked(name)) bad.add(root.relativize(p) + " -> " + name);
                }
            }
        }
        T.eq(new TreeSet<String>(), bad, "classes the game would refuse");
    }

    /** All CONSTANT_Utf8 strings of a class file (class names and descriptors live there). */
    static List<String> utf8Constants(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) throw new IOException("not a class file");
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        List<String> out = new ArrayList<String>();
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1: out.add(in.readUTF()); break;
                case 3: case 4: in.readInt(); break;
                case 5: case 6: in.readLong(); i++; break;
                case 7: case 8: case 16: case 19: case 20: in.readUnsignedShort(); break;
                case 9: case 10: case 11: case 12: case 17: case 18: in.readInt(); break;
                case 15: in.readUnsignedByte(); in.readUnsignedShort(); break;
                default: throw new IOException("unknown constant tag " + tag);
            }
        }
        return out;
    }
}
