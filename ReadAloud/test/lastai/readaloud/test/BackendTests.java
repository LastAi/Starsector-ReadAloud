package lastai.readaloud.test;

import lastai.readaloud.speech.CompanionBackend;
import lastai.readaloud.speech.CompanionLauncher;
import lastai.readaloud.speech.SpeechRequest;
import lastai.readaloud.speech.SystemBackend;
import lastai.readaloud.speech.SystemVoices;
import lastai.readaloud.text.VoiceParts;
import org.json.JSONObject;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BackendTests {
    public static void windowsCommandCarriesTextAsBase64() {
        String text = "\"Don't panic,\" she says. Ünïcödé — 100%; $env:X `tick`";
        List<List<String>> cmds = new SystemBackend("Windows 11", 3, 80, "Microsoft Zira Desktop").commands(text);
        T.eq(1, cmds.size(), "one command");
        List<String> c = cmds.get(0);
        T.eq("powershell.exe", c.get(0), "program");
        String script = c.get(c.size() - 1);
        Matcher m = Pattern.compile("Say 'narrator' '([A-Za-z0-9+/=]+)'").matcher(script);
        T.yes(m.find(), "base64 present");
        T.eq(text, new String(Base64.getDecoder().decode(m.group(1)), StandardCharsets.UTF_8), "round trip");
        T.yes(script.contains("$s.Rate=3;") && script.contains("$s.Volume=80;"), "rate + volume");
        T.yes(script.contains("$v['narrator']=(Pick 'Microsoft Zira Desktop' '' '')"), "voice");
        T.no(script.contains("Don't"), "raw text never in the script");
        T.no(script.contains("$v['male']"), "only the roles used are resolved");
    }

    public static void windowsVoiceNameQuotesAreEscaped() {
        String script = last(new SystemBackend("windows", 0, 100, "O'Neil").commands("hi").get(0));
        T.yes(script.contains("(Pick 'O''Neil' '' '')"), "doubled quote: " + script);
    }

    public static void windowsSkipsOnlineVoicesUnlessNamed() {
        String auto = last(new SystemBackend("windows", 0, 100, "").commands("hi").get(0));
        T.yes(auto.contains("$off=@($all|?{$_.Name -notlike '* Online'})"), "cloud voices are not picked automatically");
        T.yes(auto.contains("if($d -notlike '* Online'){return $d}"), "the default voice only if it is not a cloud voice");
        T.yes(auto.contains("TwoLetterISOLanguageName -eq 'en'"), "English first");
        T.yes(auto.contains("if($n -and @($all|?{$_.Name -eq $n}).Count){return $n}"), "a voice chosen by name is kept, even a cloud one");
    }

    /** Each part is spoken in its role's voice, in order, in one process. */
    public static void windowsSpeaksEachPartInItsVoice() {
        List<VoiceParts.Part> parts = Arrays.asList(new VoiceParts.Part("narrator", "The officer leans in."),
                new VoiceParts.Part("female", "Papers, please."), new VoiceParts.Part("narrator", "She waits."));
        String script = last(new SystemBackend("windows", 0, 100, "", "Microsoft David Desktop", "Microsoft Zira Desktop").commands(parts).get(0));
        T.yes(script.contains("$v['female']=(Pick 'Microsoft Zira Desktop' 'Female' $v['narrator'])"), "female voice resolved");
        T.no(script.contains("$v['male']"), "male not needed");
        Matcher m = Pattern.compile("Say '(\\w+)' '([A-Za-z0-9+/=]+)'").matcher(script);
        List<String> said = new ArrayList<String>();
        while (m.find()) said.add(m.group(1) + ":" + new String(Base64.getDecoder().decode(m.group(2)), StandardCharsets.UTF_8));
        T.eq(Arrays.asList("narrator:The officer leans in.", "female:Papers, please.", "narrator:She waits."), said, "parts in order");
    }

    /** Real Windows speech at volume 0: automatic picks give each role an installed voice, never a cloud one. */
    public static void windowsAutomaticVoicesAreRealAndOffline() throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        SystemBackend b = new SystemBackend(0, 0, "", "", "");
        b.speak(new SpeechRequest(Arrays.asList(new VoiceParts.Part("narrator", "A."),
                new VoiceParts.Part("male", "B."), new VoiceParts.Part("female", "C."))));
        long t0 = System.nanoTime();
        while (b.isSpeaking() && System.nanoTime() - t0 < 20_000_000_000L) Thread.sleep(25);
        double s = (System.nanoTime() - t0) / 1e9;
        // ~1.1 s per part plus startup; without the self-kill PowerShell lingered to ~11 s.
        T.yes(s < 7, "three short parts took " + s + " s");
        T.eventually(2000, "voice report", new java.util.concurrent.Callable<Boolean>() {
            @Override
            public Boolean call() {
                return b.lastVoiceReport.contains("female=");
            }
        });
        String report = b.lastVoiceReport;
        T.yes(report.contains("narrator=Microsoft") && report.contains("male=Microsoft"), "voices: " + report);
        T.no(report.contains("Online"), "no cloud voice: " + report);
        List<SystemVoices.Voice> installed = SystemVoices.list();
        boolean hasMale = false, hasFemale = false;
        for (SystemVoices.Voice v : installed) {
            if (v.name.endsWith(" Online")) continue;
            hasMale |= "male".equals(v.gender);
            hasFemale |= "female".equals(v.gender);
        }
        if (hasMale && hasFemale) {
            String male = line(report, "male="), female = line(report, "female=");
            T.no(male.equals(female), "male and female characters get different voices: " + report);
        }
    }

    private static String line(String report, String prefix) {
        for (String l : report.split("\n")) if (l.startsWith(prefix)) return l.substring(prefix.length());
        return "";
    }

    /** macOS / Linux with several voices: one sh process, voice/text pairs as arguments. */
    public static void unixSpeaksPartsThroughOneShell() {
        List<VoiceParts.Part> parts = Arrays.asList(new VoiceParts.Part("narrator", "-dash first."),
                new VoiceParts.Part("male", "Hello."), new VoiceParts.Part("female", "Hi."));
        List<List<String>> linux = new SystemBackend("Linux", 0, 100, "", "", "").commands(parts);
        T.eq(1, linux.size(), "one command");
        List<String> c = linux.get(0);
        T.eq(Arrays.asList("sh", "-c"), c.subList(0, 2), "sh -c");
        T.eq(Arrays.asList("sh", "-", "-dash first.", "en+m3", "Hello.", "en+f3", "Hi."), c.subList(3, c.size()),
                "args: narrator default, espeak's male and female variants");
        List<String> mac = new SystemBackend("Mac OS X", 0, 100, "Samantha", "Alex", "").commands(parts).get(0);
        T.eq(Arrays.asList("sh", "Samantha", "-dash first.", "Alex", "Hello.", "Samantha", "Hi."), mac.subList(3, mac.size()),
                "mac: automatic = the narrator's voice");
        // One voice for everything keeps the simple command (and the espeak-ng/espeak/spd-say fallback).
        List<List<String>> single = new SystemBackend("Linux", 0, 100, "", "", "")
                .commands(Collections.singletonList(new VoiceParts.Part("narrator", "Only me.")));
        T.eq(3, single.size(), "fallback list");
    }

    /** Speech mode's pauses: Start-Sleep on Windows, a "+pause" pair for the sh script elsewhere. */
    public static void pausesReachEverySystemVoice() {
        List<VoiceParts.Part> parts = Arrays.asList(new VoiceParts.Part("male", "Hello Commander."),
                new VoiceParts.Part("male", "What can I do for you?", 500));
        String win = last(new SystemBackend("windows", 0, 100, "").commands(parts).get(0));
        T.yes(win.indexOf("Start-Sleep -Milliseconds 500;Say 'male'") > 0, "windows sleeps before the second part: " + win);
        T.eq(1, win.split("Start-Sleep").length - 1, "only one pause");
        // One voice, but the pause needs the script (no plain espeak / say shortcut).
        List<List<String>> linux = new SystemBackend("Linux", 0, 100, "", "", "").commands(parts);
        T.eq(1, linux.size(), "script, not the fallback list");
        List<String> c = linux.get(0);
        T.eq(Arrays.asList("sh", "en+m3", "Hello Commander.", "+pause", "0.500", "en+m3", "What can I do for you?"),
                c.subList(3, c.size()), "pause pair before the part");
        List<String> mac = new SystemBackend("Mac OS X", 0, 100, "").commands(parts).get(0);
        T.eq("sh", mac.get(0), "mac uses the script too");
        T.eq("1", SystemBackend.seconds(1000), "whole seconds");
    }

    /**
     * Runs the Linux script with a fake espeak-ng (when a POSIX sh is available, e.g. Git Bash),
     * checking each part reaches the program with its voice and text intact.
     */
    public static void linuxScriptRunsEachPart() throws Exception {
        java.io.File dir = new java.io.File(System.getProperty("readaloud.classes", "."), "../sh-test").getCanonicalFile();
        dir.mkdirs();
        java.io.File log = new java.io.File(dir, "calls.txt");
        log.delete();
        java.io.File fake = new java.io.File(dir, "espeak-ng");
        java.nio.file.Files.write(fake.toPath(), ("#!/bin/sh\nfor a in \"$@\"; do printf '[%s]' \"$a\" >> '"
                + log.getPath().replace('\\', '/') + "'; done; echo >> '" + log.getPath().replace('\\', '/') + "'\n")
                .getBytes(StandardCharsets.UTF_8));
        fake.setExecutable(true);
        java.io.File fakeSleep = new java.io.File(dir, "sleep");
        java.nio.file.Files.write(fakeSleep.toPath(), ("#!/bin/sh\nprintf '[sleep][%s]\\n' \"$1\" >> '"
                + log.getPath().replace('\\', '/') + "'\n").getBytes(StandardCharsets.UTF_8));
        fakeSleep.setExecutable(true);
        // Windows mangles double quotes inside arguments on the way to sh (not an issue on Linux).
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String text = windows ? "It's $HOME and `x`." : "It's \"quoted\" $HOME and `x`.";
        List<String> cmd = new SystemBackend("Linux", 0, 100, "", "", "").commands(Arrays.asList(
                new VoiceParts.Part("narrator", text), new VoiceParts.Part("female", "-Hi.", 250))).get(0);
        // The script goes in a file: Windows can't pass an argument full of quotes to sh intact.
        java.io.File script = new java.io.File(dir, "speak.sh");
        java.nio.file.Files.write(script.toPath(), cmd.get(2).getBytes(StandardCharsets.UTF_8));
        List<String> run = new ArrayList<String>(Arrays.asList("sh", script.getPath().replace('\\', '/')));
        run.addAll(cmd.subList(4, cmd.size()));
        ProcessBuilder pb = new ProcessBuilder(run);
        String path = dir.getPath() + java.io.File.pathSeparator + System.getenv("PATH");
        pb.environment().put("PATH", path);
        pb.redirectErrorStream(true);
        Process p;
        try {
            p = pb.start();
        } catch (java.io.IOException e) {
            return; // no sh here
        }
        byte[] out = readAll(p.getInputStream());
        p.waitFor();
        String calls = log.exists() ? new String(java.nio.file.Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8) : "";
        T.eq("[-s][175][-a][200][--][" + text + "]\n[sleep][0.250]\n[-s][175][-a][200][-v][en+f3][--][-Hi.]\n",
                calls.replace("\r", ""), "calls (output: " + new String(out, StandardCharsets.UTF_8) + ")");
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    /**
     * Real Windows speech (at volume 0): a one-word line must finish quickly. With a cloud
     * "Online" default voice it took over 5 s, so button presses seemed to do nothing.
     */
    public static void windowsSystemVoiceIsQuick() throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        SystemBackend b = new SystemBackend(0, 0, "", "", "");
        long t0 = System.nanoTime();
        b.speak(new lastai.readaloud.speech.SpeechRequest("Hi."));
        while (b.isSpeaking() && System.nanoTime() - t0 < 15_000_000_000L) Thread.sleep(25);
        double s = (System.nanoTime() - t0) / 1e9;
        T.yes(s < 3.5, "system voice took " + s + " s for one word");
    }

    public static void linuxTriesSeveralPrograms() {
        List<List<String>> cmds = new SystemBackend("Linux", -10, 50, "").commands("hello");
        T.eq(Arrays.asList("espeak-ng", "espeak", "spd-say"), Arrays.asList(cmds.get(0).get(0), cmds.get(1).get(0), cmds.get(2).get(0)), "order");
        T.eq("hello", last(cmds.get(0)), "text is last, after --");
        T.eq("--", cmds.get(0).get(cmds.get(0).size() - 2), "-- guards against text starting with -");
        T.yes(cmds.get(0).contains("100"), "amplitude 50% -> 100");
    }

    public static void macUsesSay() {
        List<String> c = new SystemBackend("Mac OS X", 0, 100, "").commands("hi").get(0);
        T.eq("say", c.get(0), "say");
        T.eq("hi", last(c), "text");
    }

    public static void launcherCommands() {
        T.eq(Arrays.asList("cmd.exe", "/c", "D:\\Starsector\\mods\\ReadAloud\\companion\\start.cmd", "--background"),
                CompanionLauncher.command("D:\\Starsector\\mods\\ReadAloud", true), "windows");
        T.eq(Arrays.asList("sh", "/games/ss/mods/ReadAloud/companion/start.sh", "--background"),
                CompanionLauncher.command("/games/ss/mods/ReadAloud/", false), "linux");
    }

    public static void companionProtocol() throws Exception {
        try (FakeCompanion fake = new FakeCompanion()) {
            CompanionBackend b = new CompanionBackend(fake.port());
            T.yes(b.ready(), "ready");
            T.eq("companion (fake)", b.name(), "engine name from status");
            SpeechRequest r = new SpeechRequest("Hello — captain.");
            r.speakerName = "Ana Kato";
            r.speakerGender = "female";
            r.factionId = "hegemony";
            b.speak(r);
            T.yes(b.isSpeaking(), "speaking after speak");
            JSONObject got = fake.spoken.get(0);
            T.eq("Hello — captain.", got.getString("text"), "utf-8 text");
            T.eq("female", got.getJSONObject("speaker").getString("gender"), "gender");
            T.eq("hegemony", got.getJSONObject("speaker").getString("faction"), "faction");
            fake.speaking = false;
            b.poll();
            T.no(b.isSpeaking(), "status poll updates speaking");
            b.stop();
            T.eq(1, fake.stops, "stop sent");
            T.eq(0, fake.missingHeader, "header always sent");
            fake.ready = false;
            T.no(b.ready(), "not ready when engine isn't loaded");
        }
    }

    public static void companionNotRunningIsFastAndQuiet() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        CompanionBackend b = new CompanionBackend(port);
        long t0 = System.currentTimeMillis();
        T.eq(null, b.status(), "no status");
        T.no(b.ready(), "not ready");
        b.stop(); // must not throw
        T.yes(System.currentTimeMillis() - t0 < 3000, "fails fast");
        boolean threw = false;
        try {
            b.speak(new SpeechRequest("x"));
        } catch (Exception e) {
            threw = true;
        }
        T.yes(threw, "speak throws so Speech can fall back");
    }

    private static String last(List<String> l) {
        return l.get(l.size() - 1);
    }
}
