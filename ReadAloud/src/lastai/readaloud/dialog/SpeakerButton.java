package lastai.readaloud.dialog;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.util.Misc;
import lastai.readaloud.Config;
import lastai.readaloud.ReadAloud;
import lastai.readaloud.ReadAloudLog;
import lastai.readaloud.speech.Speech;
import lastai.readaloud.speech.SpeechRequest;
import lastai.readaloud.text.SegmentTracker;
import lastai.readaloud.text.TextCleaner;
import lastai.readaloud.text.VoiceParts;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * The speaker button. A small custom panel added to the interaction dialog itself,
 * so it gets the dialog's input (clicks on it are consumed and never reach the
 * dialog) and disappears with it. It also tracks the dialog's text every frame for
 * auto-read and the hotkey.
 */
public final class SpeakerButton implements CustomUIPanelPlugin {
    private static final float READ_INTERVAL = 0.1f;
    /** Auto-read waits until the text has stopped changing for this long. */
    private static final float SETTLE = 0.4f;
    /** Text unchanged this long: whatever is added next starts a new portion. */
    private static final float PAUSE = 0.75f;

    private final InteractionDialogAPI dialog;
    private final DialogReader reader = new DialogReader();
    private final SegmentTracker tracker = new SegmentTracker();
    private CustomPanelAPI panel;
    private LabelAPI tip;

    private float sinceRead = READ_INTERVAL;
    private int lastRevision = -1;
    private float settled;
    private int autoReadSegment = -1;
    private boolean hover;
    private boolean visible;
    private boolean keyWasDown;
    private float pulse;
    private float lastAdvance;

    public SpeakerButton(InteractionDialogAPI dialog) {
        this.dialog = dialog;
    }

    public void attach(CustomPanelAPI panel) {
        this.panel = panel;
        try {
            tip = Global.getSettings().createLabel("", "graphics/fonts/victor10.fnt");
            tip.setColor(Misc.getTextColor());
            tip.setOpacity(0f);
            if (tip instanceof UIComponentAPI) panel.addComponent((UIComponentAPI) tip);
            else tip = null;
        } catch (Throwable t) {
            tip = null; // tooltips are a nicety
        }
        read();
    }

    /** Seconds of game time since advance() last ran; the watcher uses it to notice a detached button. */
    public float lastAdvance() {
        return lastAdvance;
    }

    public InteractionDialogAPI dialog() {
        return dialog;
    }

    // ---- actions ----

    /** Reads the latest portion aloud, or stops if already speaking. */
    public void toggle() {
        Speech speech = ReadAloud.speech();
        if (speech == null) return;
        if (speech.isSpeaking()) {
            speech.stop();
            return;
        }
        speakLatest();
    }

    private void speakLatest() {
        Speech speech = ReadAloud.speech();
        Config cfg = ReadAloud.config();
        if (speech == null || cfg == null) return;
        SpeechRequest r = new SpeechRequest("");
        DialogReader.describeSpeaker(dialog, r);
        List<String> choices = cfg.readOptions ? TextCleaner.choices(reader.options) : new ArrayList<String>();
        List<VoiceParts.Part> parts = buildParts(TextCleaner.paragraphs(tracker.segment()), choices, cfg,
                r.speakerGender, !r.speakerName.isEmpty());
        if (parts.isEmpty()) return;
        r.setParts(parts);
        speech.speak(r);
    }

    /**
     * Text split by voice (see {@link VoiceParts}), then the choices, each its own narrator
     * part so voices pause between them; replacements applied to every part.
     */
    public static List<VoiceParts.Part> buildParts(List<String> paragraphs, List<String> choices, Config cfg,
                                                   String gender, boolean hasSpeaker) {
        List<VoiceParts.Part> raw = new ArrayList<VoiceParts.Part>(VoiceParts.split(paragraphs, cfg.voiceMode, gender, hasSpeaker));
        for (String c : choices) raw.add(new VoiceParts.Part(VoiceParts.NARRATOR, c));
        List<VoiceParts.Part> out = new ArrayList<VoiceParts.Part>();
        for (VoiceParts.Part p : raw) {
            String t = TextCleaner.replace(p.text, cfg.replacements).trim();
            if (!t.isEmpty()) out.add(new VoiceParts.Part(p.role, t));
        }
        return out;
    }

