package lastai.readaloud.speech;

import lastai.readaloud.text.VoiceParts;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What to say, in which voice ("parts", each with a role: narrator, male or female),
 * plus who is talking in the dialog.
 */
public final class SpeechRequest {
    /** All parts' text, joined; what older companion versions read. */
    public String text;
    private List<VoiceParts.Part> parts;
    /** Display name of the person in the dialog, or "". */
    public String speakerName = "";
    /** "male", "female" or "" when unknown or not a person. */
    public String speakerGender = "";
    public String speakerId = "";
    public String factionId = "";
    public String portrait = "";
    /** Simple class name of the dialog plugin, to help with per-dialog tuning later. */
    public String dialog = "";

    /** One narrator part. */
    public SpeechRequest(String text) {
        String t = text == null ? "" : text;
        setParts(t.trim().isEmpty() ? Collections.<VoiceParts.Part>emptyList()
                : Collections.singletonList(new VoiceParts.Part(VoiceParts.NARRATOR, t)));
    }

    public SpeechRequest(List<VoiceParts.Part> parts) {
        setParts(parts);
    }

    public void setParts(List<VoiceParts.Part> p) {
        parts = Collections.unmodifiableList(new ArrayList<VoiceParts.Part>(p));
        StringBuilder sb = new StringBuilder();
        for (VoiceParts.Part part : parts) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(part.text);
        }
        text = sb.toString();
    }

    public List<VoiceParts.Part> parts() {
        return parts;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("text", text);
            JSONArray arr = new JSONArray();
            for (VoiceParts.Part p : parts) {
                JSONObject j = new JSONObject();
                j.put("text", p.text);
                j.put("role", p.role);
                arr.put(j);
            }
            o.put("parts", arr);
            JSONObject sp = new JSONObject();
            sp.put("name", speakerName);
            sp.put("gender", speakerGender);
            sp.put("id", speakerId);
            sp.put("faction", factionId);
            sp.put("portrait", portrait);
            o.put("speaker", sp);
            o.put("dialog", dialog);
            o.put("source", "starsector");
        } catch (Exception e) {
            // org.json only throws for non-finite numbers; nothing here can trigger it
        }
        return o;
    }
}
