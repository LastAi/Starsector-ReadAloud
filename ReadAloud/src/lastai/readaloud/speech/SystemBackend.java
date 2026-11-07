package lastai.readaloud.speech;

import lastai.readaloud.ReadAloudLog;
import lastai.readaloud.text.VoiceParts;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The operating system's own voice, no install needed:
 * Windows SAPI (through PowerShell), macOS {@code say}, Linux espeak-ng / espeak / spd-say.
 * Each request runs one short-lived process that speaks all its parts in turn, each part
 * in the voice for its role (narrator, male, female); stopping kills it.
 */
public final class SystemBackend implements SpeechBackend {
    /** Windows command lines are limited to 32k characters; base64 grows text by 4/3. */
    static final int MAX_CHARS = 12000;
    /** The Windows script prints this, then "role=voice name", for each voice it uses. */
    static final String VOICE_PREFIX = "readaloud-voice: ";
    /** The Windows script prints this when it has spoken everything (it then ends itself, exit code 1 or so). */
    static final String DONE = "readaloud-done";
    /** Processes we killed on purpose: their non-zero exit isn't a failure. */
    private static final Set<Process> STOPPED = new HashSet<Process>();

    private final String os;
    private final int rate;
    private final int volume;
    private final String narrator;
    private final String male;
    private final String female;
    private Process process;
    /** Linux: index into the candidate list of the first program that started. */
    private int linuxChoice = -1;
    /** The "role=voice" lines the last Windows request printed. For tests. */
    public volatile String lastVoiceReport = "";

    public SystemBackend(int rate, int volume, String narrator, String male, String female) {
        this(System.getProperty("os.name", ""), rate, volume, narrator, male, female);
    }

    /** For tests: pretend to run on {@code os}. */
    public SystemBackend(String os, int rate, int volume, String narrator, String male, String female) {
        this.os = os.toLowerCase();
        this.rate = rate;
        this.volume = volume;
        this.narrator = narrator == null ? "" : narrator.trim();
        this.male = male == null ? "" : male.trim();
        this.female = female == null ? "" : female.trim();
    }

    /** One voice for every role. */
    public SystemBackend(String os, int rate, int volume, String voice) {
        this(os, rate, volume, voice, "", "");
    }

    private List<String> poolMale = Collections.emptyList();
    private List<String> poolFemale = Collections.emptyList();

    /** Voice pools: each named character gets (and keeps) one of these voices instead of the male / female voice. */
    public SystemBackend withPools(List<String> male, List<String> female) {
        poolMale = male == null ? Collections.<String>emptyList() : new ArrayList<String>(male);
        poolFemale = female == null ? Collections.<String>emptyList() : new ArrayList<String>(female);
        return this;
    }

    /** This backend with the character voices this request's speaker gets from the pools. */
    SystemBackend forSpeaker(SpeechRequest r) {
        String key = VoicePool.key(r);
        String m = VoicePool.pick(key, poolMale), f = VoicePool.pick(key, poolFemale);
        if (m == null && f == null) return this;
        return new SystemBackend(os, rate, volume, narrator, m != null ? m : male, f != null ? f : female);
    }

    /** The command line(s) for a request, with its speaker's pool voices. Public for tests. */
    public List<List<String>> commands(SpeechRequest r) {
        return forSpeaker(r).commands(r.parts());
    }

    @Override
    public String name() {
        return "system voice";
    }

    @Override
    public synchronized void speak(SpeechRequest r) throws Exception {
        stop();
        List<List<String>> candidates = commands(r);
        if (candidates.isEmpty()) return;
        int first = linuxChoice >= 0 && linuxChoice < candidates.size() ? linuxChoice : 0;
        IOException last = null;
        for (int i = first; i < candidates.size(); i++) {
            try {
                ProcessBuilder pb = new ProcessBuilder(candidates.get(i));
                pb.redirectErrorStream(true);
                process = pb.start();
                watch(process, candidates.get(i).get(0));
                if (candidates.size() > 1) linuxChoice = i;
                return;
            } catch (IOException e) {
                last = e; // program not installed: try the next one
            }
        }
        throw new IOException("no system voice program could be started (" + describe(candidates) + ")", last);
    }

