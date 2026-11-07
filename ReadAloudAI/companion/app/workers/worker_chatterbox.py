"""Chatterbox worker. Voice = {"ref": clip path}; empty uses the model's built-in voice."""

import os

from worker_common import log, pick_device, run, write_wav


class Chatterbox:
    def __init__(self):
        self.model = None
        self.variant = 'standard'
        self.exaggeration = 0.5
        self.cfg_weight = 0.5

    def load(self, s):
        device = pick_device(s.get('device'))
        self.variant = s.get('variant') or 'standard'
        self.exaggeration = float(s.get('exaggeration', 0.5))
        self.cfg_weight = float(s.get('cfg_weight', 0.5))
        if self.variant == 'turbo':
            from chatterbox.tts_turbo import ChatterboxTurboTTS
            self.model = ChatterboxTurboTTS.from_pretrained(device=device)
        else:
            from chatterbox.tts import ChatterboxTTS
            self.model = ChatterboxTTS.from_pretrained(device=device)
        return [], {'device': device, 'variant': self.variant}

    def synth(self, text, voice, out):
        voice = voice if isinstance(voice, dict) else {}
        ref = voice.get('ref') or None
        if ref and not os.path.isfile(ref):
            log('Reference clip not found: %s; using the built-in voice.' % ref)
            ref = None
        if self.variant == 'turbo':
            wav = self.model.generate(text, audio_prompt_path=ref)
        else:
            wav = self.model.generate(text, audio_prompt_path=ref, exaggeration=self.exaggeration, cfg_weight=self.cfg_weight)
        write_wav(out, wav.squeeze().detach().cpu().numpy(), self.model.sr)


if __name__ == '__main__':
    run(Chatterbox())
