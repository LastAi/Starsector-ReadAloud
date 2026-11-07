# Read Aloud for Starsector (Alpha)

Hear Starsector's dialogs read aloud. A **speaker button** appears in interaction dialogs:
events, bar encounters, station contacts, fleet encounters and exploration. Click it, or
press **F8**, to hear the latest text. Click again to stop.

> **Alpha.** It works on the author's machine, but it hasn't had much testing elsewhere
> yet, especially on Linux and macOS. Please report problems in
> [Issues](../../issues), with `starsector.log` attached (lines start with `[ReadAloud]`)
> and, for AI voices, `ReadAloudAI/companion/runtime/companion.log`.

This repo holds two mods:

| Mod | Folder | What it does |
|---|---|---|
| **Read Aloud** (`lastai_readaloud`) | [`ReadAloud/`](ReadAloud/README.md) | The speaker button. Reads with your system voice (Windows SAPI, macOS `say`, Linux `espeak-ng`). Works on its own. |
| **Read Aloud: AI Voices** (`lastai_readaloud_ai`) | [`ReadAloudAI/`](ReadAloudAI/README.md) | Optional add-on: natural AI voices (Kitten TTS, F5-TTS, Chatterbox) through a companion app that runs next to the game. |

## Features

- A narrator plus male and female character voices. By default, text in quotation marks
  is read in the character's voice and the rest by the narrator.
- Optional voice pools: each character gets their own voice and keeps it. Nothing is
  stored in your save.
- Optional auto-read of new text, and reading the choices one by one.
- In-game settings with [LunaLib](https://fractalsoftworks.com/forum/index.php?topic=25658.0)
  (optional), including voice pickers with samples and a status page for the AI voice app.
- Safe to add to or remove from a save.

## Install

Requires Starsector **0.98a**. LunaLib is optional (in-game settings).

1. Download the zips from [Releases](../../releases):
   `ReadAloud-*.zip`, and `ReadAloudAI-*.zip` if you want AI voices.
2. Unzip them into `Starsector/mods/` and enable them in the launcher.
3. **AI voices only:** run `mods/ReadAloudAI/companion/start.cmd` (Windows) or
   `sh mods/ReadAloudAI/companion/start.sh` (Linux, macOS) once. It downloads its own
   copy of [uv](https://github.com/astral-sh/uv) and Python (about 50 MB) and opens a
   setup page in your browser, where you install an engine. Kitten TTS is recommended
   (about 3 GB of libraries; an NVIDIA GPU helps but isn't needed). After that, the game
   starts the app by itself. Nothing is installed system-wide, and you don't need Python;
   everything goes into `ReadAloudAI/companion/runtime/`.

Enabling or disabling a mod needs a game restart. Setting up, quitting or changing the
AI voice app never does.

## Building from source

```
bash ReadAloud/build.sh          # the mod's jar + Java tests (JDK 17+)
bash ReadAloud/build.sh --all    # also the companion app tests and live tests (Python 3.10+)
bash ReadAloudAI/companion/test.sh
```

`build.sh` reads the game's jars from `STARSECTOR_CORE` (default
`/d/Starsector/starsector-core`) and builds the LunaLib bridge when LunaLib and LazyLib
are in the game's mods folder. The built jar is committed, so the `ReadAloud` folder works
as a mod as cloned.

## License and credits

The code in this repo is under the [MIT License](LICENSE).

- LunaLib (optional) by Lukas04, CC BY 4.0. LazyLib by LazyWizard. Neither is included;
  they're only used when installed.
- The companion app downloads uv (Astral, MIT/Apache-2.0) and the engines you choose,
  each under its own license, shown on the app's setup page: Kitten TTS (Apache-2.0; the
  kitten-tts-2 model uses the Stellon Labs Community License), F5-TTS (code MIT; the
  pretrained model is **CC-BY-NC, non-commercial**), Chatterbox (MIT).

## A note from the author

Hi, I'm LastAi. I've been a longtime fan of Starsector; it's one of my favorite games.
However, I'm dyslexic. *Very* dyslexic, and Starsector is a very text-heavy game.

Games like Stellaris are also very text-heavy, and discovering the screen reader option
in that game changed the way I look at games. I've been thinking about how I might make
a mod that brings that idea to Starsector, and now I finally have a way to bring it to
life.
