"""Read Aloud companion app: a local text-to-speech server for the Starsector mod.

Run through start.cmd / start.sh. Listens on 127.0.0.1 only. The game sends text to
/api/speak; the setup page at http://127.0.0.1:<port>/ installs and configures engines.
Standard library only; the AI engines run in their own environments (engines.py).
"""

import argparse
import collections
import json
import os
import queue
import re
import sys
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import audio  # noqa: E402
import engines  # noqa: E402
from textsplit import split_text  # noqa: E402

VERSION = '0.1.0'
APP_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_RUNTIME = os.path.join(os.path.dirname(APP_DIR), 'runtime')
DEFAULT_PORT = 47700
HEADER = 'X-ReadAloud'
MAX_TEXT = 20000
MAX_UPLOAD = 25 * 1024 * 1024


class Log:
    """Ring buffer for the setup page, mirrored to runtime/companion.log."""

    def __init__(self, path, echo):
        self.lines = collections.deque(maxlen=800)
        self.seq = 0
        self.lock = threading.Lock()
        self.path, self.echo = path, echo
        if os.path.isfile(path) and os.path.getsize(path) > 2 * 1024 * 1024:
            try:
                os.replace(path, path + '.old')
            except OSError:
                pass

    def __call__(self, msg):
        line = time.strftime('%H:%M:%S ') + str(msg)
        with self.lock:
            self.seq += 1
            self.lines.append((self.seq, line))
            try:
                with open(self.path, 'a', encoding='utf-8') as f:
                    f.write(line + '\n')
            except OSError:
                pass
        if self.echo:
            try:
                print(line, flush=True)
            except (OSError, ValueError):
                self.echo = False

    def since(self, n):
        with self.lock:
            return [{'n': s, 'text': t} for s, t in self.lines if s > n]


class Settings:
    def __init__(self, path, show_hidden):
        self.path = path
        self.show_hidden = show_hidden
        self.data = {'engine': 'system', 'volume': 100, 'port': DEFAULT_PORT, 'use_pools': True, 'engines': {}}
        try:
            with open(path, encoding='utf-8') as f:
                saved = json.load(f)
            if isinstance(saved, dict):
                self.data.update({k: v for k, v in saved.items() if k in self.data})
        except (OSError, ValueError):
            pass
        for eid, spec in engines.ENGINES.items():
            defaults = dict(spec['defaults'], pools={'male': [], 'female': []})
            self.data['engines'][eid] = merge(defaults, self.data['engines'].get(eid) or {})
            pools = self.data['engines'][eid]['pools']
            for g in ('male', 'female'):
                pools[g] = clean_pool(pools.get(g))
        if self.data['engine'] not in engines.public_specs(show_hidden):
            self.data['engine'] = 'system'

    def engine(self, eid=None):
        return self.data['engines'][eid or self.data['engine']]

    def save(self):
        tmp = self.path + '.tmp'
        with open(tmp, 'w', encoding='utf-8') as f:
            json.dump(self.data, f, indent=2)
        os.replace(tmp, self.path)


def merge(defaults, saved):
    """Saved values over defaults, one level of nested dicts deep (voices)."""
    out = json.loads(json.dumps(defaults))
    for k, v in saved.items():
        if isinstance(out.get(k), dict) and isinstance(v, dict):
            for k2, v2 in v.items():
                if isinstance(out[k].get(k2), dict) and isinstance(v2, dict):
                    out[k][k2].update(v2)
                else:
                    out[k][k2] = v2
        else:
            out[k] = v
    return out


def pick_voice(engine_settings, gender):
    voices = engine_settings.get('voices') or {}
    for key in (gender, 'default'):
        v = voices.get(key) if key else None
        if isinstance(v, dict):
            if v.get('ref'):
                return v
        elif v:
            return v
    return voices.get('default')