    @Override
    public synchronized void stop() {
        if (process != null) {
            if (process.isAlive()) {
                synchronized (STOPPED) {
                    STOPPED.add(process);
                }
            }
            process.destroy();
            process = null;
        }
    }

    @Override
    public synchronized boolean isSpeaking() {
        return process != null && process.isAlive();
    }

    /** The command line(s) for one narrator text. Public for tests. */
    public List<List<String>> commands(String text) {
        return commands(new SpeechRequest(text).parts());
    }

    /** The command line(s) to try, in order. Public for tests. */
    public List<List<String>> commands(List<VoiceParts.Part> all) {
        List<VoiceParts.Part> parts = limit(all);
        if (parts.isEmpty()) return Collections.emptyList();
        if (os.contains("win")) return Collections.singletonList(windows(parts));
        int wpm = Math.max(80, Math.min(450, 175 + rate * 14));
        boolean mac = os.contains("mac");
        // The voice of each part ("" = the program's default).
        List<String> voices = new ArrayList<String>();
        for (VoiceParts.Part p : parts) voices.add(mac ? macVoice(p.role) : linuxVoice(p.role));
        boolean oneVoice = new HashSet<String>(voices).size() == 1;
        if (mac) {
            if (oneVoice) {
                List<String> c = new ArrayList<String>(Arrays.asList("say", "-r", String.valueOf(wpm)));
                if (!voices.get(0).isEmpty()) c.addAll(Arrays.asList("-v", voices.get(0)));
                c.addAll(Arrays.asList("--", joined(parts)));
                return Collections.singletonList(c);
            }
            return Collections.singletonList(shell(MAC_SCRIPT.replace("WPM", String.valueOf(wpm)), parts, voices));
        }
        int amp = Math.round(volume * 2f); // espeak: 0..200, 100 is normal
        String spdRate = String.valueOf(rate * 10), spdVolume = String.valueOf(volume * 2 - 100);
        if (!oneVoice) {
            return Collections.singletonList(shell(LINUX_SCRIPT.replace("WPM", String.valueOf(wpm)).replace("AMP", String.valueOf(amp))
                    .replace("SPDRATE", spdRate).replace("SPDVOL", spdVolume), parts, voices));
        }
        String text = joined(parts);
        List<List<String>> out = new ArrayList<List<String>>();
        List<String> espeakNg = new ArrayList<String>(Arrays.asList("espeak-ng", "-s", String.valueOf(wpm), "-a", String.valueOf(amp)));
        if (!voices.get(0).isEmpty()) espeakNg.addAll(Arrays.asList("-v", voices.get(0)));
        espeakNg.addAll(Arrays.asList("--", text));
        out.add(espeakNg);
        List<String> espeak = new ArrayList<String>(espeakNg);
        espeak.set(0, "espeak");
        out.add(espeak);
        out.add(Arrays.asList("spd-say", "-w", "-r", spdRate, "-i", spdVolume, "--", text));
        return out;
    }

    private String named(String role) {
        if (VoiceParts.MALE.equals(role)) return male;
        if (VoiceParts.FEMALE.equals(role)) return female;
        return narrator;
    }

    private String macVoice(String role) {
        String n = named(role);
        return n.isEmpty() ? narrator : n; // no automatic gender pick on macOS
    }

    /** espeak's own male and female variants stand in when no voice is chosen. */
    private String linuxVoice(String role) {
        String n = named(role);
        if (!n.isEmpty()) return n;
        if (VoiceParts.MALE.equals(role)) return "en+m3";
        if (VoiceParts.FEMALE.equals(role)) return "en+f3";
        return narrator;
    }

    /** Keeps the parts within MAX_CHARS in total. */
    private static List<VoiceParts.Part> limit(List<VoiceParts.Part> parts) {
        List<VoiceParts.Part> out = new ArrayList<VoiceParts.Part>();
        int left = MAX_CHARS;
        for (VoiceParts.Part p : parts) {
            if (left <= 0) break;
            if (p.text.trim().isEmpty()) continue;
            String t = p.text.length() > left ? p.text.substring(0, left) : p.text;
            out.add(new VoiceParts.Part(p.role, t));
            left -= t.length();
        }
        return out;
    }

