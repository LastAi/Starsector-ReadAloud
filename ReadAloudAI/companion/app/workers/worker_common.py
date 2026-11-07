"""Shared loop for engine workers (runs inside the engine's own venv).

Protocol, one JSON object per line (see PROTOCOL.md):
  in : {"op":"load","settings":{...}}  {"op":"synth","id":1,"text":"...","voice":...,"out":"x.wav"}  {"op":"quit"}
  out: {"event":"loaded","voices":[...],"info":{...}}  {"event":"done","id":1}
       {"event":"error","id":1|null,"message":"..."}    {"event":"log","message":"..."}

Libraries love printing to stdout, which would corrupt the protocol. So the real
stdout is kept for protocol lines only, and file descriptor 1 is pointed at stderr
before any library is imported.
"""

import json
import os
import sys
import traceback

_proto = os.fdopen(os.dup(1), 'w', encoding='utf-8', buffering=1)
os.dup2(2, 1)
sys.stdout = sys.stderr


def send(**msg):
    _proto.write(json.dumps(msg) + '\n')
    _proto.flush()


def log(message):
    send(event='log', message=str(message))


def pick_device(wanted):
    """'auto' -> cuda if available, else mps (Mac), else cpu."""
    if wanted and wanted != 'auto':
        return wanted
    try:
        import torch
        if torch.cuda.is_available():
            return 'cuda'
        if getattr(torch.backends, 'mps', None) and torch.backends.mps.is_available():
            return 'mps'
    except Exception:  # noqa: BLE001
        pass
    return 'cpu'


def write_wav(path, samples, rate):
    """Writes a float array as 16-bit PCM, which every player (and the volume control) handles."""
    import numpy as np
    import soundfile as sf
    data = np.asarray(samples, dtype='float32').reshape(-1)
    sf.write(path, data, int(rate), subtype='PCM_16')


def run(engine):
    """engine.load(settings) -> (voices, info); engine.synth(text, voice, out)."""
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except ValueError:
            continue
        op = req.get('op')
        if op == 'quit':
            return
        try:
            if op == 'load':
                settings = req.get('settings') or {}
                log('Loading the model (the first time, it downloads; this can take several minutes)...')
                voices, info = engine.load(settings)
                warm = settings.get('warmup_out')
                if warm:
                    # The first generation on a GPU is much slower (kernel setup); pay it now, not on the player's first line.
                    try:
                        engine.synth('Ready.', None, warm)
                    except Exception as e:  # noqa: BLE001
                        log('Warm-up failed (speech may still work): %s' % e)
                    finally:
                        try:
                            os.remove(warm)
                        except OSError:
                            pass
                send(event='loaded', voices=list(voices or []), info=info or {})
            elif op == 'synth':
                engine.synth(req.get('text', ''), req.get('voice'), req['out'])
                send(event='done', id=req.get('id'))
        except Exception as e:  # noqa: BLE001 - reported over the protocol
            sys.stderr.write(traceback.format_exc())
            send(event='error', id=req.get('id') if op == 'synth' else None, message='%s: %s' % (type(e).__name__, e))
