package lastai.readaloud;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import lastai.readaloud.dialog.DialogWatcher;
import lastai.readaloud.speech.AiMenu;
import lastai.readaloud.speech.CompanionLauncher;
import lastai.readaloud.speech.CompanionMonitor;
import lastai.readaloud.speech.CompanionStatus;
import lastai.readaloud.speech.Speech;
import lastai.readaloud.speech.SpeechRequest;
import lastai.readaloud.speech.SystemVoices;
import lastai.readaloud.speech.VoiceSamples;
import org.json.JSONObject;
import org.lwjgl.input.Keyboard;

import java.util.List;

/** Loads settings, starts the speech service, and adds the dialog watcher to every campaign. Saves never reference this mod. */
public final class ReadAloudModPlugin extends BaseModPlugin {
    private static final String CONFIG_FILE = "data/config/readaloud.json";
    private static final String LUNA_BRIDGE = "lastai.readaloud.luna.LunaBridge";

    /** Implemented by the optional LunaLib bridge (src-optional/lunalib). */
    public interface LunaHook extends Config.Overrides {
        void listen(Runnable onChange);

        /** Adds the narrator / male / female system voice menus with these entries. */
        void addVoiceMenus(List<String> choices);

        /** Replaces this mod's fields with these ids ({@code managed}: every id that may be there) by {@code fields}. */
        void setFields(String modId, List<AiMenu.Field> fields, List<String> managed);

        String modString(String modId, String field);

        Boolean modBoolean(String modId, String field);

        /** Runs {@code onChange} when the player saves {@code modId}'s page. */
        void listenMod(String modId, Runnable onChange);

        /** The field is in LunaLib's list. */
        boolean hasField(String modId, String field);
    }

    private LunaHook luna;
    private List<String> voiceChoices;
    private static volatile AiSettings aiSettings;

    /** Called by the dialog watcher on the game thread each frame. */
    public static void gameThreadTick() {
        AiSettings a = aiSettings;
        if (a != null) a.applyPending();
    }

    @Override
    public void onApplicationLoad() {
        luna = loadLuna();
        if (luna != null) addVoiceMenus();
        Config cfg = loadConfig();
        final CompanionLauncher launcher = new CompanionLauncher(ReadAloud.AI_MOD_ID);
        final Speech speech = new Speech(cfg, launcher);
        ReadAloud.set(cfg, speech);
        boolean aiMod = launcher.modEnabled();
        ReadAloudLog.info("loaded; backend=" + cfg.backend + ", voices=" + cfg.voiceMode + ", hotkey="
                + (cfg.hotkey > 0 ? Keyboard.getKeyName(cfg.hotkey) : "none") + ", LunaLib settings " + (luna != null ? "on" : "off")
                + ", " + ReadAloud.AI_MOD_NAME + " " + (aiMod ? "enabled" : "not enabled")
                + (cfg.systemVoicePools ? ", system voice pools " + poolsText(cfg) : ""));
        if (luna != null) startAiSettings(launcher, speech, aiMod);
        // At the title screen too, so the AI voice page can list the app's voices.
        if (aiMod) speech.warmUp();
        if (luna != null) {
            luna.listen(new Runnable() {
                @Override
                public void run() {
                    Config old = ReadAloud.config();
                    Config c = loadConfig();
                    ReadAloud.setConfig(c);
                    Speech speech = ReadAloud.speech();
                    if (speech != null) {
                        speech.configure(c);
                        SpeechRequest sample = VoiceSamples.forChange(old, c);
                        if (sample != null) speech.preview(sample);
                    }
                    ReadAloudLog.info("settings changed; backend=" + c.backend + ", voices=" + c.voiceMode
                            + (c.systemVoicePools ? ", system voice pools " + poolsText(c) : ""));
                    AiSettings a = aiSettings;
                    if (a != null && a.shown() != null && !old.backend.equals(c.backend) && monitor != null) monitor.poke();
                }
            });
        }
    }

    private static CompanionMonitor monitor;

