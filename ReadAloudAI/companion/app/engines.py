"""Speech engines: what can be installed, how to install it, and how to run it.

Built-in engines need no download:
  system  - Windows SAPI (a PowerShell worker), macOS `say`, Linux espeak-ng.
AI engines each get their own virtual environment under runtime/venvs/<id>, created
with uv, and run as a long-lived worker process (app/workers/worker_<id>.py) that
talks JSON lines over stdin/stdout (see PROTOCOL.md). Models download on first load
into runtime/models.
"""

import json
import os
import shutil
import subprocess
import sys
import threading
import time

APP_DIR = os.path.dirname(os.path.abspath(__file__))
WORKERS = os.path.join(APP_DIR, 'workers')
NO_WINDOW = 0x08000000 if sys.platform == 'win32' else 0  # CREATE_NO_WINDOW

ENGINES = {
    'system': {
        'name': 'System voice',
        'kind': 'builtin',
        'blurb': 'The voice built into your operating system. Instant, no download, robotic.',
        'size': 'nothing to download',
        'defaults': {'rate': 0, 'voices': {'default': '', 'male': '', 'female': ''}},
        'voice_type': 'name',
    },
    'kitten': {
        'name': 'Kitten TTS',
        'kind': 'python',
        'blurb': 'Natural voices with many built-in speakers. Recommended. Runs on CPU or an NVIDIA GPU.',
        'size': 'about 3 GB of libraries (PyTorch) plus the model (25 MB to 1 GB)',
        'packages': ['kittenml', 'soundfile'],
        'python': '3.11',
        'torch_backends': ['auto', 'cpu'],
        'defaults': {
            'model': 'KittenML/kitten-tts-mini-0.8',
            'device': 'auto',
            'speed': 1.0,
            'voices': {'default': 'Jasper', 'male': 'Bruno', 'female': 'Bella'},
        },
        'models': [
            ['KittenML/kitten-tts-2', 'Kitten TTS 2: best quality, 115 voices (1 GB; wants 8 GB VRAM, slower)'],
            ['KittenML/kitten-tts-mini-0.8', 'Mini 0.8: fast, 8 voices (80 MB)'],
            ['KittenML/kitten-tts-micro-0.8', 'Micro 0.8 (41 MB)'],
            ['KittenML/kitten-tts-nano-0.8-int8', 'Nano 0.8 int8 (25 MB, fastest)'],
        ],
        'voice_type': 'name',
        'license': 'Code Apache-2.0; the kitten-tts-2 model uses the Stellon Labs Community License.',
    },
    'f5': {
        'name': 'F5-TTS',
        'kind': 'python',
        'blurb': 'Voice cloning from a short clip (under 12 s) of anyone speaking. Best with an NVIDIA GPU.',
        'size': 'about 4 GB of libraries plus a 1.4 GB model',
        'packages': ['f5-tts'],
        'python': '3.11',
        'torch_backends': ['auto', 'cpu'],
        'defaults': {
            'device': 'auto',
            'speed': 1.0,
            'voices': {'default': {'ref': '', 'ref_text': ''}, 'male': {'ref': '', 'ref_text': ''},
                       'female': {'ref': '', 'ref_text': ''}},
        },
        'voice_type': 'clip',
        'needs': 'ffmpeg on PATH (https://ffmpeg.org/download.html)',
        'license': 'Code MIT; the pretrained model is CC-BY-NC (non-commercial).',
    },
    'chatterbox': {
        'name': 'Chatterbox',
        'kind': 'python',
        'blurb': 'Expressive voice cloning by Resemble AI. No longer maintained by its authors (2026); kept for those who like it.',
        'size': 'about 4 GB of libraries plus a 3 GB model',
        'packages': ['chatterbox-tts'],
        'python': '3.11',
        # It pins torch 2.6.0, which only has CUDA 12.4/12.6 builds: "auto" can pick a newer CUDA and fail.
        'torch_backends': ['auto', 'cu126', 'cu124', 'cpu'],
        'defaults': {
            'device': 'auto',
            'variant': 'standard',
            'exaggeration': 0.5,
            'cfg_weight': 0.5,
            'voices': {'default': {'ref': ''}, 'male': {'ref': ''}, 'female': {'ref': ''}},
        },
        'variants': [['standard', 'Standard (English)'], ['turbo', 'Turbo (faster)']],
        'voice_type': 'clip',
        'legacy': True,
        'license': 'MIT. Output carries an inaudible watermark.',
    },
    'test': {
        'name': 'Test beeps',
        'kind': 'builtin',
        'blurb': 'A beep per chunk. Only with --test.',
        'size': 'nothing',
        'defaults': {'voices': {'default': '', 'male': '', 'female': ''}},
        'voice_type': 'name',
        'hidden': True,
    },
}