def clean_pool(raw):
    """A pool as saved: a list of distinct non-empty strings (voice names, or clip paths)."""
    out = []
    for v in raw if isinstance(raw, list) else []:
        if isinstance(v, str) and v.strip() and v.strip() not in out:
            out.append(v.strip())
    return out


M64 = 0xFFFFFFFFFFFFFFFF


def fnv64(s):
    """FNV-1a, 64 bit, over UTF-8, then MurmurHash3's fmix64 so the last bytes reach the high bits.
    Same as VoicePool.java, so both sides pick alike."""
    h = 0xcbf29ce484222325
    for b in s.encode('utf-8'):
        h = ((h ^ b) * 0x100000001b3) & M64
    h ^= h >> 33
    h = (h * 0xff51afd7ed558ccd) & M64
    h ^= h >> 33
    h = (h * 0xc4ceb9fe1a85ec53) & M64
    h ^= h >> 33
    return h


def pool_identity(entry):
    """What a pool entry is hashed by: a voice name, or a clip's file name (so moving the folder keeps the picks)."""
    return os.path.basename(entry.replace('\\', '/')) if ('/' in entry or '\\' in entry) else entry


def pool_pick(key, pool):
    """The voice a character keeps: the pool entry with the highest hash of (character, voice).

    Rendezvous hashing: adding or removing a voice only moves the characters that had that
    voice; everyone else keeps theirs. None if there is no key or the pool is empty.
    """
    if not key or not pool:
        return None
    best, best_score = None, -1
    for v in pool:
        score = fnv64(key + '\n' + pool_identity(v))
        if score > best_score:
            best, best_score = v, score
    return best


def speaker_key(speaker):
    """Who a character is, for pools: the game's person id, else the name."""
    return str(speaker.get('id') or '').strip() or str(speaker.get('name') or '').strip()


ROLES = ('narrator', 'male', 'female')
MAX_PAUSE_MS = 5000


def part_pause(p):
    """Seconds of silence before a part, from its optional "pause" (milliseconds); 0 if absent or bad."""
    ms = p.get('pause')
    if isinstance(ms, bool) or not isinstance(ms, (int, float)) or ms != ms or ms <= 0:
        return 0.0
    return min(ms, MAX_PAUSE_MS) / 1000.0


def request_parts(raw):
    """[(text, role, pause seconds)] from a request's "parts"; bad entries are skipped, the total is capped at MAX_TEXT."""
    out, left = [], MAX_TEXT
    if not isinstance(raw, list):
        return out
    for p in raw:
        if not isinstance(p, dict) or not isinstance(p.get('text'), str) or left <= 0:
            continue
        t = p['text'][:left].strip()
        if not t:
            continue
        role = p.get('role') if p.get('role') in ROLES else 'narrator'
        out.append((t, role, part_pause(p)))
        left -= len(t)
    return out


