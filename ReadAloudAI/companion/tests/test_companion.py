"""Companion app tests. Standard library only: run with any Python 3.10+.

    python -m unittest discover -s companion/tests -v

READALOUD_TEST_RUNTIME sets the scratch folder (default: a temp dir).
"""

import http.client
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(HERE, '..', 'app')
sys.path.insert(0, APP)

import audio  # noqa: E402
import engines  # noqa: E402
import server  # noqa: E402
from textsplit import split_text  # noqa: E402

BASE_RUNTIME = os.path.abspath(os.environ.get('READALOUD_TEST_RUNTIME') or tempfile.mkdtemp(prefix='readaloud-test-'))


def scratch(name):
    d = os.path.join(BASE_RUNTIME, name)
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)
    return d


def free_port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


def wait_for(cond, timeout=10.0, what='condition'):
    end = time.time() + timeout
    while time.time() < end:
        if cond():
            return
        time.sleep(0.05)
    raise AssertionError('timed out waiting for ' + what)


class TextSplitTests(unittest.TestCase):
    def test_short_text_is_one_chunk(self):
        self.assertEqual(split_text('Hello there. How are you?'), ['Hello there. How are you?'])

    def test_paragraphs_never_merge(self):
        self.assertEqual(split_text('One.\n\nTwo.'), ['One.', 'Two.'])

    def test_first_chunk_is_short_so_speech_starts_fast(self):
        s = ' '.join(['This is sentence number %d.' % i for i in range(20)])
        chunks = split_text(s, max_chars=280, first_max=60)
        self.assertLessEqual(len(chunks[0]), 60)
        self.assertTrue(all(len(c) <= 280 for c in chunks))
        self.assertEqual(' '.join(chunks), s)

    def test_long_sentence_breaks_at_clauses_then_words(self):
        s = 'word, ' * 100 + 'end.'
        chunks = split_text(s, max_chars=50, first_max=50)
        self.assertTrue(all(len(c) <= 50 for c in chunks), chunks)
        self.assertEqual(' '.join(chunks).split(), s.split())

    def test_quotes_stay_with_their_sentence(self):
        self.assertEqual(split_text('"Go now!" she said. Then silence.', max_chars=20, first_max=20),
                         ['"Go now!" she said.', 'Then silence.'])

    def test_giant_word_is_cut(self):
        self.assertTrue(all(len(c) <= 10 for c in split_text('x' * 35, max_chars=10, first_max=10)))

    def test_empty(self):
        self.assertEqual(split_text(''), [])
        self.assertEqual(split_text('   \n\n  '), [])


class SettingsTests(unittest.TestCase):
    def test_merge_keeps_new_defaults_and_saved_values(self):
        d = {'a': 1, 'voices': {'default': {'ref': '', 'ref_text': ''}, 'male': {'ref': ''}}}
        m = server.merge(d, {'a': 2, 'voices': {'default': {'ref': 'x.wav'}}})
        self.assertEqual(m['a'], 2)
        self.assertEqual(m['voices']['default'], {'ref': 'x.wav', 'ref_text': ''})
        self.assertEqual(m['voices']['male'], {'ref': ''})
        self.assertEqual(d['voices']['default']['ref'], '', 'defaults untouched')

    def test_pick_voice_by_gender_with_fallback(self):
        s = {'voices': {'default': 'Jasper', 'male': 'Bruno', 'female': ''}}
        self.assertEqual(server.pick_voice(s, 'male'), 'Bruno')
        self.assertEqual(server.pick_voice(s, 'female'), 'Jasper')
        self.assertEqual(server.pick_voice(s, ''), 'Jasper')
        clips = {'voices': {'default': {'ref': 'd.wav'}, 'female': {'ref': ''}}}
        self.assertEqual(server.pick_voice(clips, 'female'), {'ref': 'd.wav'})

    def test_bad_saved_file_gives_defaults(self):
        d = scratch('settings')
        p = os.path.join(d, 'settings.json')
        with open(p, 'w') as f:
            f.write('{not json')
        s = server.Settings(p, False)
        self.assertEqual(s.data['engine'], 'system')
        self.assertEqual(s.engine('kitten')['voices']['male'], 'Bruno')

    def test_hidden_engine_is_not_kept_without_test_mode(self):
        d = scratch('settings2')
        p = os.path.join(d, 'settings.json')
        with open(p, 'w') as f:
            json.dump({'engine': 'test'}, f)
        self.assertEqual(server.Settings(p, False).data['engine'], 'system')
        self.assertEqual(server.Settings(p, True).data['engine'], 'test')


