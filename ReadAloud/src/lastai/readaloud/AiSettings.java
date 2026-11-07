package lastai.readaloud;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import lastai.readaloud.speech.AiMenu;
import lastai.readaloud.speech.CompanionBackend;
import lastai.readaloud.speech.CompanionMonitor;
import lastai.readaloud.speech.CompanionStatus;
import lastai.readaloud.speech.Speech;
import lastai.readaloud.speech.SpeechRequest;
import lastai.readaloud.speech.VoiceSamples;

import java.util.HashMap;
import java.util.Map;

/**
 * Keeps the LunaLib pages in step with the AI voice app: the add-on's own page (status,
 * engine, voices, pools) and a status line on Read Aloud's page. Rebuilt whenever the app's
 * state changes; what the player picks there is sent to the app.
 */
final class AiSettings implements CompanionMonitor.Listener {
    private final ReadAloudModPlugin.LunaHook luna;
    private final boolean aiMod;
    private volatile CompanionStatus pending;
    /** What the pages show now. */
    private volatile CompanionStatus shown;
    private CompanionMonitor monitor;

    AiSettings(ReadAloudModPlugin.LunaHook luna, boolean aiMod) {
        this.luna = luna;
        this.aiMod = aiMod;
    }

    void attach(CompanionMonitor m) {
        monitor = m;
    }

    @Override
    public void changed(CompanionStatus s) {
        pending = s;
        // In the campaign the dialog watcher applies it on the game thread; at the title screen
        // there is no game-thread hook, and the pages are only read when the menu opens.
        boolean campaign;
        try {
            campaign = Global.getCurrentState() == GameState.CAMPAIGN;
        } catch (Throwable t) {
            campaign = false;
        }
        if (!campaign) applyPending();
    }

    /**
     * LunaLib may reload its field list after we added ours (it loads lazily, and mods start in
     * id order), which drops them: then they are put back with the last status.
     */
    @Override
    public void checked(CompanionStatus s) {
        CompanionStatus now = shown;
        if (now == null || pending != null) return;
        try {
            if (!luna.hasField(ReadAloud.MOD_ID, AiMenu.RA_STATUS)
                    || (aiMod && !luna.hasField(ReadAloud.AI_MOD_ID, AiMenu.STATUS))) {
                ReadAloudLog.info("the AI voice settings fields were gone from LunaLib; adding them again");
                changed(s);
            }
        } catch (Throwable ignored) {
            // the list changed under us: try again next check
        }
    }

    /** Shows the last status again (after a save loads). */
    void reapply() {
        CompanionStatus s = shown;
        if (s != null && pending == null) changed(s);
    }

    /** Puts a waiting change on the pages. Called on the game thread each frame in the campaign. */
    synchronized void applyPending() {
        CompanionStatus s = pending;
        if (s == null) return;
        pending = null;
        try {
            luna.setFields(ReadAloud.MOD_ID, AiMenu.readAloudPage(s), AiMenu.RA_IDS);
            if (aiMod) luna.setFields(ReadAloud.AI_MOD_ID, AiMenu.aiPage(s), AiMenu.AI_IDS);
            shown = s;
        } catch (Throwable t) {
            ReadAloudLog.errorOnce("ai-menu", "updating the AI voice settings page failed", t);
        }
    }

    /** The player saved the add-on's page: send what changed to the app, then play samples. */
    void saved() {
        final CompanionStatus s = shown;
        if (s == null || s.answer == null) return;
        final Map<String, String> radios = new HashMap<String, String>();
        for (String id : new String[] {AiMenu.NARRATOR, AiMenu.MALE, AiMenu.FEMALE}) {
            radios.put(id, luna.modString(ReadAloud.AI_MOD_ID, id));
        }
        final Boolean pools = luna.modBoolean(ReadAloud.AI_MOD_ID, AiMenu.POOLS);
        final Config cfg = ReadAloud.config();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    AiMenu.Change c = AiMenu.change(s, radios, pools);
                    if (c.body == null) return;
                    boolean ok = new CompanionBackend(cfg.companionPort).saveSettings(c.body);
                    ReadAloudLog.info("AI voice settings sent to the app (" + (ok ? "ok" : "refused") + "): " + c.body);
                    SpeechRequest sample = ok ? VoiceSamples.forRoles(c.roles, cfg) : null;
                    Speech speech = ReadAloud.speech();
                    if (sample != null && speech != null) speech.previewCompanion(sample);
                    if (monitor != null) monitor.poke();
                } catch (Throwable e) {
                    ReadAloudLog.errorOnce("ai-save", "sending the AI voice settings to the app failed", e);
                }
            }
        }, "ReadAloud-ai-settings");
        t.setDaemon(true);
        t.start();
    }

    CompanionStatus shown() {
        return shown;
    }
}
