# Read Aloud companion protocol

The mod and the companion app talk over HTTP on `127.0.0.1` (default port **47700**,
`companionPort` in the mod's `data/config/readaloud.json`). Anything that speaks this
protocol can replace the app.

## Rules

- Every request carries the header `X-ReadAloud: 1`. POSTs without it get 403. A web
  page can't add a custom header to a cross-site request without a CORS preflight,
  which the app never approves, so websites can't drive it.
- The `Host` header must be `127.0.0.1:<port>` or `localhost:<port>` (blocks DNS rebinding).
- Bodies are JSON, UTF-8. Responses are JSON with `"ok": true|false` and, on failure, `"error"`.

## Endpoints the mod uses

### `GET /api/status`

```json
{"ok": true, "version": "0.1.0", "ready": true, "speaking": false,
 "engine": "Kitten TTS", "engine_id": "kitten", "state": "ready", "error": ""}
```

`ready` means an engine is loaded and `/api/speak` will be accepted. `state` is one
of `starting`, `loading` (model loading or downloading), `ready`, `error`, `stopped`.
The mod polls this every 250 ms while the app is speaking, and every 60 s otherwise.
Background-started apps exit after 20 minutes without such contact.

### `POST /api/speak`

```json
{"text": "The station looms.\n\nWelcome,\n\nshe says.",
 "parts": [{"text": "The station looms.", "role": "narrator"},
           {"text": "Welcome,", "role": "female"},
           {"text": "she says.", "role": "narrator"}],
 "speaker": {"name": "Ana Kato", "gender": "female", "id": "...", "faction": "hegemony", "portrait": "graphics/portraits/..."},
 "dialog": "RuleBasedInteractionDialogPluginImpl",
 "source": "starsector"}
```

Replaces whatever is being spoken. Paragraphs are separated by a blank line.

`parts` (mod 0.1.0 and later) are spoken in order, each in the voice for its `role`:
`narrator` uses the engine's Narrator slot (`voices.default` in settings.json),
`male` / `female` the character slots (falling back to the Narrator). The game decides
the roles from its "Who reads what" setting (quotes / character / single). Bad
entries are skipped; an unknown role counts as `narrator`.

Without `parts` (older mods), `text` is read in the voice for `speaker.gender`
(`male`, `female` or `""` for the Narrator slot).
Answer: `{"ok": true, "id": 7}`, or `{"ok": false, "error": "engine is loading"}`.

**Voice pools.** If `use_pools` is on and the engine's `pools.male` / `pools.female` list
is not empty, `male` / `female` parts use the pool voice of this character instead of
the slot: the entry with the highest `hash(key + "\n" + voice)`, where `key` is
`speaker.id`, else `speaker.name` (no key: the slot), and `voice` is the voice name or,
for clips, the clip's file name. The hash is FNV-1a 64 over UTF-8 followed by
MurmurHash3's fmix64; `VoicePool.java` in the mod is the same, and both test suites check
the same values. Pool voices the loaded model doesn't have, and deleted clips, are skipped.

### `GET /api/voices`

What the game's settings page shows (polled every few seconds):

```json
{"ok": true, "version": "0.1.0", "engine_id": "kitten", "engine": "Kitten TTS",
 "state": "ready", "error": "", "voice_type": "name",
 "voices": ["Bella", "Jasper", "..."], "clips": ["C:\\...\\runtime\\voices\\anna.wav"],
 "slots": {"default": "Jasper", "male": "Bruno", "female": "Bella"},
 "pools": {"male": ["Hugo", "Leo"], "female": []}, "use_pools": true, "port": 47700}
```

`slots` are plain strings (a clip engine's slot is its clip path). The game changes
voices with `POST /api/settings` (`{"engines": {id: {"voices": {slot: value}}}}`, a clip
slot as `{"ref": path}`) and pools on or off with `{"use_pools": bool}`.

### `POST /api/stop`

Stops speaking at once. Body `{}`.

## Endpoints the setup page uses

| Endpoint | Body | Does |
|---|---|---|
| `GET /api/state?log=<n>` | | Everything the page shows; log lines after number `n` |
| `POST /api/settings` | `{"engine": id, "volume": 0-100, "port": n, "use_pools": bool, "engines": {id: {...}}}` (any subset) | Saves; reloads the engine if a load setting changed (voices and pools never do) |
| `POST /api/install` | `{"engine": id}` | Creates `runtime/venvs/<id>` and installs the engine with uv |
| `POST /api/cancel-install` | `{"engine": id}` | |
| `POST /api/uninstall` | `{"engine": id}` | Deletes the engine's venv (models stay) |
| `POST /api/upload?name=<file>` | raw audio bytes | Saves a voice clip into `runtime/voices/`; returns `{"path"}` |
| `POST /api/restart-engine` | `{}` | |
| `POST /api/shutdown` | `{}` | Quits the app |

## Engine workers

Each AI engine runs as a separate process in its own venv: `app/workers/worker_<id>.py`
(Windows' built-in voice: `sapi_worker.ps1`). One JSON object per line:

```
app -> worker  {"op":"load","settings":{...}}
               {"op":"synth","id":1,"text":"...","voice":<name or {"ref":path,"ref_text":"..."}>,"out":"C:/.../x.wav"}
               {"op":"quit"}
worker -> app  {"event":"loaded","voices":["..."],"info":{"device":"cuda"}}
               {"event":"done","id":1}
               {"event":"error","id":1,"message":"..."}      (id null = the load failed)
               {"event":"log","message":"..."}
```

The worker writes 16-bit PCM WAV. Its stdout carries protocol lines only:
`worker_common.py` points file descriptor 1 at stderr before any library loads, and
stderr goes to the app log.
