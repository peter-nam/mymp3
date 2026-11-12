"""Validates the BPM the app wrote into its v3 waveform cache.

Method A replicates TempoEstimator.kt exactly, on the same PCM the app decoded.
Method B is an independent detector (different frame size, onset-rectified autocorrelation)
so a porting bug cannot agree with itself.
"""
import array
import json
import math
import sys
import wave
from operator import mul

# Usage: verify_bpm.py [decoded.wav] [v3-cache.json]
#   decoded.wav     PCM the app decoded. Produce it with:
#                   ffmpeg -i song.mp3 -ac 2 -ar 44100 song.wav
#   v3-cache.json   The app's cache file: adb exec-out "run-as com.pnam.mymp3 cat \
#                   /data/data/com.pnam.mymp3/cache/waveform/v3-<md5(uri)>.json"
WAV = sys.argv[1] if len(sys.argv) > 1 else "/tmp/real.wav"
CACHE = sys.argv[2] if len(sys.argv) > 2 else "/tmp/app3.json"

app = json.load(open(CACHE))
print(f"app reports  bpm = {app['bpm']}   bars = {len(app['bars'])}")

w = wave.open(WAV)
rate = w.getframerate()
samples = array.array("h")
samples.frombytes(w.readframes(w.getnframes()))
mono = samples[0::2]  # channel 0, what the app reads
print(f"file: {rate} Hz, {len(mono)/rate:.1f}s, {len(mono)} samples on channel 0")

# ---------------------------------------------------------------- Method A
FRAME = 512  # TempoEstimator is fed frames of this size by WaveformExtractor


def envelope(mono, frame):
    """RMS per `frame` samples -- PcmAnalysis.envelope()."""
    n = len(mono) // frame
    out = [0.0] * n
    for i in range(n):
        seg = mono[i * frame:(i + 1) * frame]
        out[i] = math.sqrt(sum(map(mul, seg, seg)) / len(seg))
    return out


def estimator(env, frame_rate, min_bpm=60.0, max_bpm=180.0, harmonics=4, min_h=3):
    """Port of TempoEstimator.estimate()."""
    n = len(env)
    if n < 32:
        return None, []
    mean = sum(env) / n
    x = [v - mean for v in env]
    if not any(x):
        return None, []
    max_lag = int(round(60.0 / min_bpm * frame_rate * harmonics))
    max_lag = min(max_lag, n - 1)

    # prefix[k] = sum of x[:k]^2, so head/tail energy is O(1) instead of O(n) per lag.
    prefix = [0.0] * (n + 1)
    for i, v in enumerate(x):
        prefix[i + 1] = prefix[i] + v * v
    total = prefix[n]

    acf = [0.0] * (max_lag + 1)
    for lag in range(1, max_lag + 1):
        cross = sum(map(mul, x[lag:], x[:n - lag]))
        head = total - prefix[lag]
        tail = prefix[n - lag]
        den = math.sqrt(head * tail)
        acf[lag] = cross / den if den > 1e-9 else 0.0

    best, best_score, bpm = 0.0, float("-inf"), min_bpm
    while bpm <= max_bpm:
        period = 60.0 / bpm * frame_rate
        score, h = 0.0, 0
        for k in range(1, harmonics + 1):
            lag = int(round(period * k))
            if lag > max_lag:
                break
            score += acf[lag] / k
            h += 1
        if h >= min_h and score > best_score:
            best_score, best = score, bpm
        bpm *= 1.01
    ranked = sorted(range(1, max_lag + 1), key=lambda l: -acf[l])[:8]
    return (best if best > 0 else None), [(l, 60.0 * frame_rate / l, acf[l]) for l in ranked]


env_a = envelope(mono, FRAME)
print(f"\nMethod A: envelope {len(env_a)} frames @ {rate/FRAME:.1f} fps (same as the app)")
bpm_a, ranked = estimator(env_a, rate / FRAME)
print(f"  replica of TempoEstimator  -> {bpm_a:.2f} BPM   (app cached {app['bpm']})")
print("  strongest raw autocorrelation lags:")
for lag, bpm, score in ranked[:6]:
    print(f"    lag {lag:4d} -> {bpm:6.1f} BPM   acf={score:+.3f}")

# ---------------------------------------------------------------- Method B
FRAME_B = 1024  # different frame size, and autocorrelate onsets, not level


def onsets(mono, frame):
    """Half-wave rectified positive slope of the level envelope = note onsets."""
    e = envelope(mono, frame)
    return [max(0.0, e[i] - e[i - 1]) for i in range(1, len(e))]


def best_period_periodogram(sig, frame_rate, min_bpm=50.0, max_bpm=200.0):
    n = len(sig)
    mean = sum(sig) / n
    x = [v - mean for v in sig]
    prefix = [0.0] * (n + 1)
    for i, v in enumerate(x):
        prefix[i + 1] = prefix[i] + v * v
    total = prefix[n]
    lo = int(round(60.0 / max_bpm * frame_rate))
    hi = int(round(60.0 / min_bpm * frame_rate))
    best, best_score = None, float("-inf")
    for lag in range(max(1, lo), min(hi, n - 1) + 1):
        cross = sum(map(mul, x[lag:], x[:n - lag]))
        den = math.sqrt((total - prefix[lag]) * prefix[n - lag])
        score = cross / den if den > 1e-9 else 0.0
        if score > best_score:
            best_score, best = score, lag
    return 60.0 * frame_rate / best, best_score


env_b = onsets(mono, FRAME_B)
bpm_b, score_b = best_period_periodogram(env_b, rate / FRAME_B)
print(f"\nMethod B: onset autocorrelation, {len(env_b)} frames @ {rate/FRAME_B:.1f} fps")
print(f"  independent detector       -> {bpm_b:.2f} BPM   (acf={score_b:+.3f})")

ratio = bpm_a / bpm_b if bpm_b else 0
print(f"\nA/B ratio {ratio:.2f}  (1.0 agree | ~2.0 or ~0.5 = one of them is an octave error)")
if abs(bpm_a - bpm_b) < 3:
    print("VERDICT: agree -- the app's tempo estimate is confirmed")
elif abs(abs(math.log2(ratio)) - 1.0) < 0.12:
    print("VERDICT: agree within an octave -- check the harmonic weighting")
else:
    print("VERDICT: disagree -- needs a look")