class Speaker:
    """Splits text into chunks; synthesizes ahead while the previous chunk plays."""

    def __init__(self, app):
        self.app = app
        self.gen = 0
        self.lock = threading.Lock()
        self.play_q = queue.Queue()
        self.producing = 0  # generation currently synthesizing, 0 = none
        self.playing = False
        self.last_error = ''
        threading.Thread(target=self._play_loop, daemon=True, name='player').start()

    @property
    def speaking(self):
        synthesizing = self.producing != 0 and self.producing == self.gen
        return synthesizing or self.playing or not self.play_q.empty()

    def speak(self, text, voice):
        return self.speak_parts([(text, voice)])

    def speak_parts(self, parts):
        """parts: [(text, voice)] or [(text, voice, pause seconds)], spoken in order, each pause
        played as silence before its part. Only the very first chunk is kept short."""
        chunks = []
        for part in parts:
            text, voice = part[0], part[1]
            # A pause only between parts: never before the first thing said.
            pause = part[2] if len(part) > 2 and chunks else 0.0
            first_max = 100 if not chunks else 280
            for i, c in enumerate(split_text(text, first_max=first_max)):
                chunks.append((c, voice, pause if i == 0 else 0.0))
        with self.lock:
            self.gen += 1
            g = self.gen
            self._drain()
            self.app.player.stop()
            if not chunks:
                return g
            self.producing = g
        threading.Thread(target=self._produce, args=(g, chunks), daemon=True, name='synth-%d' % g).start()
        return g

    def stop(self):
        with self.lock:
            self.gen += 1
            self._drain()
            self.app.player.stop()

    def _drain(self):
        while True:
            try:
                _, path = self.play_q.get_nowait()
                if isinstance(path, str):
                    audio.safe_remove(path)
            except queue.Empty:
                return

    def _produce(self, g, chunks):
        engine = self.app.engine
        try:
            for i, (chunk, voice, pause) in enumerate(chunks):
                if g != self.gen:
                    return
                out = os.path.join(self.app.paths.tmp, 'speech-%d-%d.wav' % (g, i))
                t0 = time.time()
                engine.synth(chunk, voice, out)
                audio.set_volume(out, int(self.app.settings.data.get('volume', 100)))
                if i == 0:
                    self.app.log('Speaking %d chunk(s); first ready in %.1f s.' % (len(chunks), time.time() - t0))
                if g != self.gen:
                    audio.safe_remove(out)
                    return
                if pause > 0:
                    self.play_q.put((g, float(pause)))  # silence, played before the clip
                self.play_q.put((g, out))
        except Exception as e:  # noqa: BLE001
            self.last_error = str(e)
            self.app.log('Speech failed: %s' % e)
        finally:
            if self.producing == g:
                self.producing = 0

    def test_sound(self):
        """A one-second beep through the same player, to tell engine problems from sound-output ones."""
        path = os.path.join(self.app.paths.tmp, 'test-beep-%d.wav' % int(time.time() * 1000))
        audio.tone(path, seconds=1.0, freq=660.0)
        with self.lock:
            self.gen += 1
            g = self.gen
            self._drain()
            self.app.player.stop()
            self.play_q.put((g, path))
        self.app.log('Test sound: a 1-second beep through %s.' % self.app.player.backend)
        return g

    def _play_loop(self):
        played_gen, clips, seconds = 0, 0, 0.0
        while True:
            g, path = self.play_q.get()
            if not isinstance(path, str):  # a pause: wait, unless a new request or stop comes in
                self.playing = g == self.gen
                end = time.time() + path
                while self.playing and g == self.gen and time.time() < end:
                    time.sleep(0.02)
                self.playing = False
                continue
            try:
                if g == self.gen:
                    self.playing = True
                    length = audio.duration(path)
                    if g != played_gen:
                        played_gen, clips, seconds = g, 0, 0.0
                    if self.app.player.play(path):
                        clips += 1
                        seconds += length
            except Exception as e:  # noqa: BLE001
                self.app.log('Playback failed: %s' % e)
            finally:
                self.playing = False
                audio.safe_remove(path)
            # One line per request once all of it has played, so a silent machine is easy to spot.
            if clips and g == self.gen and self.play_q.empty() and self.producing != g:
                self.app.log('Played %d clip(s), %.1f s of audio, through %s.' % (clips, seconds, self.app.player.backend))
                clips = 0


