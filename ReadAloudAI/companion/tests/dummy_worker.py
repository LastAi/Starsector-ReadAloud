"""A worker that behaves like a real one but needs no TTS library. Used by the tests.

It deliberately prints to stdout (as many ML libraries do) to prove the protocol
channel stays clean.
"""

import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'workers'))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app'))

from worker_common import run  # noqa: E402


class Dummy:
    def load(self, s):
        print('noisy library banner on stdout')
        os.write(1, b'and a C-level write to fd 1\n')
        if s.get('fail_load'):
            raise RuntimeError('load failed on purpose')
        return ['Alpha', 'Beta'], {'device': 'none'}

    def synth(self, text, voice, out):
        print('synthesizing', text)
        if 'explode' in text:
            raise ValueError('bad text')
        import audio
        audio.tone(out, seconds=0.05)


if __name__ == '__main__':
    run(Dummy())
