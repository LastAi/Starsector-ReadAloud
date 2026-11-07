# Read Aloud companion app

Gives the Read Aloud mod natural AI voices. Part of the optional Read Aloud: AI Voices
mod; without it Read Aloud uses your operating system's voice.

## Start it

- **Windows:** double-click `start.cmd`.
- **Linux / macOS:** `sh start.sh`.

The first start downloads [uv](https://github.com/astral-sh/uv) (checksum-verified)
and a private copy of Python, about 50 MB in total. Then a setup page opens in your
browser at <http://127.0.0.1:47700/>. Nothing is installed system-wide and you don't
need Python. Everything lives in `companion/runtime/`; delete that folder to remove
it all.

After the first start, the game starts the app in the background whenever it needs
it (turn that off with "Start the companion app when needed" / `companionAutostart` in
Read Aloud's settings). A background app quits after 20 minutes without the game. None
of this needs a game restart: set the app up, quit it or change it while you play.

## Engines

| Engine | Good for | Download | Notes |
|---|---|---|---|
| System voice | Works at once | none | Windows SAPI, macOS `say`, Linux `espeak-ng` |
| **Kitten TTS** | Recommended. Many built-in voices, quick | ~3 GB libraries + 25 MB–1 GB model | CPU or NVIDIA GPU |
| F5-TTS | Cloning a voice from a short clip | ~4 GB + 1.4 GB model | Needs `ffmpeg` on PATH. Model is non-commercial |
| Chatterbox | Expressive cloning | ~4 GB + 3 GB model | Unmaintained by its authors since 2026 |

Press **Install** on an engine, then **Use**. The model downloads the first time it
loads, which can take several minutes; the log shows progress.

On the setup page, each engine has a Narrator voice plus voices for male and female
characters. The mod sends who reads each part and the gender of the person you're
talking to. Cloning engines take a 5–10 second clip of one person speaking with no
music. F5-TTS also wants the clip's words; leave that empty to have it transcribed.

**Voice pools:** under Male and Female characters, tick the voices (or clips) that
characters of that gender may get. Each character then keeps one of them every time
they speak, picked from the character's id, so nothing is saved and adding a voice only
moves the characters that get the new one. Empty pool: everyone uses the slot's voice.
The "Voice pools" switch turns them all off. In **Try it**, type a name to hear which
pool voice that name gets.

The game's LunaLib settings have a page for this mod that shows the app's status and
voices and can change them; this page has everything.

## Troubleshooting

- **The game uses the robot voice:** the app isn't running, or no engine is ready
  yet. The message next to the speaker button says which; the status is also on the
  mod's settings page and in the top right of the setup page.
- **No sound at all:** press **Test sound** on the setup page. No beep means Windows
  sends the app's sound somewhere else: check "Python" in the Volume Mixer.
- **Moved the mod folder:** the app repairs its Python environments on the next start
  (the log says "Checked the ... environment's launchers"). If an engine still fails,
  press Reinstall.
- **An install failed:** the log says why. Press Install again to retry. Chatterbox
  needs a CUDA 12.4/12.6 PyTorch build and retries those automatically.
- **The port is taken:** change it on the setup page and set the same
  `companionPort` in Read Aloud's `data/config/readaloud.json`.
- Logs: `runtime/companion.log` (and `runtime/console.log` when started by the game).

## For developers

- `PROTOCOL.md` describes the HTTP API and the worker protocol.
- `bash test.sh` runs the tests (any Python 3.10+, standard library only).
- `app/server.py --help` lists the options (`--test` adds a beep engine, `--silent`
  skips audio playback).