class App:
    def __init__(self, args):
        self.args = args
        os.makedirs(args.runtime, exist_ok=True)
        self.paths = engines.Paths(args.runtime)
        self.log = Log(os.path.join(args.runtime, 'companion.log'), echo=not args.background)
        self.settings = Settings(os.path.join(args.runtime, 'settings.json'), args.test)
        # --port is a one-run override; only the setup page changes the saved port.
        self.port = args.port or int(self.settings.data.get('port') or DEFAULT_PORT)
        self.player = audio.Player(silent=args.silent)
        self.engine = None
        self.engine_lock = threading.Lock()
        self.jobs = {}
        self.last_game_contact = time.time()
        self.speaker = Speaker(self)
        for f in os.listdir(self.paths.tmp):
            audio.safe_remove(os.path.join(self.paths.tmp, f))

    # -- engines --

    def switch_engine(self, eid=None):
        eid = eid or self.settings.data['engine']
        with self.engine_lock:
            self.speaker.stop()
            old = self.engine
            if old:
                old.close()
            self.log('Starting %s...' % engines.ENGINES[eid]['name'])
            self.engine = engines.create(self.paths, eid, self.settings.engine(eid), self.log)

    def engine_view(self):
        e = self.engine
        return {
            'id': getattr(e, 'engine_id', ''),
            'state': getattr(e, 'state', 'stopped'),
            'error': getattr(e, 'error', ''),
            'voices': getattr(e, 'voices', []),
            'info': getattr(e, 'info', {}),
        }

    def status(self):
        ev = self.engine_view()
        return {'ok': True, 'version': VERSION, 'ready': ev['state'] == 'ready', 'speaking': self.speaker.speaking,
                'engine': engines.ENGINES.get(ev['id'], {}).get('name', ''), 'engine_id': ev['id'],
                'state': ev['state'], 'error': ev['error'] or self.speaker.last_error}

    def state(self, log_since=0):
        specs = engines.public_specs(self.settings.show_hidden)
        return {
            'status': self.status(),
            'settings': self.settings.data,
            'engine': self.engine_view(),
            'engines': {eid: dict(spec, installed=engines.installed(self.paths, eid),
                                  job=self.job_view(eid)) for eid, spec in specs.items()},
            'log': self.log.since(log_since),
            'clips': self.clips(),
            'player': self.player.backend,
            'platform': sys.platform,
            'uv': bool(engines.find_uv()),
        }

    def job_view(self, eid):
        j = self.jobs.get(eid)
        return {'state': j.state, 'error': j.error} if j else None

    def install(self, eid):
        j = self.jobs.get(eid)
        if j and j.state == 'running':
            return
        if self.engine is not None and getattr(self.engine, 'engine_id', '') == eid:
            self.engine.close()  # files in the venv must not be in use
        self.jobs[eid] = engines.InstallJob(self.paths, eid, self.log).start()

        def after():
            self.jobs[eid].thread.join()
            if self.jobs[eid].state == 'done' and self.settings.data['engine'] == eid:
                self.switch_engine(eid)
        threading.Thread(target=after, daemon=True).start()

    # -- speech --

    def speak(self, req):
        text = (req.get('text') or '')[:MAX_TEXT]
        speaker = req.get('speaker') or {}
        gender = speaker.get('gender') if speaker.get('gender') in ('male', 'female') else ''
        if self.engine is None or self.engine.state != 'ready':
            return {'ok': False, 'error': 'engine is %s' % (self.engine.state if self.engine else 'not started')}
        eid = self.engine.engine_id
        es = self.settings.engine(eid)
        key = speaker_key(speaker)
        pooled = {}

        def voice_for(role):
            if role in ('male', 'female'):
                if role not in pooled:
                    pooled[role] = self.pool_voice(eid, role, key)
                if pooled[role] is not None:
                    return pooled[role]
            return pick_voice(es, '' if role == 'narrator' else role)

        parts = request_parts(req.get('parts'))
        if parts:
            # The game decided who reads each part: the narrator (the "default" slot) or a character.
            spoken = [(t, voice_for(role), pause) if pause else (t, voice_for(role)) for t, role, pause in parts]
        else:  # older mod versions: one text, read in the speaker's voice
            spoken = [(text, voice_for(gender) if gender else pick_voice(es, ''))]
        self.speaker.last_error = ''
        who = speaker.get('name') or ''
        roles = sorted({p[1] for p in parts}) if parts else []
        picks = ['%s from the %s pool' % (pool_identity(v if isinstance(v, str) else v.get('ref', '')), g)
                 for g, v in sorted(pooled.items()) if v is not None]
        self.log('Request: %d characters%s%s%s.' % (sum(len(s[0]) for s in spoken), (' from ' + who) if who else '',
                                                    (' (' + ', '.join(roles) + ')') if len(roles) > 1 else '',
                                                    ('; ' + ', '.join(picks)) if picks else ''))
        return {'ok': True, 'id': self.speaker.speak_parts(spoken)}

    def pool_voice(self, eid, gender, key):
        """The pool voice for this character, in the form the engine takes; None = use the slot's voice."""
        if not self.settings.data.get('use_pools', True):
            return None
        es = self.settings.engine(eid)
        pool = (es.get('pools') or {}).get(gender) or []
        if engines.ENGINES[eid]['voice_type'] == 'clip':
            pool = [p for p in pool if os.path.isfile(p)]  # a deleted clip drops out of the pool
        else:
            known = getattr(self.engine, 'voices', None) or []
            if known:
                pool = [v for v in pool if v in known]  # pools of another model's voices don't apply
        v = pool_pick(key, pool)
        if v is None or engines.ENGINES[eid]['voice_type'] != 'clip':
            return v
        slot = (es.get('voices') or {}).get('default')
        out = {'ref': v}
        if isinstance(slot, dict) and 'ref_text' in slot:
            out['ref_text'] = ''  # F5 transcribes the clip itself
        return out

    def clips(self):
        """Voice clips in runtime/voices, for clip engines' pools."""
        try:
            names = sorted(os.listdir(self.paths.voices), key=str.lower)
        except OSError:
            return []
        return [os.path.join(self.paths.voices, n) for n in names
                if os.path.splitext(n)[1].lower() in ('.wav', '.mp3', '.flac', '.ogg')]

    def voices_view(self):
        """What the game's settings menu shows: the engine, its voices, the chosen ones and the pools."""
        ev = self.engine_view()
        eid = ev['id'] or self.settings.data['engine']
        spec = engines.ENGINES.get(eid, {})
        es = self.settings.engine(eid) if eid in self.settings.data['engines'] else {}
        slots = {}
        for slot, v in (es.get('voices') or {}).items():
            slots[slot] = (v.get('ref') or '') if isinstance(v, dict) else (v or '')
        return {'ok': True, 'version': VERSION, 'engine_id': eid, 'engine': spec.get('name', ''),
                'state': ev['state'], 'error': ev['error'], 'voice_type': spec.get('voice_type', 'name'),
                'voices': list(ev['voices'] or []), 'clips': self.clips(), 'slots': slots,
                'pools': es.get('pools') or {'male': [], 'female': []},
                'use_pools': bool(self.settings.data.get('use_pools', True)), 'port': self.port}


