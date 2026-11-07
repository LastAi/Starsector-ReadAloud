package lastai.readaloud.dialog;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.listeners.CampaignUIRenderingListener;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import com.fs.starfarer.api.util.Misc;
import lastai.readaloud.Config;
import lastai.readaloud.Notices;
import lastai.readaloud.ReadAloud;
import lastai.readaloud.ReadAloudLog;
import lastai.readaloud.ReadAloudModPlugin;

/**
 * Notices interaction dialogs opening and closing, and gives each one a speaker button.
 * Runs as a transient campaign script (also while paused) and, as a backup in case
 * scripts don't tick while a dialog is up, from the campaign UI rendering hook.
 * Nothing is stored in the save.
 */
public final class DialogWatcher implements EveryFrameScript, CampaignUIRenderingListener {
    private InteractionDialogAPI current;
    private SpeakerButton button;
    private CustomPanelAPI panel;
    /** True when the button couldn't be added to the dialog, so this class drives it instead. */
    private boolean headless;

    @Override
    public void advance(float amount) {
        tick(amount);
    }

    @Override
    public void renderInUICoordsBelowUI(ViewportAPI viewport) {
        tick(0f);
    }

    @Override
    public void renderInUICoordsAboveUIBelowTooltips(ViewportAPI viewport) {}

    @Override
    public void renderInUICoordsAboveUIAndTooltips(ViewportAPI viewport) {}

    private void tick(float amount) {
        try {
            ReadAloudModPlugin.gameThreadTick();
            String notice = Notices.poll();
            if (notice != null) showNotice(notice);
        } catch (Throwable t) {
            ReadAloudLog.errorOnce("watcher-notice", "showing a Read Aloud message failed", t);
        }
        try {
            InteractionDialogAPI d = Global.getSector() == null || Global.getSector().getCampaignUI() == null
                    ? null : Global.getSector().getCampaignUI().getCurrentInteractionDialog();
            if (d != current) {
                if (current != null) closed();
                current = d;
                button = null;
                panel = null;
                if (d != null) open(d);
                return;
            }
            if (button == null || amount <= 0f) return;
            if (headless) {
                button.advance(amount);
                return;
            }
            button.tickWatch(amount);
            if (button.lastAdvance() > 1f) {
                // The button hasn't updated for a second: a sub-dialog may be on top (fine), or the
                // dialog rebuilt its contents and dropped it (then add it again).
                button.tickWatch(-button.lastAdvance());
                if (!UiTree.children(d).contains(panel)) {
                    ReadAloudLog.info("speaker button was removed from the dialog; adding it again");
                    open(d);
                }
            }
        } catch (Throwable t) {
            ReadAloudLog.errorOnce("watcher", "dialog watcher failed (the speaker button may be missing)", t);
        }
    }

    /** In the campaign message log, and next to the speaker button when a dialog is open (where the player looks). */
    private void showNotice(String text) {
        if (Global.getSector() != null && Global.getSector().getCampaignUI() != null) {
            Global.getSector().getCampaignUI().addMessage(text, Misc.getHighlightColor());
        }
        if (button != null) button.showNotice(text);
    }

    private void open(InteractionDialogAPI d) {
        Config cfg = ReadAloud.config();
        float size = cfg == null ? 22f : cfg.buttonSize;
        button = new SpeakerButton(d);
        headless = !(d instanceof UIPanelAPI);
        if (headless) {
            ReadAloudLog.warnOnce("headless:" + d.getClass().getName(), d.getClass().getName()
                    + " is not a UI panel, so no speaker button can be shown; the hotkey still works");
            return;
        }
        panel = Global.getSettings().createCustom(size, size, button);
        ((UIPanelAPI) d).addComponent(panel);
        button.attach(panel);
    }

    private void closed() {
        Config cfg = ReadAloud.config();
        if (cfg != null && cfg.stopOnClose && ReadAloud.speech() != null) ReadAloud.speech().stop();
    }

    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public boolean runWhilePaused() {
        return true;
    }
}
