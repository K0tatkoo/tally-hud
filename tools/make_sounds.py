#!/usr/bin/env python3
"""Generate Tally's cockpit sounds.

Everything here is synthesised: the tones with numpy, the voice callouts with a speech synthesiser
(then band-limited and clipped to sound like a cockpit voice-warning unit). No recordings of real
aircraft are used.

The tones are shared by every edition and go to src/main/resources/assets/tally/sounds/. The voice
callouts belong to an edition, go to src/<edition>/resources/assets/tally/sounds/, and are spoken by
the engine named in src/<edition>/edition.properties ("voice="):

    say:<voice>     macOS `say`. The same text always gives the same sound.
    piper:<voice>   Piper TTS (https://github.com/OHF-Voice/piper1-gpl); pass the .onnx model with
                    --piper-model, or put it in the folder named by $PIPER_VOICES. Piper adds random
                    variation, so every run speaks a little differently and some takes are mumbled.

Takes (Piper only): when tools/voice/<edition>/<sound>.flac exists, that recording of the bare word
is used instead of synthesising it again, so the committed callouts can be rebuilt exactly (and the
filter retuned) without re-rolling the voice. A missing take is synthesised and saved there, so to
re-roll one, delete its file and run again; --new-takes re-rolls them all. Listen to new takes (the
public edition's were also checked with a speech recogniser, whisper.cpp) and re-roll unclear ones.

Needs: numpy, soundfile (pip), and, unless every take is stored, the edition's speech engine.

    python3 tools/make_sounds.py --edition public --voice-only
    python3 tools/make_sounds.py --edition public --voice-only --new-takes --piper-model path/to/en_US-kristin-medium.onnx
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile

import numpy as np
import soundfile as sf

SR = 44100
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SHARED_OUT = os.path.join(ROOT, "src", "main", "resources", "assets", "tally", "sounds")

# How each engine's voice is made to sound like a cockpit voice unit: band-pass corners (Hz) and how
# hard it is driven into the clipper. Piper's voice loses its consonants under the harder setting.
FILTER = {"say": (380, 3300, 2.2), "piper": (250, 4200, 1.5)}

# Set from the command line in main().
ENGINE = None
VOICE = None
PIPER = None
PIPER_MODEL = None
TAKES = None
NEW_TAKES = False


def edition_voice(edition):
    path = os.path.join(ROOT, "src", edition, "edition.properties")
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line.startswith("voice="):
                engine, _, voice = line[len("voice="):].partition(":")
                return engine, voice
    sys.exit(f"{path} has no voice= line")


def t(dur):
    return np.arange(int(SR * dur)) / SR


def env(x, attack=0.004, release=0.012):
    """Short fades so nothing clicks."""
    n = len(x)
    a = min(int(SR * attack), n // 2)
    r = min(int(SR * release), n // 2)
    e = np.ones(n)
    if a:
        e[:a] = np.linspace(0, 1, a)
    if r:
        e[-r:] = np.linspace(1, 0, r)
    return x * e


def tone(freq, dur, amp=0.5, harmonics=(1.0,)):
    x = np.zeros(int(SR * dur))
    tt = t(dur)
    for i, h in enumerate(harmonics, start=1):
        x += h * np.sin(2 * np.pi * freq * i * tt)
    x /= max(1e-9, sum(abs(h) for h in harmonics))
    return env(x * amp)


def sweep(f0, f1, dur, amp=0.5):
    tt = t(dur)
    f = f0 + (f1 - f0) * (tt / dur)
    phase = 2 * np.pi * np.cumsum(f) / SR
    return env(np.sin(phase) * amp)


def gap(dur):
    return np.zeros(int(SR * dur))


def cat(*parts):
    return np.concatenate(parts)


def bandlimit(x, lo, hi):
    """Smooth-edged band-pass in the frequency domain."""
    spec = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    mask = 1 / (1 + (lo / np.maximum(f, 1)) ** 6) * 1 / (1 + (f / hi) ** 6)
    return np.fft.irfft(spec * mask, len(x))


def normalise(x, peak=0.7):
    m = np.max(np.abs(x))
    return x if m < 1e-9 else x * (peak / m)


def take_path(name):
    return os.path.join(TAKES, name + ".flac") if TAKES else None


def speak(name, text, rate, fresh=False):
    """The bare phrase as (samples, sample rate). `rate` is in words per minute."""
    take = take_path(name)
    if take and os.path.isfile(take) and not (NEW_TAKES or fresh):
        return sf.read(take)
    with tempfile.TemporaryDirectory() as d:
        if ENGINE == "say":
            path = os.path.join(d, "v.aiff")
            subprocess.run(["say", "-v", VOICE, "-r", str(rate), "-o", path, text], check=True)
        elif ENGINE == "piper":
            path = os.path.join(d, "v.wav")
            # Piper's natural pace is about 175 words per minute; a shorter phoneme length is quicker.
            # Less random variation than Piper's default (0.667 / 0.8) articulates more clearly.
            subprocess.run([PIPER, "-m", PIPER_MODEL, "-f", path, "--length-scale", f"{175 / rate:.2f}",
                            "--noise-scale", "0.4", "--noise-w-scale", "0.4"],
                           input=text.encode("utf-8"), check=True, capture_output=True)
        else:
            sys.exit(f"unknown speech engine {ENGINE!r}")
        data, sr = sf.read(path)
    if take:
        os.makedirs(TAKES, exist_ok=True)
        sf.write(take, data, sr, format="FLAC")
    return data, sr


def say(name, text, rate=185, fresh=False):
    data, sr = speak(name, text, rate, fresh)
    if data.ndim > 1:
        data = data.mean(axis=1)
    # trim silence
    idx = np.where(np.abs(data) > 0.01)[0]
    if len(idx):
        data = data[max(0, idx[0] - 200): idx[-1] + 400]
    # resample to SR
    n = int(len(data) * SR / sr)
    data = np.interp(np.linspace(0, len(data) - 1, n), np.arange(len(data)), data)
    # cockpit voice unit: narrow band, driven a little hard
    lo, hi, drive = FILTER[ENGINE]
    data = bandlimit(data, lo, hi)
    data = np.tanh(normalise(data, 1.0) * drive)
    return env(normalise(data, 0.75), 0.003, 0.02)


def callout(name, word, pause=0.14, rate=185, max_len=None):
    """The word twice. With max_len (seconds) it is spoken faster until the pair fits in that time."""
    stored = take_path(name) and os.path.isfile(take_path(name)) and not NEW_TAKES
    fresh = False
    for _ in range(5):
        v = say(name, word, rate, fresh)
        out = cat(v, gap(pause), v)
        if max_len is None or len(out) / SR <= max_len:
            return out
        if stored:
            sys.exit(f"the stored take of {name} is too long for {max_len} s: delete it to make a new one")
        rate = int(rate * (len(out) / SR) / max_len * 1.03) + 1
        fresh = True
    sys.exit(f"could not fit {word!r} into {max_len} s")


def tones():
    s = {}

    # --- tones ---------------------------------------------------------
    chirp = tone(2200, 0.055, 0.45)
    steady = tone(2650, 0.42, 0.42) * (1 - 0.18 * (0.5 + 0.5 * np.sin(2 * np.pi * 22 * t(0.42))))
    s["lock"] = cat(chirp, gap(0.04), chirp, gap(0.05), env(steady))

    s["unlock"] = cat(tone(1250, 0.08, 0.4), gap(0.02), tone(830, 0.11, 0.4))

    buzz = np.sign(np.sin(2 * np.pi * 170 * t(0.13))) * 0.22
    s["no_target"] = env(bandlimit(buzz, 80, 2500))

    blip = tone(3050, 0.04, 0.4)
    s["contact"] = cat(blip, gap(0.045), blip, gap(0.045), blip)

    tt = t(0.6)
    warble_f = np.where(np.sin(2 * np.pi * 11 * tt) > 0, 2100.0, 1450.0)
    s["spike"] = env(np.sin(2 * np.pi * np.cumsum(warble_f) / SR) * 0.42)

    horn = tone(415, 0.36, 0.5, harmonics=(1.0, 0.6, 0.45, 0.25, 0.15))
    s["stall"] = cat(horn, gap(0.06))

    d1, d2 = tone(740, 0.085, 0.42), tone(1040, 0.085, 0.42)
    s["caution"] = cat(d1, d2, d1, d2)

    s["whoop"] = cat(sweep(420, 1500, 0.3, 0.5), gap(0.06), sweep(420, 1500, 0.3, 0.5))

    s["hud_on"] = cat(tone(900, 0.03, 0.22), sweep(1100, 1900, 0.09, 0.28))
    s["hud_off"] = cat(sweep(1700, 900, 0.1, 0.26))

    return s


def voices():
    # The longest each may run: Warnings.java keeps the voice channel busy for this long (in ticks,
    # 20 a second) and repeats "pull up" every 21 ticks, so a longer file would talk over itself.
    return {
        "pull_up": callout("pull_up", "Pull up!", 0.1, 195, max_len=0.92),
        "altitude": callout("altitude", "Altitude.", 0.16, max_len=1.52),
        # Piper's voices swallow the first syllable ("train"); its [[ ]] takes phonemes, stressed TERR-ain.
        "terrain": callout("terrain", "[[ tˈɛɹeɪn ]]" if ENGINE == "piper" else "Terrain.", 0.16, max_len=1.13),
        "caution_voice": callout("caution_voice", "Caution.", 0.16, max_len=1.18),
        "warning_voice": callout("warning_voice", "Warning.", 0.16, max_len=1.13),
        "bingo": callout("bingo", "Bingo.", 0.16, max_len=1.08),
    }


def write(sounds, out):
    os.makedirs(out, exist_ok=True)
    for name, data in sounds.items():
        data = np.clip(data, -1, 1).astype(np.float32)
        path = os.path.join(out, name + ".ogg")
        sf.write(path, data, SR, format="OGG", subtype="VORBIS")
        print(f"{name:16s} {len(data) / SR:5.2f}s  {os.path.getsize(path):6d} B  {os.path.relpath(path, ROOT)}")


def main():
    global ENGINE, VOICE, PIPER, PIPER_MODEL, TAKES, NEW_TAKES
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--edition", default="personal" if os.path.isdir(os.path.join(ROOT, "src", "personal")) else "public")
    ap.add_argument("--voice-only", action="store_true", help="leave the shared tones alone")
    ap.add_argument("--piper", default=shutil.which("piper") or "piper", help="the piper executable")
    ap.add_argument("--piper-model", help="the voice's .onnx file")
    ap.add_argument("--new-takes", action="store_true", help="synthesise the voice again and store the new takes")
    a = ap.parse_args()

    ENGINE, VOICE = edition_voice(a.edition)
    PIPER = a.piper
    TAKES = os.path.join(ROOT, "tools", "voice", a.edition) if ENGINE == "piper" else None
    NEW_TAKES = a.new_takes
    stored = TAKES and all(os.path.isfile(os.path.join(TAKES, n + ".flac"))
                           for n in ("pull_up", "altitude", "terrain", "caution_voice", "warning_voice", "bingo"))
    if ENGINE == "piper" and (NEW_TAKES or not stored):
        PIPER_MODEL = a.piper_model or os.path.join(os.environ.get("PIPER_VOICES", "."), VOICE + ".onnx")
        if not os.path.isfile(PIPER_MODEL):
            sys.exit(f"Piper voice model not found: {PIPER_MODEL} (pass --piper-model)")
    print(f"edition {a.edition}: voice by {ENGINE} ({VOICE})")

    if not a.voice_only:
        write(tones(), SHARED_OUT)
    write(voices(), os.path.join(ROOT, "src", a.edition, "resources", "assets", "tally", "sounds"))


if __name__ == "__main__":
    sys.exit(main())