# ------------------------------------------------------------------------------------- HTTP


class Handler(BaseHTTPRequestHandler):
    app = None  # set in main()
    server_version = 'ReadAloudCompanion/' + VERSION

    def log_message(self, fmt, *a):  # quiet: the app log has what matters
        pass

    def _allowed(self, post):
        port = self.server.server_address[1]
        host = (self.headers.get('Host') or '').lower()
        if host not in ('127.0.0.1:%d' % port, 'localhost:%d' % port):
            self._json(403, {'ok': False, 'error': 'bad host'})
            return False
        if post and self.headers.get(HEADER) != '1':
            self._json(403, {'ok': False, 'error': 'missing %s header' % HEADER})
            return False
        return True

    def _json(self, code, obj):
        body = json.dumps(obj).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(body)

    def _body(self, limit=1024 * 1024):
        n = int(self.headers.get('Content-Length') or 0)
        if n > limit:
            raise ValueError('request too large')
        return self.rfile.read(n) if n else b''

    def do_GET(self):
        if not self._allowed(False):
            return
        url = urlparse(self.path)
        app = self.app
        if url.path in ('/', '/index.html'):
            with open(os.path.join(APP_DIR, 'ui', 'index.html'), 'rb') as f:
                body = f.read()
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Content-Security-Policy', "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'")
            self.end_headers()
            self.wfile.write(body)
        elif url.path == '/api/status':
            if self.headers.get(HEADER) == '1':
                app.last_game_contact = time.time()
            self._json(200, app.status())
        elif url.path == '/api/voices':
            self._json(200, app.voices_view())
        elif url.path == '/api/state':
            since = int((parse_qs(url.query).get('log') or ['0'])[0])
            self._json(200, app.state(since))
        else:
            self._json(404, {'ok': False, 'error': 'not found'})

    def do_POST(self):
        if not self._allowed(True):
            return
        app = self.app
        url = urlparse(self.path)
        try:
            if url.path == '/api/upload':
                name = (parse_qs(url.query).get('name') or ['voice.wav'])[0]
                stem, ext = os.path.splitext(os.path.basename(name))
                if ext.lower() not in ('.wav', '.mp3', '.flac', '.ogg'):
                    return self._json(400, {'ok': False, 'error': 'use a .wav, .mp3, .flac or .ogg file'})
                stem = re.sub(r'[^A-Za-z0-9_-]+', '_', stem)[:60] or 'voice'
                path = os.path.join(app.paths.voices, stem + ext.lower())
                with open(path, 'wb') as f:
                    f.write(self._body(MAX_UPLOAD))
                app.log('Saved voice clip %s' % path)
                return self._json(200, {'ok': True, 'path': path})
            req = json.loads(self._body().decode('utf-8') or '{}')
            if url.path == '/api/speak':
                app.last_game_contact = time.time()
                self._json(200, app.speak(req))
            elif url.path == '/api/stop':
                app.speaker.stop()
                self._json(200, {'ok': True})
            elif url.path == '/api/test-sound':
                self._json(200, {'ok': True, 'id': app.speaker.test_sound(), 'player': app.player.backend})
            elif url.path == '/api/settings':
                self._json(200, self._settings(req))
            elif url.path == '/api/install':
                eid = req.get('engine')
                if eid not in engines.ENGINES or engines.ENGINES[eid]['kind'] != 'python':
                    return self._json(400, {'ok': False, 'error': 'unknown engine'})
                app.install(eid)
                self._json(200, {'ok': True})
            elif url.path == '/api/cancel-install':
                j = app.jobs.get(req.get('engine'))
                if j:
                    j.cancel()
                self._json(200, {'ok': True})
            elif url.path == '/api/uninstall':
                eid = req.get('engine')
                if eid not in engines.ENGINES or engines.ENGINES[eid]['kind'] != 'python':
                    return self._json(400, {'ok': False, 'error': 'unknown engine'})
                if getattr(app.engine, 'engine_id', '') == eid:
                    app.settings.data['engine'] = 'system'
                    app.settings.save()
                    app.switch_engine('system')
                engines.uninstall(app.paths, eid)
                app.log('Removed %s.' % engines.ENGINES[eid]['name'])
                self._json(200, {'ok': True})
            elif url.path == '/api/restart-engine':
                app.switch_engine()
                self._json(200, {'ok': True})
            elif url.path == '/api/shutdown':
                self._json(200, {'ok': True})
                threading.Thread(target=self.server.shutdown, daemon=True).start()
            else:
                self._json(404, {'ok': False, 'error': 'not found'})
        except Exception as e:  # noqa: BLE001
            self._json(400, {'ok': False, 'error': str(e)})

    def _settings(self, req):
        app = self.app
        s = app.settings.data
        restart = False
        if 'engine' in req:
            if req['engine'] not in engines.public_specs(app.settings.show_hidden):
                return {'ok': False, 'error': 'unknown engine'}
            restart = req['engine'] != s['engine']
            s['engine'] = req['engine']
        if 'volume' in req:
            s['volume'] = max(0, min(100, int(req['volume'])))
        if 'port' in req:
            s['port'] = max(1024, min(65535, int(req['port'])))
        if 'use_pools' in req:
            s['use_pools'] = bool(req['use_pools'])
        if isinstance(req.get('engines'), dict):
            for eid, values in req['engines'].items():
                if eid in s['engines'] and isinstance(values, dict):
                    live = ('voices', 'pools')  # take effect on the next request, no reload
                    before = json.dumps({k: v for k, v in s['engines'][eid].items() if k not in live}, sort_keys=True)
                    s['engines'][eid] = merge(s['engines'][eid], values)
                    pools = s['engines'][eid].setdefault('pools', {})
                    for g in ('male', 'female'):
                        pools[g] = clean_pool(pools.get(g))
                    after = json.dumps({k: v for k, v in s['engines'][eid].items() if k not in live}, sort_keys=True)
                    if eid == s['engine'] and before != after:
                        restart = True  # model, device, rate... need a reload; voices don't
                    if app.engine is not None and getattr(app.engine, 'engine_id', '') == eid:
                        app.engine.settings.update(s['engines'][eid])
        app.settings.save()
        if restart:
            app.switch_engine()
        return {'ok': True, 'restarted': restart}


