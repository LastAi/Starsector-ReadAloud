package lastai.readaloud.speech;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ModSpecAPI;
import lastai.readaloud.ReadAloud;
import lastai.readaloud.ReadAloudLog;

import java.util.Arrays;
import java.util.List;

/**
 * Starts companion/start.cmd (Windows) or companion/start.sh (Linux, macOS) of the
 * optional add-on mod (Read Aloud: AI Voices) in the background, with --background so it
 * opens no browser and exits once the game has been gone for a while. Only does so after
 * the player has run the setup once: the app writes companion/runtime/ready.txt then.
 */
public final class CompanionLauncher implements Speech.Launcher {
    public static final String READY_MARKER = "companion/runtime/ready.txt";
    private final String modId;

    public CompanionLauncher(String modId) {
        this.modId = modId;
    }

    @Override
    public boolean modEnabled() {
        try {
            return Global.getSettings().getModManager().isModEnabled(modId);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean isSetUp() {
        if (!modEnabled()) return false;
        try {
            Global.getSettings().loadText(READY_MARKER, modId);
            return true;
        } catch (Throwable notSetUp) {
            return false;
        }
    }

    @Override
    public boolean launch() {
        if (!modEnabled()) {
            ReadAloudLog.infoOnce("no-ai-mod", "the " + ReadAloud.AI_MOD_NAME + " mod isn't enabled; using the system voice");
            return false;
        }
        if (!isSetUp()) {
            ReadAloudLog.info("companion app not set up yet (no " + READY_MARKER + " in " + ReadAloud.AI_MOD_NAME
                    + "); using the system voice");
            return false;
        }
        ModSpecAPI spec = Global.getSettings().getModManager().getModSpec(modId);
        if (spec == null) return false;
        String dir = spec.getPath();
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> cmd = command(dir, windows);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getOutputStream().close();
            ReadAloudLog.info("started the companion app: " + cmd);
            // Output isn't read: the app logs to companion/runtime/companion.log in background mode.
            p.getInputStream().close();
            return true;
        } catch (Exception e) {
            ReadAloudLog.error("could not start the companion app with " + cmd, e);
            return false;
        }
    }

    public static List<String> command(String modDir, boolean windows) {
        String sep = windows ? "\\" : "/";
        String base = modDir.endsWith(sep) || modDir.endsWith("/") ? modDir : modDir + sep;
        if (windows) return Arrays.asList("cmd.exe", "/c", base + "companion" + sep + "start.cmd", "--background");
        return Arrays.asList("sh", base + "companion" + sep + "start.sh", "--background");
    }
}
