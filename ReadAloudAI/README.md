# Read Aloud: AI Voices

Optional add-on for [Read Aloud](../ReadAloud/README.md) (mod id `lastai_readaloud_ai`,
game 0.98a). It holds the **companion app**, which gives Read Aloud natural AI voices:
Kitten TTS, F5-TTS (voice cloning) or Chatterbox. Without this mod, Read Aloud reads
with your system voice.

The mod has no code of its own: Read Aloud finds it in the game's mod list, starts its
app when needed and talks to it over HTTP on 127.0.0.1.

## Setting it up

1. Enable **Read Aloud** and **Read Aloud: AI Voices** in the launcher. Enabling or
   disabling a mod needs a game restart; nothing else here does.
2. Run `companion/start.cmd` (Windows) or `sh companion/start.sh` (Linux, macOS) once.
   A setup page opens in your browser; install an engine there (Kitten TTS is the
   recommended one). See [companion/README.md](companion/README.md).
3. Play. The game starts the app in the background when it needs it (at the title
   screen, when a save loads, or on a read), so you don't have to keep it open.

If the app isn't running or isn't ready when something is read, the system voice
reads instead, and a message next to the speaker button says why and what to do. You
never need to restart the game for the app: set it up, quit it or change it any time.

## In-game settings (LunaLib)

With LunaLib, this mod has its own page in Mod Settings. Read Aloud fills it in while
the game runs, from what the app reports:

- **Status:** running and ready, loading, failed (with the reason), not running, not set
  up, or the mod not used because Read Aloud's Voice setting is "system".
- **Voices:** the engine, who reads what (narrator, male and female characters) and the
  voice pools.
- **Narrator / Male / Female voice** pickers (up to 20 voices each; the app's page has
  them all) and a **Voice pools** switch. Saving sends the change to the app and plays a
  sample in the new voice.

The page is rebuilt whenever the app's state changes; reopen the menu to see it.
Read Aloud's own page also ends with an "AI voices" status line.

## Voice pools

On the app's page, tick voices into the male and female pools. Each character then gets
one voice of their gender and keeps it every time they speak. The voice is picked from
the character's id, so nothing is stored in your save, and adding a voice to a pool only
moves the characters that get the new voice.

## Files

- `companion/`: the app (Python, run through uv). Everything it downloads (uv, Python,
  engines, models, about 6 GB with Kitten TTS) lives in `companion/runtime/`. Delete
  that folder to remove it all. If you move the mod folder, the app repairs its Python
  environments on the next start, without downloading anything.
- `data/config/LunaSettings.csv`: the fixed top of the settings page.

Tests: `bash companion/test.sh` (any Python 3.10+). Read Aloud's `bash build.sh --all`
runs them too, plus live tests of the game's client against this app.
