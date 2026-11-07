package lastai.readaloud.luna;

import lastai.readaloud.Config;
import lastai.readaloud.ReadAloud;
import lastai.readaloud.ReadAloudLog;
import lastai.readaloud.ReadAloudModPlugin;
import lastai.readaloud.speech.AiMenu;
import lastai.readaloud.speech.SystemVoices;
import lunalib.backend.ui.settings.LunaSettingsData;
import lunalib.backend.ui.settings.LunaSettingsLoader;
import lunalib.lunaSettings.LunaSettings;
import lunalib.lunaSettings.LunaSettingsListener;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * LunaLib (mod id "lunalib", CC BY 4.0) settings, from data/config/LunaSettings.csv.
 * Loaded by name only when LunaLib is enabled; core code never references LunaLib.
 */
public final class LunaBridge implements ReadAloudModPlugin.LunaHook {
    private boolean added;

    @Override
    public String getString(String field) {
        return LunaSettings.getString(ReadAloud.MOD_ID, field);
    }

    @Override
    public Boolean getBoolean(String field) {
        return LunaSettings.getBoolean(ReadAloud.MOD_ID, field);
    }

    @Override
    public Integer getInt(String field) {
        return LunaSettings.getInt(ReadAloud.MOD_ID, field);
    }

    @Override
    public void addVoiceMenus(List<String> choices) {
        if (added && getString(Config.FIELD_VOICE_NARRATOR) != null) return; // still there
        added = true;
        StringBuilder options = new StringBuilder();
        for (String c : choices) {
            if (options.length() > 0) options.append(',');
            options.append(c);
        }
        String o = options.toString();
        String mod = ReadAloud.MOD_ID;
        String auto = SystemVoices.AUTOMATIC;
        LunaSettings.SettingsCreator.addHeader(mod, Config.FIELD_VOICE_HEADER, "System voices", "");
        LunaSettings.SettingsCreator.addRadio(mod, Config.FIELD_VOICE_NARRATOR, "Narrator voice",
                "Reads the narration (and everything, with one reader). Automatic: your Windows default voice, or an"
                        + " installed one if the default is a slow cloud (\"Online\") voice. Used by the system voice; the"
                        + " companion app has its own voice settings.", auto, o, "");
        LunaSettings.SettingsCreator.addRadio(mod, Config.FIELD_VOICE_MALE, "Male character voice",
                "Reads what male characters (and characters of unknown gender) say. Automatic: an installed male voice.",
                auto, o, "");
        LunaSettings.SettingsCreator.addRadio(mod, Config.FIELD_VOICE_FEMALE, "Female character voice",
                "Reads what female characters say. Automatic: an installed female voice.", auto, o, "");
        LunaSettings.SettingsCreator.addBoolean(mod, Config.FIELD_VOICE_POOLS, "Voice pools",
                "Each character gets one of the installed voices of their gender, and keeps it every time they speak"
                        + " (picked from who they are; nothing is saved). The voices above are then used only when the game"
                        + " can't tell who is speaking. Few voices come with Windows, so the pools may be small; more are in"
                        + " Settings > Time & language > Speech. The companion app has its own pools.", false, "");
        LunaSettings.SettingsCreator.refresh(mod);
    }

    @Override
    public String modString(String modId, String field) {
        return LunaSettings.getString(modId, field);
    }

    @Override
    public Boolean modBoolean(String modId, String field) {
        return LunaSettings.getBoolean(modId, field);
    }

    /**
     * LunaLib can add fields while the game runs but not change or remove them, so this takes
     * the old ones out of its field list (LunaSettingsLoader.getSettingsData(), a plain list),
     * adds the new ones, and sets their values to what the app reports. Only our own mods'
     * fields with the given ids are touched.
     */
    @Override
    public void setFields(String modId, List<AiMenu.Field> fields, List<String> managed) {
        List<LunaSettingsData> data = LunaSettingsLoader.getSettingsData();
        for (Iterator<LunaSettingsData> it = data.iterator(); it.hasNext(); ) {
            LunaSettingsData d = it.next();
            if (modId.equals(d.getModID()) && managed.contains(d.getFieldID())) it.remove();
        }
        for (AiMenu.Field f : fields) {
            if ("Header".equals(f.type)) {
                LunaSettings.SettingsCreator.addHeader(modId, f.id, f.text, "");
            } else if ("Text".equals(f.type)) {
                LunaSettings.SettingsCreator.addText(modId, f.id, f.text, "");
            } else if ("Radio".equals(f.type)) {
                LunaSettings.SettingsCreator.addRadio(modId, f.id, f.name, f.text, f.value, f.options, "");
            } else if ("Boolean".equals(f.type)) {
                LunaSettings.SettingsCreator.addBoolean(modId, f.id, f.name, f.text, Boolean.parseBoolean(f.value), "");
            }
        }
        LunaSettings.SettingsCreator.refresh(modId);
        // refresh() reloads the values saved earlier; the app's current choices win.
        Map<String, ? extends JSONObject> values = LunaSettingsLoader.getSettings();
        JSONObject v = values == null ? null : values.get(modId);
        if (v == null) return;
        for (AiMenu.Field f : fields) {
            try {
                if ("Radio".equals(f.type)) v.put(f.id, f.value);
                else if ("Boolean".equals(f.type)) v.put(f.id, Boolean.parseBoolean(f.value));
            } catch (Exception ignored) {
                // org.json only refuses non-finite numbers
            }
        }
    }

    @Override
    public boolean hasField(String modId, String field) {
        for (LunaSettingsData d : new java.util.ArrayList<LunaSettingsData>(LunaSettingsLoader.getSettingsData())) {
            if (modId.equals(d.getModID()) && field.equals(d.getFieldID())) return true;
        }
        return false;
    }

    @Override
    public void listenMod(final String modId, final Runnable onChange) {
        LunaSettings.addSettingsListener(new LunaSettingsListener() {
            @Override
            public void settingsChanged(String changed) {
                if (!modId.equals(changed)) return;
                try {
                    onChange.run();
                } catch (Throwable t) {
                    ReadAloudLog.errorOnce("luna-listener:" + modId, "applying changed settings of " + modId + " failed", t);
                }
            }
        });
    }

    @Override
    public void listen(final Runnable onChange) {
        LunaSettings.addSettingsListener(new LunaSettingsListener() {
            @Override
            public void settingsChanged(String modId) {
                if (!ReadAloud.MOD_ID.equals(modId)) return;
                try {
                    onChange.run();
                } catch (Throwable t) {
                    ReadAloudLog.errorOnce("luna-listener", "applying changed settings failed; old values are kept", t);
                }
            }
        });
    }
}