class AudioTests(unittest.TestCase):
    def test_tone_duration_and_volume(self):
        d = scratch('audio')
        p = os.path.join(d, 't.wav')
        audio.tone(p, seconds=0.5)
        self.assertAlmostEqual(audio.duration(p), 0.5, places=2)
        import wave
        import array
        audio.set_volume(p, 50)
        with wave.open(p) as w:
            a = array.array('h', w.readframes(w.getnframes()))
        self.assertLessEqual(max(abs(x) for x in a), int(0.3 * 32767 * 0.5) + 2)

    def test_silent_player_can_be_stopped(self):
        d = scratch('player')
        p = os.path.join(d, 't.wav')
        audio.tone(p, seconds=3)
        pl = audio.Player(silent=True)
        result = {}
        t = threading.Thread(target=lambda: result.setdefault('r', pl.play(p)))
        t0 = time.time()
        t.start()
        time.sleep(0.2)
        pl.stop()
        t.join(2)
        self.assertFalse(result['r'])
        self.assertLess(time.time() - t0, 1.5)


class WorkerProtocolTests(unittest.TestCase):
    """The real WorkerEngine against a worker that pollutes stdout like ML libraries do."""

    def make(self, settings=None):
        logs = []
        cmd = [sys.executable, '-u', os.path.join(HERE, 'dummy_worker.py')]
        e = engines.WorkerEngine('test', cmd, dict(os.environ), settings or {}, logs.append).start()
        self.addCleanup(e.close)
        return e, logs

    def test_load_synth_and_errors(self):
        e, logs = self.make()
        wait_for(lambda: e.state == 'ready', what='ready')
        self.assertEqual(e.voices, ['Alpha', 'Beta'])
        out = os.path.join(scratch('worker'), 'a.wav')
        e.synth('hello', 'Alpha', out)
        self.assertGreater(audio.duration(out), 0.01)
        with self.assertRaises(RuntimeError) as cm:
            e.synth('explode', None, out + '2')
        self.assertIn('bad text', str(cm.exception))
        e.synth('still works', None, out)  # one failure doesn't kill the worker
        wait_for(lambda: any('noisy library banner' in l for l in logs), what='stdout noise in the log')

    def test_failed_load_reports_error(self):
        e, _ = self.make({'fail_load': True})
        wait_for(lambda: e.state == 'error', what='error state')
        self.assertIn('load failed on purpose', e.error)

    def test_worker_crash_is_noticed(self):
        e, _ = self.make()
        wait_for(lambda: e.state == 'ready')
        e._proc.kill()
        wait_for(lambda: e.state == 'error', what='crash noticed')
        with self.assertRaises(RuntimeError):
            e.synth('x', None, os.path.join(scratch('crash'), 'x.wav'))


