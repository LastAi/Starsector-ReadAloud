"""WAV helpers and a stoppable player. Standard library only.

Windows plays through winsound (built in). Linux tries paplay, pw-play, aplay, then
ffplay. macOS uses afplay.
"""

import array
import math
import os
import shutil
import subprocess
import sys
import threading
import time
import wave


def duration(path):
    with wave.open(path, 'rb') as w:
        return w.getnframes() / float(w.getframerate() or 1)


def set_volume(path, volume):
    """Scales a 16-bit PCM WAV in place. volume: 0..100 (100 = unchanged)."""
    if volume >= 100:
        return
    with wave.open(path, 'rb') as w:
        params = w.getparams()
        frames = w.readframes(w.getnframes())
    if params.sampwidth != 2:
        return  # only 16-bit is scaled; other widths play at full volume
    samples = array.array('h')
    samples.frombytes(frames)
    if sys.byteorder == 'big':
        samples.byteswap()
    f = max(0.0, volume / 100.0)
    for i in range(len(samples)):
        samples[i] = int(samples[i] * f)
    if sys.byteorder == 'big':
        samples.byteswap()
    with wave.open(path, 'wb') as w:
        w.setparams(params)
        w.writeframes(samples.tobytes())


def write_pcm16(path, samples, rate):
    """Writes floats in -1..1 (any iterable) as a mono 16-bit WAV."""
    data = array.array('h', (max(-32767, min(32767, int(s * 32767))) for s in samples))
    if sys.byteorder == 'big':
        data.byteswap()
    with wave.open(path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(data.tobytes())


def tone(path, seconds=0.3, freq=440.0, rate=16000):
    """A short sine beep. Used by the test engine."""
    n = int(seconds * rate)
    write_pcm16(path, (0.3 * math.sin(2 * math.pi * freq * i / rate) for i in range(n)), rate)


class Player:
    """Plays one WAV at a time; stop() interrupts it from any thread."""

    def __init__(self, silent=False):
        self.silent = silent  # tests: "play" by waiting for the clip's length, no sound
        self._stop = threading.Event()
        self._proc = None
        self._lock = threading.Lock()
        self.backend = 'silent' if silent else self._detect()

    @staticmethod
    def _detect():
        if sys.platform == 'win32':
            return 'winsound'
        if sys.platform == 'darwin':
            return 'afplay'
        for p in ('paplay', 'pw-play', 'aplay', 'ffplay'):
            if shutil.which(p):
                return p
        return 'none'

    def play(self, path):
        """Blocks until the clip ends or stop() is called. Returns False if stopped."""
        self._stop.clear()
        length = duration(path)
        if self.backend in ('silent', 'none'):
            return not self._stop.wait(length if self.backend == 'silent' else 0)
        if self.backend == 'winsound':
            import winsound
            winsound.PlaySound(path, winsound.SND_FILENAME | winsound.SND_ASYNC | winsound.SND_NODEFAULT)
            stopped = self._stop.wait(length + 0.05)
            if stopped:
                winsound.PlaySound(None, 0)
            return not stopped
        cmd = {
            'afplay': ['afplay', path],
            'paplay': ['paplay', path],
            'pw-play': ['pw-play', path],
            'aplay': ['aplay', '-q', path],
            'ffplay': ['ffplay', '-nodisp', '-autoexit', '-loglevel', 'quiet', path],
        }[self.backend]
        with self._lock:
            self._proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        proc = self._proc
        while proc.poll() is None:
            if self._stop.wait(0.05):
                proc.terminate()
                return False
        return True

    def stop(self):
        self._stop.set()
        with self._lock:
            p = self._proc
        if p is not None and p.poll() is None:
            try:
                p.terminate()
            except OSError:
                pass


def describe_output(player):
    """One line about where sound goes, for the log."""
    if player.backend == 'winsound':
        try:
            import ctypes
            n = ctypes.windll.winmm.waveOutGetNumDevs()
        except Exception:  # noqa: BLE001
            return 'winsound (Windows default output device)'
        if n == 0:
            return 'winsound, but Windows reports NO sound output device: nothing can be heard'
        return ('winsound, to the Windows default output device (%d device(s)). If nothing is heard, check that '
                '"Python" is not muted or sent to another device in the Volume Mixer.' % n)
    if player.backend == 'none':
        return 'none found (install pulseaudio-utils, pipewire, alsa-utils or ffmpeg): nothing can be heard'
    return player.backend


def safe_remove(path):
    for _ in range(5):
        try:
            os.remove(path)
            return
        except FileNotFoundError:
            return
        except OSError:
            time.sleep(0.1)  # Windows: the player may still hold the file for a moment
