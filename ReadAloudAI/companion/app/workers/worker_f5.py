"""F5-TTS worker. Voice = {"ref": clip path, "ref_text": its transcript}; empty uses the clip bundled with F5-TTS."""

import os

from worker_common import log, pick_device, run, write_wav

DEFAULT_TEXT = 'Some call me nature, others call me mother nature.'


class F5:
    def __init__(self):
        self.tts = None
        self.speed = 1.0
        self.default_ref = ''

    def load(self, s):
        from importlib.resources import files
        from f5_tts.api import F5TTS
        device = pick_device(s.get('device'))
        self.tts = F5TTS(device=device)
        self.speed = float(s.get('speed') or 1.0)
        self.default_ref = str(files('f5_tts').joinpath('infer/examples/basic/basic_ref_en.wav'))
        return [], {'device': device}

    def synth(self, text, voice, out):
        voice = voice if isinstance(voice, dict) else {}
        ref = voice.get('ref') or ''
        if ref and not os.path.isfile(ref):
            log('Reference clip not found: %s; using the default voice.' % ref)
            ref = ''
        if ref:
            ref_text = voice.get('ref_text') or ''  # empty: F5-TTS transcribes the clip itself
        else:
            ref, ref_text = self.default_ref, DEFAULT_TEXT
        wav, sr, _ = self.tts.infer(ref_file=ref, ref_text=ref_text, gen_text=text, speed=self.speed)
        write_wav(out, wav, sr)


if __name__ == '__main__':
    run(F5())
