"""Run PitchSense's pitch analysis on WAV files outside the app, to investigate odd contours.

Mirrors app/src/main/java/.../pitch: resample to 16 kHz, 70 Hz high-pass, YIN candidates
(65-800 Hz, 10 ms hop), silence gate at 3% of peak, Viterbi tracking. Keep the constants in sync.

    python3 tools/pitch_check.py recording.wav [--from 2.7 --to 3.3]

Prints per-file stats and, for the given range, each frame's F0 and its best candidates
(Hz/cost), so missing or jumping frames can be traced to the evidence YIN saw.
Synthetic test files: python3 tools/pitch_check.py --synth OUT_DIR
"""
import argparse
import os
import wave

import numpy as np
from numpy.lib.stride_tricks import sliding_window_view

RATE = 16_000
HOP = 160
MIN_F, MAX_F = 65.0, 800.0
HIGH_PASS_HZ = 70.0
SILENCE_RATIO = 0.03
MAX_COST = 0.8
LAG_BIAS = 0.1
UNVOICED_COST = 0.5
SWITCH_COST = 0.6
COST_PER_SEMITONE = 0.08
MAX_STEP_SEMITONES = 6.0

MIN_LAG = max(2, int(np.floor(RATE / MAX_F)))
MAX_LAG = int(np.ceil(RATE / MIN_F))
W = MAX_LAG
FRAME = W + MAX_LAG + 2


def read_wav(path):
    with wave.open(path) as w:
        assert w.getsampwidth() == 2, "16-bit PCM only"
        raw = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float64) / 32768
        return raw.reshape(-1, w.getnchannels()).mean(axis=1), w.getframerate()


def resample(x, src):
    if src == RATE:
        return x
    ratio = src / RATE
    half = 31
    n = np.arange(-half, half + 1)
    cutoff = 0.45 / ratio
    kernel = np.where(n == 0, 2 * cutoff, np.sin(2 * np.pi * cutoff * n) / (np.pi * np.where(n == 0, 1, n)))
    kernel *= 0.54 - 0.46 * np.cos(2 * np.pi * np.arange(2 * half + 1) / (2 * half))
    kernel /= kernel.sum()
    filtered = np.convolve(x, kernel, mode="same")
    pos = np.arange(int(len(x) / ratio)) * ratio
    i = pos.astype(int)
    nxt = np.minimum(i + 1, len(x) - 1)
    return filtered[i] + (filtered[nxt] - filtered[i]) * (pos - i)


def high_pass(x, fc=HIGH_PASS_HZ, q=0.7071):
    w0 = 2 * np.pi * fc / RATE
    alpha = np.sin(w0) / (2 * q)
    c = np.cos(w0)
    a0 = 1 + alpha
    b0, b1, a1, a2 = (1 + c) / 2 / a0, -(1 + c) / a0, -2 * c / a0, (1 - alpha) / a0
    y = np.zeros_like(x)
    x1 = x2 = y1 = y2 = 0.0
    for i, v in enumerate(x):
        out = b0 * v + b1 * x1 + b0 * x2 - a1 * y1 - a2 * y2
        x2, x1, y2, y1 = x1, v, y1, out
        y[i] = out
    return y