class InstallJobTests(unittest.TestCase):
    """The install flow against a fake uv that records its arguments (no network)."""

    def fake_uv(self, d, fail_backends=()):
        calls = os.path.join(d, 'calls.txt')
        fails = ' '.join(fail_backends)
        if sys.platform == 'win32':
            uv = os.path.join(d, 'uv.cmd')
            lines = ['@echo off', 'echo %* >> "' + calls + '"']
            for b in fail_backends:
                lines.append('echo %* | findstr /C:"--torch-backend ' + b + '" >nul && exit /b 1')
            lines += ['if "%1"=="venv" (mkdir "%~5\\Scripts" & echo x > "%~5\\Scripts\\python.exe")', 'exit /b 0']
            with open(uv, 'w', newline='\r\n') as f:
                f.write('\n'.join(lines) + '\n')
        else:
            uv = os.path.join(d, 'uv')
            with open(uv, 'w') as f:
                f.write('#!/bin/sh\necho "$@" >> "%s"\nfor b in %s; do case "$*" in *"--torch-backend $b"*) exit 1;; esac; done\n'
                        'if [ "$1" = venv ]; then mkdir -p "$5/bin"; echo x > "$5/bin/python"; fi\nexit 0\n' % (calls, fails))
            os.chmod(uv, 0o755)
        return uv, calls

    def run_job(self, fail_backends=()):
        d = scratch('install-%d' % len(fail_backends))
        uv, calls = self.fake_uv(d, fail_backends)
        old = os.environ.get('READALOUD_UV')
        os.environ['READALOUD_UV'] = uv
        try:
            paths = engines.Paths(os.path.join(d, 'rt'))
            logs = []
            job = engines.InstallJob(paths, 'chatterbox', logs.append).start()
            job.thread.join(60)
        finally:
            if old is None:
                os.environ.pop('READALOUD_UV', None)
            else:
                os.environ['READALOUD_UV'] = old
        with open(calls) as f:
            return job, paths, [l.strip() for l in f if l.strip()], logs

    def test_install_creates_venv_marks_it_and_cleans_cache(self):
        job, paths, calls, logs = self.run_job()
        self.assertEqual(job.state, 'done', logs)
        self.assertTrue(calls[0].startswith('venv --python 3.11 --seed'), calls)
        self.assertIn('--torch-backend auto chatterbox-tts', calls[1])
        self.assertEqual(calls[-1], 'cache clean')
        self.assertTrue(engines.installed(paths, 'chatterbox'))

    def test_falls_back_through_torch_builds(self):
        job, paths, calls, logs = self.run_job(fail_backends=('auto', 'cu126'))
        self.assertEqual(job.state, 'done', logs)
        backends = [c.split('--torch-backend ')[1].split()[0] for c in calls if '--torch-backend' in c]
        self.assertEqual(backends, ['auto', 'cu126', 'cu124'])

    def test_total_failure_is_reported_and_not_marked(self):
        job, paths, calls, logs = self.run_job(fail_backends=('auto', 'cu126', 'cu124', 'cpu'))
        self.assertEqual(job.state, 'failed')
        self.assertFalse(engines.installed(paths, 'chatterbox'))
        self.assertNotIn('cache clean', calls, 'cache kept after a failure (helps the retry)')


@unittest.skipUnless(sys.platform == 'win32', 'Windows SAPI')
class SapiTests(unittest.TestCase):
    def test_system_voice_writes_speech(self):
        rt = scratch('sapi')
        logs = []
        e = engines.create(engines.Paths(rt), 'system', {'rate': 2}, logs.append)
        self.addCleanup(e.close)
        wait_for(lambda: e.state in ('ready', 'error'), timeout=30, what='SAPI worker')
        self.assertEqual(e.state, 'ready', e.error + '\n' + '\n'.join(logs))
        self.assertTrue(e.voices, 'at least one installed voice')
        out = os.path.join(rt, 'hello.wav')
        e.synth('Hello captain. Été — "quoted" & <tagged> text, 100% fine.', e.voices[0], out)
        self.assertGreater(audio.duration(out), 0.8)
        # The default voice may be an "Online" one that can't save to files: the worker falls back.
        out2 = os.path.join(rt, 'default.wav')
        e.synth('Default voice check.', '', out2)
        self.assertGreater(audio.duration(out2), 0.5)
        self.assertFalse(any(v.endswith('Online') for v in e.voices), 'online voices are not offered')