def public_specs(show_hidden=False):
    return {k: v for k, v in ENGINES.items() if show_hidden or not v.get('hidden')}


class Paths:
    def __init__(self, runtime):
        self.runtime = runtime
        self.venvs = os.path.join(runtime, 'venvs')
        self.models = os.path.join(runtime, 'models')
        self.tmp = os.path.join(runtime, 'tmp')
        self.voices = os.path.join(runtime, 'voices')
        for d in (self.venvs, self.models, self.tmp, self.voices):
            os.makedirs(d, exist_ok=True)

    def venv(self, engine_id):
        return os.path.join(self.venvs, engine_id)

    def venv_python(self, engine_id):
        v = self.venv(engine_id)
        return os.path.join(v, 'Scripts', 'python.exe') if sys.platform == 'win32' else os.path.join(v, 'bin', 'python')

    def marker(self, engine_id):
        return os.path.join(self.venv(engine_id), 'readaloud-installed.json')

    def worker_env(self):
        """Keeps every download and cache inside runtime/."""
        env = dict(os.environ)
        env.update({
            'HF_HOME': os.path.join(self.models, 'huggingface'),
            'TORCH_HOME': os.path.join(self.models, 'torch'),
            'XDG_CACHE_HOME': os.path.join(self.models, 'cache'),
            'NLTK_DATA': os.path.join(self.models, 'nltk'),
            'HF_HUB_DISABLE_TELEMETRY': '1',
            'HF_HUB_DISABLE_SYMLINKS_WARNING': '1',  # Windows without developer mode: copies instead, fine
            'HF_HUB_VERBOSITY': 'error',
            'GRADIO_ANALYTICS_ENABLED': 'False',
            'WANDB_MODE': 'disabled',
            'PYTHONUTF8': '1',
            'PYTHONIOENCODING': 'utf-8',
            'PYTHONNOUSERSITE': '1',
        })
        return env


def installed(paths, engine_id):
    spec = ENGINES[engine_id]
    if spec['kind'] == 'builtin':
        return True
    return os.path.isfile(paths.marker(engine_id)) and os.path.isfile(paths.venv_python(engine_id))


def find_uv():
    uv = os.environ.get('READALOUD_UV')
    if uv and os.path.isfile(uv):
        return uv
    return shutil.which('uv')


_running_installs = 0
_installs_lock = threading.Lock()


class InstallJob:
    """Creates the venv and installs an engine's packages with uv, in a thread. Log lines go to log().

    Afterwards uv's download cache is emptied (it would double the disk use: venvs hold
    copies, not links) unless another install is still using it.
    """

    def __init__(self, paths, engine_id, log):
        self.paths, self.engine_id, self.log = paths, engine_id, log
        self.state = 'running'  # running | done | failed | cancelled
        self.error = ''
        self._proc = None
        self._cancel = False
        self.thread = threading.Thread(target=self._run, name='install-' + engine_id, daemon=True)

    def start(self):
        self.thread.start()
        return self

    def cancel(self):
        self._cancel = True
        p = self._proc
        if p and p.poll() is None:
            p.terminate()

    def _run(self):
        global _running_installs
        with _installs_lock:
            _running_installs += 1
        try:
            self._install()
        finally:
            with _installs_lock:
                _running_installs -= 1
                last_one = _running_installs == 0
            uv = find_uv()
            if self.state == 'done' and last_one and uv:
                try:
                    self._exec([uv, 'cache', 'clean'])
                    self.log('Cleared the download cache.')
                except Exception as e:  # noqa: BLE001 - only disk space is at stake
                    self.log('Could not clear the download cache: %s' % e)

    def _install(self):
        spec = ENGINES[self.engine_id]
        try:
            uv = find_uv()
            if not uv:
                raise RuntimeError('uv not found. Start the app with start.cmd / start.sh, which downloads it.')
            venv = self.paths.venv(self.engine_id)
            if os.path.isdir(venv):
                self.log('Removing the old environment...')
                shutil.rmtree(venv, ignore_errors=True)
            self.log('Creating a Python %s environment for %s...' % (spec['python'], spec['name']))
            self._exec([uv, 'venv', '--python', spec['python'], '--seed', venv])
            last = None
            for backend in spec.get('torch_backends', ['auto']):
                self.log('Installing %s (PyTorch build: %s). This can take a while...' % (', '.join(spec['packages']), backend))
                try:
                    self._exec([uv, 'pip', 'install', '--python', self.paths.venv_python(self.engine_id),
                                '--torch-backend', backend] + spec['packages'])
                    last = None
                    break
                except RuntimeError as e:
                    if self._cancel:
                        raise
                    last = e
                    self.log('That failed; trying the next PyTorch build.')
            if last:
                raise last
            with open(self.paths.marker(self.engine_id), 'w', encoding='utf-8') as f:
                json.dump({'packages': spec['packages'], 'time': time.time()}, f)
            venv = self.paths.venv(self.engine_id)
            with open(os.path.join(venv, LOCATION_FILE), 'w', encoding='utf-8') as f:
                f.write(os.path.abspath(venv) + '\n')
            self.state = 'done'
            self.log('%s is installed. The model downloads the first time it loads.' % spec['name'])
        except Exception as e:  # noqa: BLE001 - reported to the user
            self.state = 'cancelled' if self._cancel else 'failed'
            self.error = str(e)
            self.log('Install %s: %s' % (self.state, e))

    def _exec(self, cmd):
        if self._cancel:
            raise RuntimeError('cancelled')
        env = self.paths.worker_env()
        env['UV_LINK_MODE'] = 'copy'
        self._proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env,
                                      creationflags=NO_WINDOW, text=True, encoding='utf-8', errors='replace')
        for line in self._proc.stdout:
            line = line.rstrip()
            if line:
                self.log('  ' + line)
        code = self._proc.wait()
        self._proc.stdout.close()
        if code != 0:
            raise RuntimeError('%s exited with code %d' % (os.path.basename(cmd[0]), code))