    private void startAiSettings(final CompanionLauncher launcher, final Speech speech, boolean aiMod) {
        try {
            final AiSettings a = new AiSettings(luna, aiMod);
            CompanionMonitor m = new CompanionMonitor(new CompanionMonitor.Env() {
                @Override
                public Config config() {
                    return ReadAloud.config();
                }

                @Override
                public boolean modEnabled() {
                    return launcher.modEnabled();
                }

                @Override
                public boolean setUp() {
                    return launcher.isSetUp();
                }

                @Override
                public boolean starting() {
                    return speech.isStartingCompanion();
                }
            }, a, 5000);
            a.attach(m);
            aiSettings = a;
            monitor = m;
            if (aiMod) {
                luna.listenMod(ReadAloud.AI_MOD_ID, new Runnable() {
                    @Override
                    public void run() {
                        a.saved();
                    }
                });
            }
            m.start();
        } catch (Throwable t) {
            ReadAloudLog.errorOnce("ai-settings", "the AI voice settings page could not be set up", t);
        }
    }

    private static String poolsText(Config c) {
        List<String>[] p = Speech.systemPools(c, SystemVoices.cached());
        return "male " + p[0] + ", female " + p[1];
    }

    /** The voice menus list this computer's voices, so they are made in code rather than in LunaSettings.csv. */
    private void addVoiceMenus() {
        try {
            long t0 = System.currentTimeMillis();
            List<SystemVoices.Voice> voices = SystemVoices.list();
            voiceChoices = SystemVoices.menuChoices(voices);
            luna.addVoiceMenus(voiceChoices);
            ReadAloudLog.info("system voices for the menus (" + (System.currentTimeMillis() - t0) + " ms): " + voiceChoices);
        } catch (Throwable t) {
            ReadAloudLog.errorOnce("voice-menus", "adding the voice menus to LunaLib failed (voices can still be set in "
                    + CONFIG_FILE + ")", t);
        }
    }

    @Override
    public void onGameLoad(boolean newGame) {
        // Start the companion app now, so the first dialog isn't read by the system voice
        // while the app is still loading.
        if (ReadAloud.speech() != null) ReadAloud.speech().warmUp();
        AiSettings ai = aiSettings;
        if (ai != null) ai.reapply();
        Config cfg = ReadAloud.config();
        if (cfg != null && Config.BACKEND_COMPANION.equals(cfg.backend)
                && !Global.getSettings().getModManager().isModEnabled(ReadAloud.AI_MOD_ID)) {
            Notices.post("ai-nomod", CompanionStatus.of(false, cfg, false, false, null).notice(false));
        }
        // In case LunaLib (re)loaded its settings after onApplicationLoad; adding twice is a no-op.
        if (luna != null && voiceChoices != null) {
            try {
                luna.addVoiceMenus(voiceChoices);
            } catch (Throwable ignored) {
            }
        }
        DialogWatcher watcher = new DialogWatcher();
        Global.getSector().addTransientScript(watcher);
        Global.getSector().getListenerManager().addListener(watcher, true);
    }

    private Config loadConfig() {
        JSONObject json = null;
        try {
            json = Global.getSettings().getMergedJSONForMod(CONFIG_FILE, ReadAloud.MOD_ID);
        } catch (Throwable t) {
            ReadAloudLog.error("can't read " + CONFIG_FILE + "; using defaults", t);
        }
        Config c = Config.fromJson(json, new Config.KeyNames() {
            @Override
            public int codeOf(String name) {
                int code = Keyboard.getKeyIndex(name.toUpperCase());
                return code == Keyboard.KEY_NONE ? -1 : code;
            }
        });
        try {
            c.applyOverrides(luna);
        } catch (Throwable t) {
            ReadAloudLog.errorOnce("luna-read", "reading LunaLib settings failed; using " + CONFIG_FILE, t);
        }
        return c;
    }

    private static LunaHook loadLuna() {
        if (!Global.getSettings().getModManager().isModEnabled("lunalib")) return null;
        try {
            Class<?> c = Global.getSettings().getScriptClassLoader().loadClass(LUNA_BRIDGE);
            return (LunaHook) c.newInstance();
        } catch (ClassNotFoundException e) {
            ReadAloudLog.warn("built without the LunaLib bridge; in-game settings are off (edit " + CONFIG_FILE + ")");
        } catch (Throwable t) {
            ReadAloudLog.error("LunaLib bridge failed to load; in-game settings are off", t);
        }
        return null;
    }
}