def candidates(y):
    """Per frame: (time, [(hz, cost)]); gated frames have no candidates."""
    gate = np.abs(y).max() * SILENCE_RATIO
    frames = []
    for k in range((len(y) - FRAME) // HOP + 1 if len(y) >= FRAME else 0):
        seg = y[k * HOP:k * HOP + FRAME]
        t = (k * HOP + W / 2) / RATE
        if np.sqrt(np.mean(seg[:W] ** 2)) < gate:
            frames.append((t, []))
            continue
        win = sliding_window_view(seg, W)[: MAX_LAG + 2]
        d = np.concatenate([[0.0], ((win[0] - win[1:]) ** 2).sum(axis=1)])
        run = np.cumsum(d[1:])
        cm = np.ones_like(d)
        cm[1:] = np.where(run == 0, 1, d[1:] * np.arange(1, len(d)) / np.where(run == 0, 1, run))
        c = []
        for tau in range(MIN_LAG, MAX_LAG + 1):
            if cm[tau] < MAX_COST and cm[tau] <= cm[tau - 1] and cm[tau] < cm[tau + 1]:
                den = cm[tau - 1] - 2 * cm[tau] + cm[tau + 1]
                ft = tau + (0.5 * (cm[tau - 1] - cm[tau + 1]) / den if den else 0)
                c.append((RATE / ft, cm[tau] + LAG_BIAS * tau / MAX_LAG))
        frames.append((t, c))
    return frames


def transition(a, b):
    if a is None and b is None:
        return 0.0
    if a is None or b is None:
        return SWITCH_COST
    st = abs(12 * np.log2(b / a))
    return COST_PER_SEMITONE * st if st <= MAX_STEP_SEMITONES else np.inf


def track(frames):
    if not frames:
        return []
    states = [[(None, UNVOICED_COST)] + c for _, c in frames]
    cost = [e for _, e in states[0]]
    back = []
    for prev, cur in zip(states, states[1:]):
        new, bp = [], []
        for hz, e in cur:
            totals = [cost[k] + transition(phz, hz) for k, (phz, _) in enumerate(prev)]
            k = int(np.argmin(totals))
            new.append(totals[k] + e)
            bp.append(k)
        cost = new
        back.append(bp)
    j = int(np.argmin(cost))
    path = [j]
    for bp in reversed(back):
        j = bp[j]
        path.append(j)
    return [states[i][j][0] for i, j in enumerate(reversed(path))]


def analyze(path):
    x, src = read_wav(path)
    frames = candidates(high_pass(resample(x, src)))
    return frames, track(frames)


def synth(out_dir):
    """Writes voice-like test signals (harmonic tone + hum/noise) that stress the tracker."""
    os.makedirs(out_dir, exist_ok=True)
    rng = np.random.default_rng(0)
    t = np.arange(RATE) / RATE

    def voice(f0, hum=0.0, noise=0.0):
        ph = np.cumsum(2 * np.pi * np.nan_to_num(f0) / RATE)
        x = sum(np.sin(k * ph) / k for k in range(1, 8)) * 0.3 * ~np.isnan(f0)
        return x + hum * np.sin(2 * np.pi * 60 * t) + noise * rng.standard_normal(len(t))

    fall = np.where((t > 0.2) & (t < 0.6), 390 * (155 / 390) ** np.clip((t - 0.3) / 0.2, 0, 1), np.nan)
    signals = {
        "fast_fall_hum.wav": voice(fall, hum=0.03, noise=0.01),
        "low_70hz.wav": voice(np.where((t > 0.2) & (t < 0.8), 70.0, np.nan), noise=0.005),
    }
    for name, x in signals.items():
        with wave.open(os.path.join(out_dir, name), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())
        print(os.path.join(out_dir, name))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("wav", nargs="*")
    ap.add_argument("--from", dest="start", type=float)
    ap.add_argument("--to", dest="end", type=float)
    ap.add_argument("--synth", metavar="OUT_DIR")
    args = ap.parse_args()
    if args.synth:
        synth(args.synth)
    for path in args.wav:
        frames, f0 = analyze(path)
        voiced = sum(f is not None for f in f0)
        jumps = sum(1 for a, b in zip(f0, f0[1:]) if a and b and abs(12 * np.log2(b / a)) > 4)
        gated = sum(not c for _, c in frames)
        print(f"{path}: {len(frames)} frames, voiced {voiced}, gated {gated}, jumps >4 st {jumps}")
        if args.start is None and args.end is None:
            continue
        for (t, c), f in zip(frames, f0):
            if (args.start or 0) <= t <= (args.end if args.end is not None else np.inf):
                best = " ".join(f"{hz:.0f}/{cost:.2f}" for hz, cost in sorted(c, key=lambda a: a[1])[:4])
                print(f"  {t:6.2f}  {'-' if f is None else f'{f:5.0f}':>5}   {best or '(gated or no dip)'}")


if __name__ == "__main__":
    main()