def uninstall(paths, engine_id):
    shutil.rmtree(paths.venv(engine_id), ignore_errors=True)


# ---------------------------------------------------------------------------------
# Running engines. All share: start(), state, error, voices, synth(text, voice, out), close().


class WorkerEngine:
    """A worker process speaking JSON lines (Python worker in a venv, or the PowerShell SAPI worker)."""

    def __init__(self, engine_id, cmd, env, settings, log):
        self.engine_id = engine_id
        self.cmd, self.env, self.settings, self.log = cmd, env, settings, log
        self.state = 'starting'  # starting | loading | ready | error | stopped
        self.error = ''
        self.voices = []
        self.info = {}
        self._proc = None
        self._next = 0
        self._waiting = {}
        self._lock = threading.Lock()  # one synth at a time
        self._wlock = threading.Lock()

    def start(self):
        try:
            self._proc = subprocess.Popen(self.cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                          env=self.env, cwd=WORKERS, creationflags=NO_WINDOW,
                                          text=True, encoding='utf-8', errors='replace', bufsize=1)
        except OSError as e:
            self.state, self.error = 'error', 'could not start: %s' % e
            return self
        threading.Thread(target=self._read_out, daemon=True, name='worker-out').start()
        threading.Thread(target=self._read_err, daemon=True, name='worker-err').start()
        self.state = 'loading'
        self._send({'op': 'load', 'settings': self.settings})
        return self

    def _send(self, msg):
        with self._wlock:
            try:
                self._proc.stdin.write(json.dumps(msg) + '\n')
                self._proc.stdin.flush()
            except (OSError, ValueError, AttributeError) as e:
                self._fail('worker is not running (%s)' % e)

    def _read_out(self):
        try:
            self._read_events()
        except (OSError, ValueError):
            pass  # pipe closed by close()
        if self.state not in ('stopped', 'error'):
            self._fail('worker exited (code %s)' % self._proc.poll())
        for ev, box in list(self._waiting.values()):
            box.update({'event': 'error', 'message': 'worker exited'})
            ev.set()

    def _read_events(self):
        for line in self._proc.stdout:
            line = line.strip()
            if not line:
                continue
            try:
                ev = json.loads(line)
            except ValueError:
                self.log('[%s] %s' % (self.engine_id, line))
                continue
            kind = ev.get('event')
            if kind == 'loaded':
                self.voices = ev.get('voices') or []
                self.info = ev.get('info') or {}
                self.state = 'ready'
                self.log('%s is ready%s.' % (ENGINES[self.engine_id]['name'],
                                             (' (%s)' % self.info['device']) if self.info.get('device') else ''))
            elif kind == 'log':
                self.log('[%s] %s' % (self.engine_id, ev.get('message', '')))
            elif kind in ('done', 'error'):
                if kind == 'error' and ev.get('id') is None:
                    self._fail(ev.get('message', 'failed'))
                    continue
                waiter = self._waiting.pop(ev.get('id'), None)
                if waiter:
                    waiter[1].update(ev)
                    waiter[0].set()

    def _read_err(self):
        try:
            for line in self._proc.stderr:
                line = line.rstrip()
                if line:
                    self.log('[%s] %s' % (self.engine_id, line[:500]))
        except (OSError, ValueError):
            pass

    def _fail(self, msg):
        if self.state != 'stopped':
            self.state, self.error = 'error', msg
            self.log('%s: %s' % (ENGINES[self.engine_id]['name'], msg))

    def synth(self, text, voice, out, timeout=600):
        with self._lock:
            if self.state != 'ready':
                raise RuntimeError('engine is %s' % self.state)
            self._next += 1
            rid = self._next
            done, box = threading.Event(), {}
            self._waiting[rid] = (done, box)
            self._send({'op': 'synth', 'id': rid, 'text': text, 'voice': voice, 'out': out})
            if not done.wait(timeout):
                self._waiting.pop(rid, None)
                raise RuntimeError('timed out')
            if box.get('event') != 'done':
                raise RuntimeError(box.get('message', 'synthesis failed'))
            return out

    def close(self):
        self.state = 'stopped'
        p = self._proc
        if p and p.poll() is None:
            try:
                self._send({'op': 'quit'})
                p.wait(3)
            except Exception:  # noqa: BLE001
                pass
            if p.poll() is None:
                p.kill()
        if p:
            for pipe in (p.stdin, p.stdout, p.stderr):
                try:
                    pipe.close()
                except (OSError, ValueError):
                    pass


