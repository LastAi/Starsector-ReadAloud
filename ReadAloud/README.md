# Read Aloud

A Starsector mod (id `lastai_readaloud`, game 0.98a) that adds a **speaker button** to
interaction dialogs: events, bar encounters, station contacts, fleet encounters,
exploration. Click it, or press **F8**, to hear the latest text read aloud. Click
again to stop.

- Works out of the box with your system voice (Windows SAPI, macOS `say`, Linux
  `espeak-ng` / `spd-say`).
- For natural AI voices (Kitten TTS, F5-TTS, Chatterbox), add the optional
  **Read Aloud: AI Voices** mod (`mods/ReadAloudAI`, id `lastai_readaloud_ai`) and run its
  companion app once: `companion/start.cmd` (Windows) or `companion/start.sh`. See
  `../ReadAloudAI/README.md`. Without it, everything works with the system voice.
- **Voices:** a narrator plus a male and a female character voice. By default, text in
  quotation marks is read by the character and the rest by the narrator. Or the character
  reads everything they're part of, or one reader reads it all.
- **Voice pools** (optional): each character gets one voice from a pool of their gender
  and keeps it every time they speak. Nothing is saved: the voice comes from a hash of
  the character's id (see below).
- Optional: **auto-read** each new block of text, **read the choices** too (one by one),
  and pronunciation fixes.
- Settings: `data/config/readaloud.json`, or in game through LunaLib if you have it. With
  LunaLib, the system voices are picked from lists of your installed voices, and saving
  plays a sample of the voices you changed.
- Safe to add to or remove from a save: nothing is stored in it.

## What "latest text" means

When you pick an option, the game prints that option into the dialog and then the
response. The mod reads everything after the last option you picked, so you hear the
new response and not the whole conversation again. When a dialog opens, you hear its
opening text. Text that appears after a pause without an option pick (a contact
chosen in the comm directory) also counts as new.

## Who reads what

`text/VoiceParts.java` splits the text into parts, each with a role: `narrator`,
`male` or `female` (the person in the dialog; unknown gender counts as male).
Settings "Who reads what" / `voiceMode`:

| Mode | Narrator reads | Character reads |
|---|---|---|
| `quotes` (default) | everything outside quotation marks | text in "straight" or “curly” quotes |
| `character` | everything when nobody is on the line | everything when a person is |
| `single` | everything | nothing |

A quote spanning paragraphs (each one reopened, closed only at the end) stays with the
character. The system voice speaks all parts in one process. "Automatic" voices: the
narrator gets your default voice, characters an installed voice of their gender
(Windows), or espeak's `en+m3` / `en+f3` (Linux). Windows cloud voices ("... Online")
are only used when chosen by name: they wait about 5 seconds before every part.
The companion app maps the roles to its Narrator / Male / Female voice slots.

## Voice pools

With pools on, a character's voice is the pool entry with the highest hash of
(character key, voice name): rendezvous hashing, in `speech/VoicePool.java`. The key is
the game's person id (fixed for story characters; generic contacts and commanders get
one when they are created, and it is kept in the save), or the name when there is no
id. So the same character always gets the same voice, without the mod saving anything,
and adding or removing a voice only moves the characters that had that voice. The hash
is FNV-1a 64 with MurmurHash3's finalizer; the companion app (`server.py` `pool_pick`)
uses the same one, and tests on both sides check the same values.

- System voices: "Voice pools" in the LunaLib menu (`systemVoicePools`). The pools hold
  every installed voice of that gender (English first, no cloud voices, not the
  narrator's voice if others remain), or only the names in `systemPoolMale` /
  `systemPoolFemale`. Windows usually has only two or three English voices.
- AI voices: tick the voices of each pool on the companion app's page.

The chosen male / female voice is still used for characters the game can't tell apart.

## AI voices: the add-on mod

The companion app lives in the separate, optional mod `mods/ReadAloudAI`. Read Aloud
doesn't depend on it: it finds the add-on through the game's mod list, starts its
`companion/start.cmd` / `start.sh` when needed, and talks to the app over HTTP.

- **Restarts:** only enabling or disabling the add-on (any mod) needs a game restart.
  Setting the app up, starting, quitting or reconfiguring it doesn't: the game asks the
  app before each read and tries to start it again at most once a minute.
- **Telling the player:** when a read can't use the app, a message says why and whether a
  restart is needed (next to the speaker button and in the campaign message log, at
  most once per 10 minutes per reason), and another says when AI voices are back.
- **Settings pages (LunaLib):** Read Aloud's page ends with an "AI voices" status line.
  The add-on gets its own page, built while the game runs from what the app reports
  (`GET /api/voices`, checked every 5 seconds by `speech/CompanionMonitor.java`): status,
  engine, the voices in use and the pools, pickers for the three voices and a pools
  switch. Saving it sends the changes to the app and plays a sample in the AI voice.
  LunaLib can't change or remove fields, so `LunaBridge.setFields` swaps our entries in
  its field list; pages are rebuilt only when something changed.

## How it works

| Part | File |
|---|---|
| Notices dialogs opening; adds the button to each | `src/.../dialog/DialogWatcher.java` |
| The button: drawing, clicks, hotkey, auto-read | `dialog/SpeakerButton.java` |
| Reads the dialog's paragraphs and options | `dialog/DialogReader.java`, `dialog/UiTree.java` |
| Finds the latest portion | `text/SegmentTracker.java` |
| Speech: companion app over HTTP, or the system voice | `speech/*.java` |

The API can write dialog text but not read it back. The text panel's implementation,
though, is an ordinary UI panel, and every UI panel has a public `getChildrenCopy()`
(not in the API, but not obfuscated). `UiTree` walks it with `MethodHandles`
(`java.lang.reflect` is blocked for mods). If a game update breaks that, the problem
is logged once and the button stays hidden; nothing else is affected.

The game blocks mods from `java.io` file classes, `java.nio.file`, reflection,
`javax.script` and `java.util.prefs`. The mod only needs sockets (`java.net`) and
processes (`ProcessBuilder`), which are allowed. A build test checks every shipped
class against the game's actual blocklist.

## Build and test

```
bash build.sh          # jar + Java tests (needs a JDK 17+)
bash build.sh --all    # also the companion tests (../ReadAloudAI) and live mod <-> app tests (needs Python 3.10+)
```

The LunaLib bridge (`src-optional/lunalib`) is built when LunaLib and LazyLib are
found in the game's mods folder.

## Credits and licenses

- LunaLib (optional) by Lukas04, CC BY 4.0.
- The companion app (in the AI Voices add-on) downloads uv (Astral, MIT/Apache-2.0) and
  the engines you choose. Each has its own license, shown on the setup page. Notably,
  the F5-TTS model is non-commercial.
