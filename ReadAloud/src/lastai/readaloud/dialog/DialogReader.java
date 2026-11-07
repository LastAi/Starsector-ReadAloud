package lastai.readaloud.dialog;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import lastai.readaloud.ReadAloudLog;
import lastai.readaloud.speech.SpeechRequest;

import java.util.ArrayList;
import java.util.List;

/** Reads one dialog's paragraphs, options and speaker. Every read is guarded: on failure it returns empty data. */
public final class DialogReader {
    public final List<LabelAPI> labels = new ArrayList<LabelAPI>();
    public final List<String> texts = new ArrayList<String>();
    public final List<String> options = new ArrayList<String>();

    /** Refreshes the snapshot from {@code dialog}. */
    public void read(InteractionDialogAPI dialog) {
        labels.clear();
        texts.clear();
        options.clear();
        try {
            TextPanelAPI tp = dialog.getTextPanel();
            if (tp != null) {
                if (!UiTree.canWalk(tp)) {
                    ReadAloudLog.warnOnce("no-walk:" + tp.getClass().getName(), "can't read the text of "
                            + tp.getClass().getName() + " (this game version has no getChildrenCopy on it); the speaker button will stay hidden");
                }
                for (LabelAPI l : UiTree.labels(tp)) {
                    String t = l.getText();
                    labels.add(l);
                    texts.add(t == null ? "" : t);
                }
            }
            OptionPanelAPI op = dialog.getOptionPanel();
            if (op != null) {
                for (ButtonAPI b : UiTree.buttons(op)) {
                    String t = b.getText();
                    if (t != null && !t.trim().isEmpty()) options.add(t);
                }
            }
        } catch (Throwable t) {
            ReadAloudLog.errorOnce("read:" + t.getClass().getName(), "reading dialog text failed", t);
            labels.clear();
            texts.clear();
            options.clear();
        }
    }

    /** Fills in who is speaking, as far as the dialog says. */
    public static void describeSpeaker(InteractionDialogAPI dialog, SpeechRequest r) {
        try {
            Object plugin = dialog.getPlugin();
            if (plugin != null) r.dialog = plugin.getClass().getSimpleName();
            SectorEntityToken target = dialog.getInteractionTarget();
            PersonAPI p = target == null ? null : target.getActivePerson();
            if (p == null) return;
            r.speakerName = safe(p.getNameString());
            r.speakerId = safe(p.getId());
            r.portrait = safe(p.getPortraitSprite());
            if (p.getFaction() != null) r.factionId = safe(p.getFaction().getId());
            FullName.Gender g = p.getGender();
            if (g == FullName.Gender.MALE) r.speakerGender = "male";
            else if (g == FullName.Gender.FEMALE) r.speakerGender = "female";
        } catch (Throwable t) {
            ReadAloudLog.errorOnce("speaker", "reading the dialog's speaker failed (the default voice is used)", t);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