class PartsTests(unittest.TestCase):
    """Requests whose text is split by voice (narrator / male / female), as the game sends them."""

    def test_request_parts_are_validated(self):
        raw = [{'text': ' Hi ', 'role': 'female'}, {'text': 'x', 'role': 'bogus'}, 'junk', {'text': '  '}, {'role': 'male'},
               {'text': 5}]
        self.assertEqual(server.request_parts(raw), [('Hi', 'female'), ('x', 'narrator')])
        self.assertEqual(server.request_parts(None), [])
        long = server.request_parts([{'text': 'a' * (server.MAX_TEXT - 1)}, {'text': 'bcd'}])
        self.assertEqual(sum(len(t) for t, _ in long), server.MAX_TEXT, 'total capped')

    class FakeSpeaker:
        last_error = ''

        def __init__(self):
            self.parts = None

        def speak_parts(self, parts):
            self.parts = parts
            return 1

    def fake_app(self):
        class Engine:
            state, engine_id = 'ready', 'kitten'
        settings = server.Settings(os.path.join(scratch('parts'), 'settings.json'), False)
        settings.engine('kitten')['voices'].update({'default': 'Jasper', 'male': 'Bruno', 'female': 'Bella'})
        app = type('FakeApp', (), {})()
        app.engine, app.settings, app.speaker, app.log = Engine(), settings, self.FakeSpeaker(), lambda m: None
        app.pool_voice = lambda e, g, k: server.App.pool_voice(app, e, g, k)
        return app

    def test_each_role_gets_its_slot(self):
        app = self.fake_app()
        r = server.App.speak(app, {'text': 'ignored when parts exist', 'speaker': {'gender': 'female'}, 'parts': [
            {'text': 'The officer looks up.', 'role': 'narrator'}, {'text': 'Papers.', 'role': 'female'},
            {'text': 'Behind her, a guard laughs.', 'role': 'narrator'}, {'text': 'Ha!', 'role': 'male'}]})
        self.assertTrue(r['ok'], r)
        self.assertEqual(app.speaker.parts, [('The officer looks up.', 'Jasper'), ('Papers.', 'Bella'),
                                             ('Behind her, a guard laughs.', 'Jasper'), ('Ha!', 'Bruno')])

    def test_old_requests_use_the_speakers_voice(self):
        app = self.fake_app()
        server.App.speak(app, {'text': 'Hello.', 'speaker': {'gender': 'male'}})
        self.assertEqual(app.speaker.parts, [('Hello.', 'Bruno')])

    def test_chunks_keep_their_voice_and_play_in_order(self):
        synth = []

        class Engine:
            def synth(self, text, voice, out):
                synth.append((text, voice))
                audio.tone(out, seconds=0.05)

        played = []

        class Player:
            backend = 'fake'

            def play(self, path):
                played.append(os.path.basename(path))

            def stop(self):
                pass

        app = type('FakeApp', (), {})()
        app.engine, app.player, app.log = Engine(), Player(), lambda m: None
        app.paths = type('P', (), {'tmp': scratch('parts-tmp')})()
        app.settings = type('S', (), {'data': {'volume': 100}})()
        sp = server.Speaker(app)
        sp.speak_parts([('One. ' * 30, 'Jasper'), ('Two.', 'Bella')])
        wait_for(lambda: not sp.speaking and synth, what='speech done')
        self.assertEqual(synth[-1], ('Two.', 'Bella'))
        self.assertTrue(all(v == 'Jasper' for _, v in synth[:-1]), synth)
        self.assertGreater(len(synth), 2, 'the long narrator part was chunked')
        self.assertEqual(len(played), len(synth))


