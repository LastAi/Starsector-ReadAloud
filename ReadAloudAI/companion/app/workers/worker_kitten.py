"""Kitten TTS worker (package "kittenml"). Voice = a built-in speaker name.

Model generations differ: kitten-tts-2 takes a device and has a sample_rate; the
0.8 models take neither and run at 24 kHz. generate() options are passed only when
the loaded model accepts them.
"""

import inspect

from worker_common import log, pick_device, run, write_wav


class Kitten:
    def __init__(self):
        self.model = None
        self.voices = []
        self.rate = 24000
        self.options = {}

    def load(self, s):
        from kittenml import KittenTTS
        name = s.get('model') or 'KittenML/kitten-tts-mini-0.8'
        device = pick_device(s.get('device'))
        try:
            self.model = KittenTTS(name, device=device)
        except TypeError:  # models without the device argument
            self.model = KittenTTS(name)
        self.voices = list(getattr(self.model, 'available_voices', None) or [])
        self.rate = int(getattr(self.model, 'sample_rate', 24000) or 24000)
        try:
            accepted = set(inspect.signature(self.model.generate).parameters)
        except (TypeError, ValueError):
            accepted = set()
        wanted = {'speed': float(s.get('speed') or 1.0), 'clean_text': True}  # clean_text spells out numbers
        self.options = {k: v for k, v in wanted.items() if k in accepted}
        return self.voices, {'device': device, 'model': name}

    def synth(self, text, voice, out):
        kwargs = dict(self.options)
        if isinstance(voice, str) and voice:
            if self.voices and voice not in self.voices:
                log('Voice "%s" is not in this model; using its default voice.' % voice)
            else:
                kwargs['voice'] = voice
        audio = self.model.generate(text, **kwargs)
        write_wav(out, audio, self.rate)


if __name__ == '__main__':
    run(Kitten())