    private static String joined(List<VoiceParts.Part> parts) {
        StringBuilder sb = new StringBuilder();
        for (VoiceParts.Part p : parts) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(p.text);
        }
        return sb.toString();
    }

    // ---- macOS / Linux: several voices in one process ----

    /**
     * Arguments after the script: voice, text, voice, text... ("-" = the default voice; an
     * empty argument is fragile). Each part runs in the background and is waited for, so
     * stopping (SIGTERM to sh) can kill the one playing.
     */
    private static final String STOP_TRAP = "c=\ntrap 'if [ -n \"$c\" ]; then kill \"$c\" 2>/dev/null; fi; exit 143' TERM INT\n";
    static final String DEFAULT_VOICE_ARG = "-";
    static final String MAC_SCRIPT = STOP_TRAP
            + "while [ \"$#\" -gt 1 ]; do\n"
            + "  if [ \"$1\" != \"-\" ]; then say -r WPM -v \"$1\" -- \"$2\" &\n"
            + "  else say -r WPM -- \"$2\" &\n"
            + "  fi\n"
            + "  c=$!; wait \"$c\"; shift 2\n"
            + "done\n";
    static final String LINUX_SCRIPT = STOP_TRAP
            + "E=\n"
            + "for p in espeak-ng espeak; do if command -v \"$p\" >/dev/null 2>&1; then E=$p; break; fi; done\n"
            + "while [ \"$#\" -gt 1 ]; do\n"
            + "  if [ -z \"$E\" ]; then spd-say -w -r SPDRATE -i SPDVOL -- \"$2\" &\n"
            + "  elif [ \"$1\" != \"-\" ]; then \"$E\" -s WPM -a AMP -v \"$1\" -- \"$2\" &\n"
            + "  else \"$E\" -s WPM -a AMP -- \"$2\" &\n"
            + "  fi\n"
            + "  c=$!; wait \"$c\"; shift 2\n"
            + "done\n";

    private static List<String> shell(String script, List<VoiceParts.Part> parts, List<String> voices) {
        List<String> c = new ArrayList<String>(Arrays.asList("sh", "-c", script, "sh"));
        for (int i = 0; i < parts.size(); i++) {
            c.add(voices.get(i).isEmpty() ? DEFAULT_VOICE_ARG : voices.get(i));
            c.add(parts.get(i).text);
        }
        return c;
    }

    // ---- Windows ----

    private List<String> windows(List<VoiceParts.Part> parts) {
        Set<String> roles = new LinkedHashSet<String>();
        for (VoiceParts.Part p : parts) roles.add(p.role);
        StringBuilder sb = new StringBuilder();
        sb.append("$ErrorActionPreference='Stop';")
                .append("Add-Type -AssemblyName System.Speech;")
                .append("$s=New-Object System.Speech.Synthesis.SpeechSynthesizer;")
                .append("$s.Rate=").append(Math.max(-10, Math.min(10, rate))).append(";")
                .append("$s.Volume=").append(Math.max(0, Math.min(100, volume))).append(";")
                .append("$d=$s.Voice.Name;")
                .append("$all=@($s.GetInstalledVoices()|?{$_.Enabled}|%{$_.VoiceInfo});")
                // "... Online" voices are cloud voices: each phrase waits about 5 s for the network,
                // and they can stay silent. They are only used when chosen by name.
                .append("$off=@($all|?{$_.Name -notlike '* Online'});")
                .append("$en=@($off|?{$_.Culture.TwoLetterISOLanguageName -eq 'en'});")
                // Pick(name, gender, avoid): the named voice if installed; else for a character an
                // installed voice of that gender (English first, preferably not the narrator's);
                // else the default voice unless it is a cloud voice.
                .append("function Pick($n,$g,$a){")
                .append("if($n -and @($all|?{$_.Name -eq $n}).Count){return $n};")
                .append("if($g){foreach($l in @(@($en|?{$_.Name -ne $a}),$en,$off)){")
                .append("$m=@($l|?{\"$($_.Gender)\" -eq $g});if($m.Count){return $m[0].Name}}};")
                .append("if($d -notlike '* Online'){return $d};")
                .append("if($en.Count){return $en[0].Name};if($off.Count){return $off[0].Name};return $d};")
                .append("$v=@{};")
                .append("$v['narrator']=(Pick ").append(quote(narrator)).append(" '' '');");
        if (roles.contains(VoiceParts.MALE)) {
            sb.append("$v['male']=(Pick ").append(quote(male)).append(" 'Male' $v['narrator']);");
        }
        if (roles.contains(VoiceParts.FEMALE)) {
            sb.append("$v['female']=(Pick ").append(quote(female)).append(" 'Female' $v['narrator']);");
        }
        for (String role : roles) {
            sb.append("[Console]::Out.WriteLine('").append(VOICE_PREFIX).append(role).append("='+$v['").append(role).append("']);");
        }
        sb.append("[Console]::Out.Flush();");
        // The text travels as base64 so no quoting or console-encoding issue can mangle it.
        sb.append("function Say($r,$b){$s.SelectVoice($v[$r]);$s.Speak([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($b)))};");
        for (VoiceParts.Part p : parts) {
            String b64 = java.util.Base64.getEncoder().encodeToString(p.text.getBytes(Charset.forName("UTF-8")));
            sb.append("Say '").append(p.role).append("' '").append(b64).append("';");
        }
        // After speaking, the speech engine can keep PowerShell alive for ~8 s more (and Dispose()
        // waits ~3 s). The process must end with the speech, since "still running" means "still
        // speaking" to the button, so it kills itself, after saying it finished.
        sb.append("[Console]::Out.WriteLine('").append(DONE).append("');[Console]::Out.Flush();")
                .append("[Diagnostics.Process]::GetCurrentProcess().Kill()");
        return Arrays.asList("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", sb.toString());
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    private static String describe(List<List<String>> candidates) {
        StringBuilder sb = new StringBuilder();
        for (List<String> c : candidates) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(c.get(0));
        }
        return sb.toString();
    }

    /**
     * Reads the process output (so a full pipe can never block it), then reports:
     * the voices used, once per voice, and a failure with its output unless we stopped it.
     */
    private void watch(final Process p, final String program) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder out = new StringBuilder();
                InputStream in = p.getInputStream();
                byte[] buf = new byte[1024];
                try {
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        if (out.length() < 4000) out.append(new String(buf, 0, n, Charset.defaultCharset()));
                    }
                    int code = p.waitFor();
                    StringBuilder rest = new StringBuilder();
                    StringBuilder report = new StringBuilder();
                    boolean done = false;
                    for (String line : out.toString().split("\r?\n")) {
                        String l = line.trim();
                        if (l.equals(DONE)) {
                            done = true;
                        } else if (l.startsWith(VOICE_PREFIX)) {
                            String v = l.substring(VOICE_PREFIX.length()).trim();
                            report.append(v).append('\n');
                            ReadAloudLog.infoOnce("sysvoice:" + v, "system voice: " + v.replace("=", " = "));
                        } else if (!l.isEmpty()) {
                            rest.append(l).append(' ');
                        }
                    }
                    if (report.length() > 0) lastVoiceReport = report.toString();
                    synchronized (STOPPED) {
                        if (STOPPED.remove(p)) return;
                    }
                    String msg = rest.toString().trim();
                    if (code != 0 && !done) {
                        ReadAloudLog.warnOnce("sysfail:" + code + ":" + msg.hashCode(), "the system voice (" + program
                                + ") failed with exit code " + code + (msg.isEmpty() ? "" : ": " + msg));
                    }
                } catch (IOException ignored) {
                } catch (InterruptedException ignored) {
                } finally {
                    synchronized (STOPPED) {
                        STOPPED.remove(p);
                    }
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }, "ReadAloud-system-voice-output");
        t.setDaemon(true);
        t.start();
    }
}