class Server(ThreadingHTTPServer):
    """Refuses to share the port. Python sets SO_REUSEADDR, which on Windows lets a
    second process bind a port that is already in use; then two apps would run."""
    allow_reuse_address = sys.platform != 'win32'

    def server_bind(self):
        if sys.platform == 'win32':
            import socket
            self.socket.setsockopt(socket.SOL_SOCKET, getattr(socket, 'SO_EXCLUSIVEADDRUSE', -5), 1)
        super().server_bind()


def main(argv=None):
    p = argparse.ArgumentParser(description='Read Aloud companion app')
    p.add_argument('--port', type=int, default=0, help='port (default: from settings, 47700)')
    p.add_argument('--runtime', default=DEFAULT_RUNTIME, help='folder for settings, engines and models')
    p.add_argument('--background', action='store_true', help='started by the game: no browser, no console output, exits when idle')
    p.add_argument('--no-browser', action='store_true')
    p.add_argument('--idle-minutes', type=float, default=20, help='background mode: exit after this long without the game')
    p.add_argument('--test', action='store_true', help='show the test engine')
    p.add_argument('--silent', action='store_true', help='do not play audio (tests)')
    p.add_argument('--engine', default='', help='use this engine (and save the choice)')
    args = p.parse_args(argv)
    args.runtime = os.path.abspath(args.runtime)

    app = App(args)
    if args.engine:
        if args.engine not in engines.public_specs(args.test):
            p.error('unknown engine %r' % args.engine)
        app.settings.data['engine'] = args.engine
        app.settings.save()
    port = app.port
    url = 'http://127.0.0.1:%d/' % port
    Handler.app = app
    try:
        httpd = Server(('127.0.0.1', port), Handler)
    except OSError:
        app.log('Port %d is in use: the companion app is probably already running at %s' % (port, url))
        if not (args.background or args.no_browser):
            webbrowser.open(url)
        return 0
    httpd.daemon_threads = True
    app.log('Read Aloud companion %s on %s (runtime: %s)' % (VERSION, url, args.runtime))
    app.log('Sound output: %s' % audio.describe_output(app.player))
    app.switch_engine()
    with open(os.path.join(args.runtime, 'ready.txt'), 'w', encoding='utf-8') as f:
        f.write('The companion app has been set up; the game may start it in the background.\n')

    if args.background:
        def idle_watch():
            while True:
                time.sleep(15)
                if not app.speaker.speaking and time.time() - app.last_game_contact > args.idle_minutes * 60:
                    app.log('No contact from the game for %g minutes; exiting.' % args.idle_minutes)
                    httpd.shutdown()
                    return
        threading.Thread(target=idle_watch, daemon=True).start()
    elif not args.no_browser:
        threading.Timer(0.5, webbrowser.open, args=(url,)).start()
        app.log('Setup page opened in your browser. Close this window to stop the app.')
    try:
        httpd.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        app.speaker.stop()
        if app.engine:
            app.engine.close()
        httpd.server_close()
        app.log('Stopped.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