class MovedRuntimeTests(unittest.TestCase):
    def test_venv_home_follows_a_moved_runtime(self):
        rt = scratch('moved-runtime')
        paths = engines.Paths(rt)
        py = os.path.join(rt, 'python', 'cpython-3.11-windows-x86_64-none')
        os.makedirs(py)
        os.makedirs(paths.venv('kitten'))
        cfg = os.path.join(paths.venv('kitten'), 'pyvenv.cfg')
        with open(cfg, 'w') as f:
            f.write('home = D:\\Old\\Place\\companion\\runtime\\python\\cpython-3.11-windows-x86_64-none\n'
                    'implementation = CPython\nversion_info = 3.11\n')
        logged = []
        self.assertTrue(engines.fix_moved_venv(paths, 'kitten', logged.append, run_uv=False))
        with open(cfg) as f:
            text = f.read()
        self.assertIn('home = ' + py + '\n', text)
        self.assertIn('version_info = 3.11', text, 'other lines kept')
        self.assertEqual(len(logged), 1)
        self.assertFalse(engines.fix_moved_venv(paths, 'kitten'), 'home exists now: nothing to do')
        self.assertFalse(engines.fix_moved_venv(paths, 'f5'), 'no venv')

    def test_broken_minor_version_link_uses_the_real_folder(self):
        rt = scratch('moved-runtime2')
        paths = engines.Paths(rt)
        for v in ('3.11.9', '3.11.18', '3.12.1'):
            os.makedirs(os.path.join(rt, 'python', 'cpython-%s-linux-x86_64-gnu' % v, 'bin'))
        os.makedirs(paths.venv('kitten'))
        cfg = os.path.join(paths.venv('kitten'), 'pyvenv.cfg')
        with open(cfg, 'w') as f:
            f.write('home = /old/runtime/python/cpython-3.11-linux-x86_64-gnu/bin\n')
        self.assertTrue(engines.fix_moved_venv(paths, 'kitten', run_uv=False))
        with open(cfg) as f:
            self.assertIn(os.path.join(rt, 'python', 'cpython-3.11.18-linux-x86_64-gnu', 'bin'), f.read(), 'newest 3.11, not 3.12')


