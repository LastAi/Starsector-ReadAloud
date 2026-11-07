package lastai.readaloud.speech;

import lastai.readaloud.ReadAloudLog;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lists the operating system's installed voices, for the voice pickers in the settings menu. */
public final class SystemVoices {
    /** The menu entry that means "let the mod pick". */
    public static final String AUTOMATIC = "Automatic";
    /** Most voices shown in a menu; a radio list with more is unusable. */
    static final int MAX_MENU = 20;

    /** One installed voice. gender: "male", "female" or ""; lang: e.g. "en-US" or "". */
    public static final class Voice {
        public final String name;
        public final String gender;
        public final String lang;

        public Voice(String name, String gender, String lang) {
            this.name = name;
            this.gender = gender;
            this.lang = lang;
        }
    }

    private SystemVoices() {}

    private static volatile List<Voice> cached = Collections.emptyList();
    private static volatile String defaultName = "";
    /** Windows: the listing's first line names the default voice. */
    static final String DEFAULT_LINE = "#default|";

    /** The voices found by the last {@link #list()} (empty before it ran). For voice pools. */
    public static List<Voice> cached() {
        return cached;
    }

    /** Windows: the default voice's name from the last listing, or "". */
    public static String defaultName() {
        return defaultName;
    }

    /** Runs the OS's voice listing (a second or so on Windows) and remembers it. Empty if it fails. */
    public static List<Voice> list() {
        List<Voice> v = listNow();
        cached = Collections.unmodifiableList(new ArrayList<Voice>(v));
        return v;
    }

    private static List<Voice> listNow() {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                String script = "Add-Type -AssemblyName System.Speech;"
                        + "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer;"
                        + "[Console]::Out.WriteLine('" + DEFAULT_LINE + "'+$s.Voice.Name);"
                        + "$s.GetInstalledVoices()|?{$_.Enabled}|%{$i=$_.VoiceInfo;"
                        + "[Console]::Out.WriteLine($i.Name+'|'+$i.Gender+'|'+$i.Culture.Name)}";
                String out = run(Arrays.asList("powershell.exe", "-NoProfile", "-NonInteractive",
                        "-ExecutionPolicy", "Bypass", "-Command", script));
                defaultName = parseWindowsDefault(out);
                return parseWindows(out);
            }
            if (os.contains("mac")) return parseMac(run(Arrays.asList("say", "-v", "?")));
            String out = run(Arrays.asList("espeak-ng", "--voices=en"));
            if (out == null) out = run(Arrays.asList("espeak", "--voices=en"));
            List<Voice> v = new ArrayList<Voice>(parseEspeak(out));
            if (out != null) { // espeak's built-in variants of the English voice
                v.add(0, new Voice("en+f3", "female", "en"));
                v.add(0, new Voice("en+m3", "male", "en"));
            }
            return v;
        } catch (Throwable t) {
            ReadAloudLog.warn("listing the system voices failed (" + t + "); the voice menus only offer \"" + AUTOMATIC + "\"");
            return Collections.emptyList();
        }
    }

    /** Output of a short command, or null if it can't run or takes too long. */
    private static String run(List<String> cmd) throws Exception {
        final Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
        } catch (java.io.IOException e) {
            return null; // not installed
        }
        final StringBuilder out = new StringBuilder();
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[4096];
                InputStream in = p.getInputStream();
                try {
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        synchronized (out) {
                            if (out.length() < 200000) out.append(new String(buf, 0, n, Charset.defaultCharset()));
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }, "ReadAloud-voice-list");
        reader.setDaemon(true);
        reader.start();
        if (!p.waitFor(10, TimeUnit.SECONDS)) {
            p.destroy();
            ReadAloudLog.warn("listing the system voices took too long; skipped");
            return null;
        }
        reader.join(2000);
        synchronized (out) {
            return out.toString();
        }
    }

    /** Lines of "name|Male|en-US" (from the listing script). */
    public static List<Voice> parseWindows(String out) {
        List<Voice> v = new ArrayList<Voice>();
        if (out == null) return v;
        for (String line : out.split("\r?\n")) {
            if (line.startsWith(DEFAULT_LINE)) continue;
            String[] f = line.trim().split("\\|");
            if (f.length < 3 || f[0].trim().isEmpty()) continue;
            v.add(new Voice(f[0].trim(), gender(f[1]), f[2].trim()));
        }
        return v;
    }

    /** The default voice's name from the Windows listing, or "". */
    public static String parseWindowsDefault(String out) {
        if (out == null) return "";
        for (String line : out.split("\r?\n")) {
            if (line.startsWith(DEFAULT_LINE)) return line.substring(DEFAULT_LINE.length()).trim();
        }
        return "";
    }

    private static final Pattern MAC_LINE = Pattern.compile("^(.+?)\\s+([a-z]{2,3}[_-][A-Za-z0-9]+)\\s+#");

    /** {@code say -v ?}: "Alex                en_US    # Most people recognize me by my voice." */
    public static List<Voice> parseMac(String out) {
        List<Voice> v = new ArrayList<Voice>();
        if (out == null) return v;
        for (String line : out.split("\r?\n")) {
            Matcher m = MAC_LINE.matcher(line);
            if (m.find()) v.add(new Voice(m.group(1).trim(), "", m.group(2).replace('_', '-')));
        }
        return v;
    }

    /** {@code espeak-ng --voices=en}: "Pty Language Age/Gender VoiceName File Other Languages" columns. */
    public static List<Voice> parseEspeak(String out) {
        List<Voice> v = new ArrayList<Voice>();
        if (out == null) return v;
        for (String line : out.split("\r?\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 5 || !f[0].matches("\\d+")) continue; // header and junk
            String g = f[2].endsWith("F") ? "female" : f[2].endsWith("M") ? "male" : "";
            v.add(new Voice(f[3], g, f[1]));
        }
        return v;
    }

    private static String gender(String s) {
        String g = s.trim().toLowerCase();
        return "male".equals(g) || "female".equals(g) ? g : "";
    }

    /**
     * Names for a voice menu: "Automatic" first, then English voices (all voices if there are
     * none). Windows cloud voices ("... Online") are left out: they wait ~5 s before every part.
     * Names with commas can't be menu entries (LunaLib separates options with commas).
     */
    public static List<String> menuChoices(List<Voice> voices) {
        List<Voice> usable = new ArrayList<Voice>();
        for (Voice v : voices) {
            if (v.name.endsWith(" Online") || v.name.contains(",")) continue;
            usable.add(v);
        }
        List<Voice> english = new ArrayList<Voice>();
        for (Voice v : usable) if (v.lang.toLowerCase().startsWith("en")) english.add(v);
        Set<String> out = new LinkedHashSet<String>();
        out.add(AUTOMATIC);
        for (Voice v : english.isEmpty() ? usable : english) {
            if (out.size() > MAX_MENU) break;
            out.add(v.name);
        }
        return new ArrayList<String>(out);
    }
}
