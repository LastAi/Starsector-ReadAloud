package lastai.readaloud.speech;

import lastai.readaloud.Config;
import lastai.readaloud.text.VoiceParts;

import java.util.ArrayList;
import java.util.List;

/**
 * The samples played when settings are saved, so voices can be tried from the menu
 * (LunaLib has no buttons). Pure, so it is unit tested.
 */
public final class VoiceSamples {
    static final String NARRATOR = "This is the narrator.";
    static final String SINGLE = "This voice reads everything.";
    static final String MALE = "This is the voice of male characters.";
    static final String FEMALE = "This is the voice of female characters.";

    private VoiceSamples() {}

    /** Samples of these roles' voices (the AI voice page), or null for none. */
    public static SpeechRequest forRoles(List<String> roles, Config now) {
        if (roles == null || roles.isEmpty() || now == null || now.speechOff() || Config.PREVIEW_OFF.equals(now.voicePreview)) return null;
        List<VoiceParts.Part> parts = new ArrayList<VoiceParts.Part>();
        for (String r : roles) {
            if (VoiceParts.MALE.equals(r)) parts.add(new VoiceParts.Part(r, MALE));
            else if (VoiceParts.FEMALE.equals(r)) parts.add(new VoiceParts.Part(r, FEMALE));
            else parts.add(new VoiceParts.Part(VoiceParts.NARRATOR, NARRATOR));
        }
        return new SpeechRequest(parts);
    }

    /** What to play after the settings changed from {@code old} to {@code now}, or null for nothing. */
    public static SpeechRequest forChange(Config old, Config now) {
        if (now == null || now.speechOff() || Config.PREVIEW_OFF.equals(now.voicePreview)) return null;
        boolean single = VoiceParts.MODE_SINGLE.equals(now.voiceMode);
        boolean all = Config.PREVIEW_ALL.equals(now.voicePreview) || old == null
                || old.systemRate != now.systemRate || old.systemVolume != now.systemVolume
                || !old.voiceMode.equals(now.voiceMode);
        List<VoiceParts.Part> parts = new ArrayList<VoiceParts.Part>();
        if (all || !old.systemVoice.equals(now.systemVoice)) {
            parts.add(new VoiceParts.Part(VoiceParts.NARRATOR, single ? SINGLE : NARRATOR));
        }
        if (!single && (all || !old.systemVoiceMale.equals(now.systemVoiceMale))) {
            parts.add(new VoiceParts.Part(VoiceParts.MALE, MALE));
        }
        if (!single && (all || !old.systemVoiceFemale.equals(now.systemVoiceFemale))) {
            parts.add(new VoiceParts.Part(VoiceParts.FEMALE, FEMALE));
        }
        return parts.isEmpty() ? null : new SpeechRequest(parts);
    }
}