    // ---- per frame ----

    @Override
    public void advance(float amount) {
        lastAdvance = 0f;
        try {
            Config cfg = ReadAloud.config();
            if (cfg == null) return;
            sinceRead += amount;
            if (sinceRead >= READ_INTERVAL) {
                sinceRead = 0f;
                read();
            }
            if (tracker.revision() != lastRevision) {
                lastRevision = tracker.revision();
                settled = 0f;
            } else {
                settled += amount;
            }
            // Text that shows up after a pause without an option pick (a contact chosen from
            // the comm directory, for one) is its own portion.
            if (settled >= PAUSE) tracker.markSettled();
            if (cfg.autoRead && !cfg.speechOff() && tracker.segmentId() != autoReadSegment
                    && settled >= SETTLE && !tracker.segment().isEmpty()) {
                autoReadSegment = tracker.segmentId();
                speakLatest();
            }
            hotkey(cfg);
            if (noticeLeft > 0f) noticeLeft -= amount;
            layout(cfg);
            pulse = (pulse + amount) % 2f;
        } catch (Throwable t) {
            ReadAloudLog.errorOnce("button-advance", "speaker button update failed", t);
        }
    }

    /** Called by the watcher every frame so it can tell whether advance() still runs. */
    public void tickWatch(float amount) {
        lastAdvance += amount;
    }

    private void read() {
        reader.read(dialog);
        tracker.update(reader.labels, reader.texts, reader.options);
        // With auto-read off when the dialog opens, the opening text counts as already handled,
        // so switching auto-read on later only reads text that arrives after that.
        Config cfg = ReadAloud.config();
        if (autoReadSegment == -1 && cfg != null && !cfg.autoRead) autoReadSegment = tracker.segmentId();
    }

    private void hotkey(Config cfg) {
        if (cfg.hotkey <= 0) return;
        boolean down = Keyboard.isKeyDown(cfg.hotkey);
        if (down && !keyWasDown && !cfg.speechOff()) toggle();
        keyWasDown = down;
    }

    private void layout(Config cfg) {
        Object tp = dialog.getTextPanel();
        boolean textShown = tp instanceof UIComponentAPI && ((UIComponentAPI) tp).getOpacity() > 0.5f;
        boolean speaking = ReadAloud.speech() != null && ReadAloud.speech().isSpeaking();
        visible = cfg.showButton && !cfg.speechOff() && textShown && (speaking || !tracker.segment().isEmpty());
        if (!visible) {
            if (tip != null) tip.setOpacity(0f);
            return;
        }
        PositionAPI pp = ((UIComponentAPI) tp).getPosition();
        float[] last = null;
        Object l = tracker.segmentLastLabel();
        if (l instanceof LabelAPI) {
            PositionAPI lp = ((LabelAPI) l).getPosition();
            last = new float[]{lp.getX(), lp.getY(), lp.getWidth(), lp.getHeight()};
        }
        float size = cfg.buttonSize;
        float[] at = Placement.place(new float[]{pp.getX(), pp.getY(), pp.getWidth(), pp.getHeight()}, last, size,
                Config.PLACE_CORNER.equals(cfg.buttonPlacement));
        panel.getPosition().setSize(size, size);
        panel.getPosition().setLocation(at[0], at[1]);
        if (tip != null) {
            if (noticeLeft > 0f && notice != null) {
                tip.setText(notice);
                tip.autoSizeToWidth(380f);
                tip.getPosition().inTL(size + 6f, 0f);
                tip.setOpacity(Math.min(1f, noticeLeft));
                return;
            }
            String key = cfg.hotkey > 0 ? " (" + Keyboard.getKeyName(cfg.hotkey) + ")" : "";
            String via = ReadAloud.speech() == null ? "" : ReadAloud.speech().lastBackend();
            tip.setText((speaking ? "Stop reading" : "Read aloud") + key + (via.isEmpty() ? "" : " - " + via));
            tip.autoSizeToWidth(300f);
            tip.getPosition().inTL(size + 6f, (size - tip.getPosition().getHeight()) / 2f);
            tip.setOpacity(hover ? 1f : 0f);
        }
    }

    private String notice;
    private float noticeLeft;
    static final float NOTICE_SECONDS = 12f;