class CommandEngine:
    """Built-in voices on macOS/Linux: one short process per chunk, writing a WAV."""

    def __init__(self, engine_id, settings, log):
        self.engine_id, self.settings, self.log = engine_id, settings, log
        self.state, self.error, self.voices, self.info = 'starting', '', [], {}
        self.program = None

    def start(self):
        if self.engine_id == 'test':
            self.state, self.voices, self.info = 'ready', ['beep'], {'device': 'none'}
            return self
        if sys.platform == 'darwin':
            self.program = 'say'
        else:
            for p in ('espeak-ng', 'espeak'):
                if shutil.which(p):
                    self.program = p
                    break
        if not self.program:
            self.state, self.error = 'error', 'no system voice found: install espeak-ng (e.g. sudo apt install espeak-ng)'
            return self
        self.state, self.info = 'ready', {'device': self.program}
        return self

    def synth(self, text, voice, out, timeout=120):
        if self.engine_id == 'test':
            from audio import tone
            tone(out, seconds=0.05 + 0.002 * len(text))
            return out
        rate = int(self.settings.get('rate', 0))
        wpm = max(80, min(450, 175 + rate * 14))
        if self.program == 'say':
            cmd = ['say', '-r', str(wpm), '-o', out, '--file-format=WAVE', '--data-format=LEI16@22050']
            if voice:
                cmd += ['-v', voice]
        else:
            cmd = [self.program, '-s', str(wpm), '-w', out]
            if voice:
                cmd += ['-v', voice]
        subprocess.run(cmd + ['--', text], check=True, timeout=timeout, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return out

    def close(self):
        self.state = 'stopped'


LOCATION_FILE = 'readaloud-location.txt'


def fix_moved_venv(paths, engine_id, log=None, run_uv=True):
    """Makes an engine's venv work again after the runtime folder moved (the mod folder moved, or
    the app moved to its own mod) instead of making the player reinstall gigabytes.

    A venv has absolute paths in two places: "home" in pyvenv.cfg (where its Python lives) and
    its python launcher (uv's trampoline on Windows, a symlink elsewhere). The first is rewritten
    to the same Python inside this runtime; "uv venv --allow-existing" remakes the launchers and
    keeps the installed packages. The venv's own path is noted in LOCATION_FILE, so this runs
    once per move. Returns True if it changed anything."""
    venv = paths.venv(engine_id)
    cfg = os.path.join(venv, 'pyvenv.cfg')
    loc = os.path.join(venv, LOCATION_FILE)
    try:
        with open(cfg, encoding='utf-8') as f:
            lines = f.read().splitlines()
    except OSError:
        return False
    try:
        with open(loc, encoding='utf-8') as f:
            moved = f.read().strip() != os.path.abspath(venv)
    except OSError:
        moved = True  # made before locations were noted: check once
    home = next((l.partition('=')[2].strip() for l in lines if l.partition('=')[0].strip() == 'home'), '')
    fixed_home = home if os.path.isdir(home) else _moved_home(paths, home)
    if not fixed_home:
        return False
    changed = False
    if fixed_home != home:
        lines = ['home = ' + fixed_home if l.partition('=')[0].strip() == 'home' else l for l in lines]
        with open(cfg, 'w', encoding='utf-8', newline='\n') as f:
            f.write('\n'.join(lines) + '\n')
        if log:
            log('The runtime folder moved: pointing the %s environment at %s.' % (engine_id, fixed_home))
        changed = moved = True
    if not moved:
        return changed
    ok = not run_uv or _rebuild_launchers(paths, engine_id, fixed_home, log)
    if ok:
        with open(loc, 'w', encoding='utf-8') as f:
            f.write(os.path.abspath(venv) + '\n')
    return True


def _rebuild_launchers(paths, engine_id, home, log):
    uv = find_uv()
    exe = next((os.path.join(home, n) for n in ('python.exe', 'python3', 'python')
                if os.path.isfile(os.path.join(home, n))), None)
    if not (uv and exe):
        return False
    try:
        r = subprocess.run([uv, 'venv', '--allow-existing', '--python', exe, paths.venv(engine_id)],
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=paths.worker_env(),
                           creationflags=NO_WINDOW, text=True, encoding='utf-8', errors='replace', timeout=120)
    except (OSError, subprocess.SubprocessError) as e:
        if log:
            log('Could not rebuild the %s environment\'s launchers: %s. Reinstall the engine if it fails.' % (engine_id, e))
        return False
    if log and r.returncode != 0:
        log('Rebuilding the %s environment\'s launchers failed: %s' % (engine_id, r.stdout.strip()[-300:]))
    elif log:
        log('Checked the %s environment\'s launchers for this folder.' % engine_id)
    return r.returncode == 0


def _moved_home(paths, home):
    """The same Python as `home` inside this runtime, or '' if there is none."""
    # ...\runtime\python\cpython-3.11-windows-x86_64-none[\bin]: keep the part after "python"
    parts = home.replace('\\', '/').split('/')
    if 'python' not in parts:
        return ''
    rest = parts[len(parts) - parts[::-1].index('python'):]
    base = os.path.join(paths.runtime, 'python')
    candidate = os.path.join(base, *rest)
    if not os.path.isdir(candidate) and rest:
        # uv's "cpython-3.11-windows-x86_64-none" is a link to "cpython-3.11.18-..." by absolute path,
        # so it breaks in a move too: use the newest real folder of that minor version.
        pieces = rest[0].split('-', 2)
        if len(pieces) == 3:
            impl, ver, tail = pieces
            try:
                names = os.listdir(base)
            except OSError:
                names = []
            found = sorted((n for n in names if n.startswith('%s-%s.' % (impl, ver)) and n.endswith('-' + tail)
                            and os.path.isdir(os.path.join(base, n))),
                           key=lambda n: [int(x) if x.isdigit() else 0 for x in n.split('-')[1].split('.')])
            if found:
                candidate = os.path.join(base, found[-1], *rest[1:])
    return candidate if os.path.isdir(candidate) else ''


def create(paths, engine_id, settings, log):
    """Starts the engine (asynchronously for workers). Returns the engine object."""
    spec = ENGINES[engine_id]
    if engine_id == 'system' and sys.platform == 'win32':
        cmd = ['powershell.exe', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
               '-File', os.path.join(WORKERS, 'sapi_worker.ps1')]
        return WorkerEngine(engine_id, cmd, paths.worker_env(), settings, log).start()
    if spec['kind'] == 'builtin':
        return CommandEngine(engine_id, settings, log).start()
    if not installed(paths, engine_id):
        e = CommandEngine(engine_id, settings, log)
        e.state, e.error = 'error', 'not installed'
        return e
    fix_moved_venv(paths, engine_id, log)
    cmd = [paths.venv_python(engine_id), '-u', os.path.join(WORKERS, 'worker_%s.py' % engine_id)]
    s = dict(settings)
    s['cache_dir'] = paths.models
    s['warmup_out'] = os.path.join(paths.tmp, 'warmup-%s.wav' % engine_id)
    return WorkerEngine(engine_id, cmd, paths.worker_env(), s, log).start()