class PoolTests(unittest.TestCase):
    """Voice pools: each character keeps one voice, picked by a hash of who they are."""

    # The same vectors are checked in the mod's VoiceTests.java: both sides must hash alike.
    VECTORS = [('', 17280346270528514342), ('a', 9413272369427828315),
               ('person_1\nBruno', 2101149729552114462), ('Ludd\u00e9', 17774750531867304293)]
    POOL = ['Bruno', 'Hugo', 'Leo', 'Jasper']

    def test_hash_matches_the_mods(self):
        for s, h in self.VECTORS:
            self.assertEqual(server.fnv64(s), h, repr(s))

    def test_picks_are_stable_and_spread(self):
        self.assertEqual(server.pool_pick('person_1', self.POOL), 'Leo')
        self.assertEqual(server.pool_pick('person_2', self.POOL), 'Hugo')
        counts = {}
        for i in range(2000):
            v = server.pool_pick('p%d' % i, self.POOL)
            self.assertEqual(v, server.pool_pick('p%d' % i, list(reversed(self.POOL))), 'order does not matter')
            counts[v] = counts.get(v, 0) + 1
        self.assertEqual(set(counts), set(self.POOL))
        self.assertTrue(all(400 < n < 600 for n in counts.values()), counts)
        self.assertIsNone(server.pool_pick('', self.POOL))
        self.assertIsNone(server.pool_pick('x', []))

    def test_editing_a_pool_only_moves_who_had_that_voice(self):
        bigger = self.POOL + ['Kai']
        for i in range(500):
            k = 'p%d' % i
            before, after = server.pool_pick(k, self.POOL), server.pool_pick(k, bigger)
            self.assertIn(after, (before, 'Kai'), k)
            smaller = [v for v in self.POOL if v != 'Hugo']
            if before != 'Hugo':
                self.assertEqual(server.pool_pick(k, smaller), before, k)

    def test_clips_hash_by_file_name(self):
        self.assertEqual(server.pool_identity('C:\\a\\runtime\\voices\\anna.wav'), 'anna.wav')
        self.assertEqual(server.pool_pick('k', ['/x/voices/anna.wav', '/x/voices/bo.wav']),
                         '/x/voices/' + server.pool_pick('k', ['anna.wav', 'bo.wav']), 'moving the folder keeps picks')

    def fake_app(self, eid='kitten', voices=('Jasper', 'Bruno', 'Bella', 'Hugo', 'Leo', 'Luna', 'Rosie')):
        app = PartsTests.fake_app(self)
        app.engine.engine_id = eid
        app.engine.voices = list(voices)
        return app

    FakeSpeaker = PartsTests.FakeSpeaker

    def test_characters_get_pool_voices_and_the_narrator_does_not(self):
        app = self.fake_app()
        app.settings.engine('kitten')['pools'] = {'male': ['Hugo', 'Leo'], 'female': ['Luna', 'Rosie']}
        req = {'speaker': {'id': 'person_1', 'name': 'Ana', 'gender': 'female'},
               'parts': [{'text': 'She nods.', 'role': 'narrator'}, {'text': 'Hello.', 'role': 'female'}]}
        server.App.speak(app, req)
        expect = server.pool_pick('person_1', ['Luna', 'Rosie'])
        self.assertEqual(app.speaker.parts, [('She nods.', 'Jasper'), ('Hello.', expect)])
        server.App.speak(app, req)
        self.assertEqual(app.speaker.parts[1][1], expect, 'same character, same voice')
        req['speaker'] = {'gender': 'female'}  # no id and no name: the slot's voice
        server.App.speak(app, req)
        self.assertEqual(app.speaker.parts[1][1], 'Bella')
        req['speaker'] = {'id': 'person_1'}
        app.settings.data['use_pools'] = False
        server.App.speak(app, req)
        self.assertEqual(app.speaker.parts[1][1], 'Bella', 'pools switched off')

    def test_pool_voices_the_model_lacks_are_skipped(self):
        app = self.fake_app(voices=('Jasper', 'Bruno', 'Bella'))
        app.settings.engine('kitten')['pools'] = {'male': ['Hugo', 'Leo'], 'female': []}
        server.App.speak(app, {'speaker': {'id': 'x'}, 'parts': [{'text': 'Hi.', 'role': 'male'}]})
        self.assertEqual(app.speaker.parts, [('Hi.', 'Bruno')], 'pool of another model: slot voice')

    def test_clip_pools_send_clips(self):
        d = scratch('pool-clips')
        clips = []
        for n in ('anna.wav', 'bea.wav'):
            clips.append(os.path.join(d, n))
            audio.tone(clips[-1], seconds=0.05)
        app = self.fake_app(eid='f5', voices=())
        app.settings.engine('f5')['pools'] = {'male': [], 'female': clips + [os.path.join(d, 'deleted.wav')]}
        server.App.speak(app, {'speaker': {'id': 'k'}, 'parts': [{'text': 'Hi.', 'role': 'female'}]})
        v = app.speaker.parts[0][1]
        self.assertEqual(v, {'ref': server.pool_pick('k', clips), 'ref_text': ''}, 'missing clip dropped, F5 transcribes')

    def test_saved_pools_are_cleaned(self):
        d = scratch('pool-settings')
        p = os.path.join(d, 'settings.json')
        with open(p, 'w') as f:
            json.dump({'engines': {'kitten': {'pools': {'male': ['Hugo', 'Hugo', '', 5, ' Leo ']}}}}, f)
        s = server.Settings(p, False)
        self.assertEqual(s.engine('kitten')['pools'], {'male': ['Hugo', 'Leo'], 'female': []})
        self.assertEqual(s.engine('f5')['pools'], {'male': [], 'female': []}, 'every engine has pools')
        self.assertTrue(s.data['use_pools'])