    /** Shows a message next to the button for a while (the AI voice app isn't running, ...). */
    public void showNotice(String text) {
        notice = text;
        noticeLeft = NOTICE_SECONDS;
    }

    // ---- input ----

    @Override
    public void processInput(List<InputEventAPI> events) {
        if (panel == null) return;
        PositionAPI p = panel.getPosition();
        for (InputEventAPI e : events) {
            if (e.isConsumed() || !e.isMouseEvent()) continue;
            boolean inside = visible && p.containsEvent(e);
            if (e.isMouseMoveEvent()) hover = inside;
            if (!inside) continue;
            if (e.isLMBDownEvent()) {
                e.consume();
                try {
                    Global.getSoundPlayer().playUISound("ui_button_pressed", 1f, 1f);
                } catch (Throwable ignored) {
                }
                toggle();
            } else if (e.isLMBUpEvent() || e.isLMBEvent()) {
                e.consume();
            }
        }
    }

    @Override
    public void buttonPressed(Object buttonId) {}

    @Override
    public void positionChanged(PositionAPI position) {}

    @Override
    public void renderBelow(float alphaMult) {}

    // ---- drawing ----

    @Override
    public void render(float alphaMult) {
        if (!visible || panel == null) return;
        try {
            draw(alphaMult);
        } catch (Throwable t) {
            ReadAloudLog.errorOnce("button-render", "drawing the speaker button failed", t);
        }
    }

    private void draw(float alpha) {
        PositionAPI p = panel.getPosition();
        float x = p.getX(), y = p.getY(), s = p.getWidth();
        boolean speaking = ReadAloud.speech() != null && ReadAloud.speech().isSpeaking();
        Color fg = hover ? Misc.getBrightPlayerColor() : Misc.getBasePlayerColor();
        Color bg = Misc.getDarkPlayerColor();

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_LINE_SMOOTH);

            // backing square and frame
            color(Color.BLACK, 0.65f * alpha);
            rect(x, y, s, s);
            color(bg, (hover ? 1f : 0.8f) * alpha);
            rect(x + 1, y + 1, s - 2, s - 2);
            color(fg, alpha);
            GL11.glLineWidth(1f);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2f(x + 0.5f, y + 0.5f);
            GL11.glVertex2f(x + s - 0.5f, y + 0.5f);
            GL11.glVertex2f(x + s - 0.5f, y + s - 0.5f);
            GL11.glVertex2f(x + 0.5f, y + s - 0.5f);
            GL11.glEnd();

            // speaker body and cone
            color(fg, alpha);
            rect(x + s * 0.18f, y + s * 0.39f, s * 0.14f, s * 0.22f);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(x + s * 0.32f, y + s * 0.39f);
            GL11.glVertex2f(x + s * 0.50f, y + s * 0.22f);
            GL11.glVertex2f(x + s * 0.50f, y + s * 0.78f);
            GL11.glVertex2f(x + s * 0.32f, y + s * 0.61f);
            GL11.glEnd();

            if (speaking) {
                // animated waves while speaking (click to stop)
                float t = pulse / 2f;
                for (int i = 0; i < 3; i++) {
                    float phase = (t + i / 3f) % 1f;
                    arc(x + s * 0.50f, y + s * 0.5f, s * (0.12f + 0.28f * phase), fg, alpha * (1f - phase));
                }
            } else {
                arc(x + s * 0.50f, y + s * 0.5f, s * 0.17f, fg, alpha);
                arc(x + s * 0.50f, y + s * 0.5f, s * 0.30f, fg, alpha * 0.8f);
            }
        } finally {
            GL11.glPopAttrib();
        }
    }

    private static void color(Color c, float a) {
        GL11.glColor4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, Math.max(0f, Math.min(1f, a)));
    }

    private static void rect(float x, float y, float w, float h) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();
    }

    private static void arc(float cx, float cy, float r, Color c, float a) {
        color(c, a);
        GL11.glLineWidth(1.5f);
        GL11.glBegin(GL11.GL_LINE_STRIP);
        for (int i = -5; i <= 5; i++) {
            double ang = Math.toRadians(i * 10);
            GL11.glVertex2f(cx + (float) (Math.cos(ang) * r), cy + (float) (Math.sin(ang) * r));
        }
        GL11.glEnd();
    }
}