class ServerTests(unittest.TestCase):
    """The whole app as a subprocess, with the beep engine and no sound."""

    @classmethod
    def setUpClass(cls):
        cls.runtime = scratch('server')
        cls.port = free_port()
        cls.proc = subprocess.Popen([sys.executable, os.path.join(APP, 'server.py'), '--port', str(cls.port),
                                     '--runtime', cls.runtime, '--no-browser', '--test', '--silent'],
                                    stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
        wait_for(lambda: cls._up(), what='server start')

    @classmethod
    def _up(cls):
        try:
            cls.get('/api/status')
            return True
        except OSError:
            return False

    @classmethod
    def tearDownClass(cls):
        try:
            cls.post('/api/shutdown', {})
            cls.proc.wait(10)
        except Exception:  # noqa: BLE001
            cls.proc.kill()

    @classmethod
    def request(cls, method, path, body=None, headers=None, raw=False):
        c = http.client.HTTPConnection('127.0.0.1', cls.port, timeout=10)
        h = {'X-ReadAloud': '1', 'Content-Type': 'application/json'}
        h.update(headers or {})
        data = body if raw else (json.dumps(body).encode() if body is not None else None)
        c.request(method, path, body=data, headers=h)
        r = c.getresponse()
        out = r.status, json.loads(r.read().decode() or '{}')
        c.close()
        return out

    @classmethod
    def get(cls, path, **kw):
        return cls.request('GET', path, **kw)

    @classmethod
    def post(cls, path, body, **kw):
        return cls.request('POST', path, body, **kw)

    def use_test_engine(self):
        code, r = self.post('/api/settings', {'engine': 'test'})
        self.assertEqual(code, 200, r)
        wait_for(lambda: self.get('/api/status')[1]['ready'], what='test engine ready')

    def log_text(self):
        return '\n'.join(l['text'] for l in self.get('/api/state')[1]['log'])

    def test_test_sound_plays_and_is_logged(self):
        code, r = self.post('/api/test-sound', {})
        self.assertEqual(code, 200, r)
        self.assertEqual(r['player'], 'silent')
        wait_for(lambda: 'Played 1 clip(s), 1.0 s of audio, through silent.' in self.log_text(), what='beep played')
        self.assertIn('Sound output: silent', self.log_text())

    def test_status_and_ready_marker(self):
        code, s = self.get('/api/status')
        self.assertEqual(code, 200)
        self.assertIn('speaking', s)
        self.assertTrue(os.path.isfile(os.path.join(self.runtime, 'ready.txt')))

    def test_rejects_other_hosts_and_missing_header(self):
        code, _ = self.get('/api/status', headers={'Host': 'evil.example:%d' % self.port})
        self.assertEqual(code, 403, 'DNS-rebinding style Host')
        c = http.client.HTTPConnection('127.0.0.1', self.port, timeout=5)
        c.request('POST', '/api/speak', body=b'{"text":"x"}', headers={'Content-Type': 'application/json'})
        self.assertEqual(c.getresponse().status, 403, 'POST without the header')
        c.close()

    def test_speak_then_finish(self):
        self.use_test_engine()
        code, r = self.post('/api/speak', {'text': 'One. Two. Three.', 'speaker': {'gender': 'female', 'name': 'Tester'}})
        self.assertTrue(r['ok'], r)
        self.assertTrue(self.get('/api/status')[1]['speaking'])
        wait_for(lambda: not self.get('/api/status')[1]['speaking'], what='speech to finish')
        wait_for(lambda: 'Played 1 clip(s)' in self.log_text(), what='playback logged')

    def test_stop_interrupts_long_speech(self):
        self.use_test_engine()
        self.post('/api/speak', {'text': ' '.join(['Sentence %d is here.' % i for i in range(200)])})
        time.sleep(0.3)
        self.post('/api/stop', {})
        wait_for(lambda: not self.get('/api/status')[1]['speaking'], timeout=3, what='stop')
        leftovers = [f for f in os.listdir(os.path.join(self.runtime, 'tmp')) if f.endswith('.wav')]
        wait_for(lambda: len([f for f in os.listdir(os.path.join(self.runtime, 'tmp'))]) <= 1, timeout=3,
                 what='temp files cleaned (%s)' % leftovers)

    def test_new_request_replaces_old(self):
        self.use_test_engine()
        self.post('/api/speak', {'text': ' '.join(['Old %d.' % i for i in range(100)])})
        _, r = self.post('/api/speak', {'text': 'New.'})
        self.assertTrue(r['ok'])
        wait_for(lambda: not self.get('/api/status')[1]['speaking'], timeout=5, what='only the new text plays')

    def test_settings_and_state(self):
        code, r = self.post('/api/settings', {'volume': 150, 'engines': {'kitten': {'voices': {'male': 'Hugo'}}}})
        self.assertTrue(r['ok'])
        _, st = self.get('/api/state')
        self.assertEqual(st['settings']['volume'], 100, 'clamped')
        self.assertEqual(st['settings']['engines']['kitten']['voices']['male'], 'Hugo')
        self.assertEqual(st['settings']['engines']['kitten']['voices']['female'], 'Bella', 'other voices kept')
        self.assertIn('test', st['engines'], 'test engine visible with --test')
        with open(os.path.join(self.runtime, 'settings.json')) as f:
            saved = json.load(f)
        self.assertEqual(saved['engines']['kitten']['voices']['male'], 'Hugo', 'saved to disk')
        self.assertEqual(saved['port'], 47700, '--port is not saved (the mod expects the saved port)')

    def test_voices_view_for_the_game_menu(self):
        self.use_test_engine()
        _, r = self.post('/api/settings', {'use_pools': False, 'engines': {'test': {'pools': {'male': ['a', 'a', 'b']}}}})
        self.assertTrue(r['ok'])
        self.assertFalse(r['restarted'], 'pool changes need no engine reload')
        code, v = self.get('/api/voices')
        self.assertEqual(code, 200)
        self.assertEqual(v['engine_id'], 'test')
        self.assertEqual(v['state'], 'ready')
        self.assertEqual(v['pools']['male'], ['a', 'b'])
        self.assertFalse(v['use_pools'])
        self.assertEqual(set(v['slots']), {'default', 'male', 'female'})
        self.assertEqual(v['port'], self.port)
        self.post('/api/settings', {'use_pools': True})

    def test_unknown_engine_and_paths_are_refused(self):
        self.assertEqual(self.post('/api/install', {'engine': 'rm -rf'})[0], 400)
        self.assertEqual(self.post('/api/install', {'engine': 'system'})[0], 400, 'builtin is not installable')
        self.assertFalse(self.post('/api/settings', {'engine': 'nope'})[1]['ok'])
        self.assertEqual(self.get('/nope')[0], 404)

    def test_upload_sanitizes_names(self):
        code, r = self.request('POST', '/api/upload?name=..%2F..%2Fevil%20name.wav', body=b'RIFF0000', raw=True)
        self.assertTrue(r['ok'], r)
        self.assertEqual(os.path.dirname(r['path']), os.path.join(self.runtime, 'voices'))
        self.assertEqual(os.path.basename(r['path']), 'evil_name.wav')
        code, r = self.request('POST', '/api/upload?name=x.exe', body=b'MZ', raw=True)
        self.assertEqual(code, 400)

    def test_speak_while_engine_not_ready_says_why(self):
        _, r = self.post('/api/settings', {'engine': 'kitten'})  # not installed in the test runtime
        wait_for(lambda: self.get('/api/status')[1]['state'] == 'error', what='kitten error state')
        _, r = self.post('/api/speak', {'text': 'Hi.'})
        self.assertFalse(r['ok'])
        self.assertIn('error', r['error'])
        self.use_test_engine()

    def test_setup_page_is_served(self):
        c = http.client.HTTPConnection('127.0.0.1', self.port, timeout=5)
        c.request('GET', '/')
        r = c.getresponse()
        body = r.read().decode()
        self.assertEqual(r.status, 200)
        self.assertIn('Read Aloud companion', body)
        self.assertIn('X-ReadAloud', body, 'the page sends the header')
        c.close()


class SecondInstanceTests(unittest.TestCase):
    def test_second_instance_exits_cleanly(self):
        rt = scratch('second')
        port = free_port()
        args = [sys.executable, os.path.join(APP, 'server.py'), '--port', str(port), '--runtime', rt, '--no-browser', '--silent']
        first = subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
        try:
            def up():
                try:
                    socket.create_connection(('127.0.0.1', port), 0.2).close()
                    return True
                except OSError:
                    return False
            wait_for(up, what='first instance')
            second = subprocess.run(args, capture_output=True, timeout=20)
            self.assertEqual(second.returncode, 0)
        finally:
            first.kill()
            first.wait()


if __name__ == '__main__':
    unittest.main()
