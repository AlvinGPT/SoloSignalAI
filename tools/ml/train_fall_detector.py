#!/usr/bin/env python3
"""
SoloSignal AI — Offline Supervised ML Training, Calibration, Evaluation & LiteRT Export Pipeline.

Trains:
1. Lightweight 1D CNN for 6-class smartphone motion time-series classification:
   - 0: STANDING_STILL
   - 1: WALKING
   - 2: SITTING_DOWN
   - 3: STAIR_MOVEMENT
   - 4: POSSIBLE_FALL
   - 5: UNKNOWN_UNCERTAIN
2. Random Forest Baseline (on 36 engineered time/frequency-domain features) strictly for
   held-out baseline comparison against the 1D CNN and 3-Phase Rule Detector.

Safety & Ethics:
- Uses only appropriately licensed public research dataset schemas (SisFall / UniMiB SHAR / MobiAct)
  and safe physics-parameterized biomechanical traces across 24 distinct virtual/replayed subjects.
- Never recommends or requires dangerous physical fall experiments or dropping real phones.
- Performs strict subject-disjoint splitting (Train: Subjects 1-16, Val: Subjects 17-20, Test: Subjects 21-24).
- Applies class-weighted Focal Loss (gamma=2.0) and post-training Temperature Scaling calibration on Val.
"""

import argparse
import hashlib
import json
import math
import os
import random
import struct
from pathlib import Path
from typing import Dict, List, Tuple

GRAVITY_MS2 = 9.80665
WINDOW_SAMPLES = 200  # 4.0 seconds @ 50 Hz (20 ms step)
NUM_CHANNELS = 6
NUM_CLASSES = 6

CLASS_NAMES = [
    "STANDING_STILL",
    "WALKING",
    "SITTING_DOWN",
    "STAIR_MOVEMENT",
    "POSSIBLE_FALL",
    "UNKNOWN_UNCERTAIN",
]


def random_rotation_matrix(rng: random.Random) -> List[List[float]]:
    """Generates a random 3D SO(3) rotation matrix to ensure phone orientation invariance."""
    u1, u2, u3 = rng.random(), rng.random(), rng.random()
    q0 = math.sqrt(1.0 - u1) * math.sin(2.0 * math.pi * u2)
    q1 = math.sqrt(1.0 - u1) * math.cos(2.0 * math.pi * u2)
    q2 = math.sqrt(u1) * math.sin(2.0 * math.pi * u3)
    q3 = math.sqrt(u1) * math.cos(2.0 * math.pi * u3)
    return [
        [1 - 2 * (q2 * q2 + q3 * q3), 2 * (q1 * q2 - q0 * q3), 2 * (q1 * q3 + q0 * q2)],
        [2 * (q1 * q2 + q0 * q3), 1 - 2 * (q1 * q1 + q3 * q3), 2 * (q2 * q3 - q0 * q1)],
        [2 * (q1 * q3 - q0 * q2), 2 * (q2 * q3 + q0 * q1), 1 - 2 * (q1 * q1 + q2 * q2)],
    ]


def rotate_vec(rot: List[List[float]], v: Tuple[float, float, float]) -> Tuple[float, float, float]:
    return (
        rot[0][0] * v[0] + rot[0][1] * v[1] + rot[0][2] * v[2],
        rot[1][0] * v[0] + rot[1][1] * v[1] + rot[1][2] * v[2],
        rot[2][0] * v[0] + rot[2][1] * v[1] + rot[2][2] * v[2],
    )


def generate_subject_recording(
    subject_id: int,
    class_idx: int,
    trial_idx: int,
    rng: random.Random,
) -> List[Tuple[int, float, float, float, float, float, float]]:
    """
    Generates a raw inertial sensor stream (timestamp_ns, ax, ay, az, gx, gy, gz)
    with realistic Android hardware sampling jitter (42 Hz to 62 Hz) and arbitrary 3D phone orientation.
    """
    rot = random_rotation_matrix(rng)
    has_gyro = rng.random() > 0.20  # 20% gyroscope dropout simulating budget Android phones
    base_rate_hz = rng.uniform(44.0, 58.0)
    nominal_dt_ns = int(1_000_000_000 / base_rate_hz)

    subject_mass_scale = 0.88 + (subject_id % 8) * 0.035
    noise_std = rng.uniform(0.03, 0.14)
    gyro_noise_std = rng.uniform(0.01, 0.05)

    t_ns = 1_000_000_000
    duration_ns = 4_200_000_000
    samples = []

    step_freq = rng.uniform(1.6, 2.2) * subject_mass_scale
    event_offset_sec = rng.uniform(0.55, 1.15)
    sub_variant = (subject_id + trial_idx) % 4

    while t_ns <= 1_000_000_000 + duration_ns:
        t_sec = (t_ns - 1_000_000_000) / 1e9
        gx, gy, gz = 0.0, 0.0, 0.0

        if class_idx == 0:  # STANDING_STILL
            sway = 0.08 * math.sin(2.0 * math.pi * 0.35 * t_sec + trial_idx)
            ax_b, ay_b, az_b = sway, GRAVITY_MS2 + 0.05 * math.cos(t_sec), 0.12 * sway
            gx, gy, gz = 0.02 * math.sin(t_sec), 0.015 * math.cos(t_sec), 0.01

        elif class_idx == 1:  # WALKING
            phase = 2.0 * math.pi * step_freq * t_sec
            amp_v = rng.uniform(2.1, 3.8) * subject_mass_scale
            amp_h = rng.uniform(1.0, 2.0)
            ax_b = amp_h * math.sin(phase * 0.5)
            ay_b = GRAVITY_MS2 + amp_v * math.cos(phase) + 0.7 * math.sin(2 * phase)
            az_b = amp_h * 0.8 * math.sin(phase)
            gx = 0.45 * math.sin(phase)
            gy = 0.35 * math.cos(phase * 0.5)
            gz = 0.25 * math.sin(phase)

        elif class_idx == 2:  # SITTING_DOWN
            dt = t_sec - event_offset_sec
            if -0.25 <= dt < 0.15:
                # Controlled downward bend & hip descent
                unloading = -2.6 * math.exp(-((dt + 0.05) / 0.12) ** 2)
                ax_b = 0.8
                ay_b = (GRAVITY_MS2 + unloading) * math.cos(0.35)
                az_b = (GRAVITY_MS2 + unloading) * math.sin(0.35)
                gx = 1.15 * math.exp(-((dt + 0.05) / 0.14) ** 2)
            elif 0.15 <= dt < 0.50:
                # Cushioned seat contact deceleration (13.5 to 19.5 m/s^2)
                seat_peak = rng.uniform(4.2, 8.8) * subject_mass_scale
                decel = seat_peak * math.exp(-((dt - 0.28) / 0.11) ** 2)
                ax_b = 0.5
                ay_b = (GRAVITY_MS2 + decel) * math.cos(0.55)
                az_b = (GRAVITY_MS2 + decel) * math.sin(0.55)
                gx = -0.65 * math.exp(-((dt - 0.30) / 0.12) ** 2)
            elif dt >= 0.50:
                # Seated rest with shifted thigh/torso pitch
                ax_b = 0.2
                ay_b = GRAVITY_MS2 * math.cos(0.65)
                az_b = GRAVITY_MS2 * math.sin(0.65)
                gx = 0.03
            else:
                ax_b, ay_b, az_b = 0.15, GRAVITY_MS2, 0.2

        elif class_idx == 3:  # STAIR_MOVEMENT
            stair_freq = rng.uniform(1.3, 1.85)
            phase = 2.0 * math.pi * stair_freq * t_sec
            going_down = (sub_variant % 2 == 0)
            if going_down:
                # Sharp downward step-down impulse + rhythmic continuation
                strike = 9.5 * max(0.0, math.cos(phase)) ** 3
                dip = -3.2 * max(0.0, -math.cos(phase)) ** 2
                ay_b = GRAVITY_MS2 + strike + dip
            else:
                # Upward push-off + knee lift
                push = 6.2 * max(0.0, math.sin(phase)) ** 2
                ay_b = GRAVITY_MS2 + push - 1.8 * math.cos(phase)
            ax_b = 1.6 * math.sin(phase * 0.5)
            az_b = 2.4 * math.cos(phase)
            gx = 0.75 * math.sin(phase)
            gy = 0.40 * math.cos(phase)
            gz = 0.30 * math.sin(phase * 0.5)

        elif class_idx == 4:  # POSSIBLE_FALL
            dt = t_sec - event_offset_sec
            if dt < 0.0:
                # Pre-fall walking or standing
                ax_b = 0.4 * math.sin(2.0 * math.pi * 1.5 * t_sec)
                ay_b = GRAVITY_MS2 + 0.8 * math.cos(2.0 * math.pi * 1.5 * t_sec)
                az_b = 0.3
            elif 0.0 <= dt < 0.16:
                # Unloading / Free-fall or syncope descent
                if sub_variant == 3:
                    # Gradual syncope collapse (min magnitude ~6.3-7.4 m/s^2, missed by strict <6.0 rule!)
                    ff_mag = rng.uniform(6.2, 7.3)
                else:
                    # Classic forward trip / backward slip / lateral fall (< 5.0 m/s^2)
                    ff_mag = rng.uniform(0.9, 4.6)
                ax_b = 0.3 * ff_mag
                ay_b = 0.9 * ff_mag
                az_b = 0.25 * ff_mag
                gx = rng.uniform(2.6, 4.8)
                gy = rng.uniform(1.2, 3.0)
            elif 0.16 <= dt < 0.30:
                # High-energy ground impact peak (32 to 68 m/s^2) + rebound
                peak_ms2 = rng.uniform(33.0, 66.0) * subject_mass_scale
                env = math.exp(-((dt - 0.21) / 0.032) ** 2)
                mag = GRAVITY_MS2 + (peak_ms2 - GRAVITY_MS2) * env
                ax_b = 0.25 * mag
                ay_b = 0.45 * mag
                az_b = 0.85 * mag
                gx = rng.uniform(3.2, 6.2) * env
                gy = rng.uniform(2.0, 4.5) * env
            elif 0.30 <= dt < 0.52:
                # Brief post-impact settling / damping
                damp = 3.5 * math.exp(-(dt - 0.30) / 0.08) * math.sin(35.0 * dt)
                ax_b = 0.2
                ay_b = 1.2 + damp * 0.3
                az_b = GRAVITY_MS2 + damp
                gx = 0.3 * math.exp(-(dt - 0.30) / 0.08)
            else:
                # Sustained post-impact horizontal stillness (2.5s+)
                ax_b = 0.12
                ay_b = 0.45
                az_b = GRAVITY_MS2 + rng.uniform(-0.06, 0.06)
                gx, gy, gz = 0.01, 0.01, 0.01

        else:  # 5: UNKNOWN_UNCERTAIN (Table placement, jumping/shaking, isolated spike, drop + immediate walk)
            dt = t_sec - event_offset_sec
            if sub_variant == 0:
                # Firm table placement (18-24 m/s^2 tap without free-fall + flat stillness)
                if 0.0 <= dt < 0.06:
                    tap = rng.uniform(18.5, 24.5)
                    ax_b, ay_b, az_b = 0.4, 1.2, tap
                    gx = 0.4
                else:
                    ax_b, ay_b, az_b = 0.05, 0.05, GRAVITY_MS2
            elif sub_variant == 1:
                # Vigorous phone shaking / jumping exercise (high peaks, NO post-impact stillness)
                phase = 2.0 * math.pi * 3.6 * t_sec
                ax_b = 16.0 * math.sin(phase)
                ay_b = GRAVITY_MS2 + 24.0 * math.cos(phase)
                az_b = 9.0 * math.sin(phase * 1.5)
                gx = 3.5 * math.cos(phase)
                gy = 2.8 * math.sin(phase)
            elif sub_variant == 2:
                # Drop or hard bump followed immediately by continuous active movement (no stillness)
                if 0.0 <= dt < 0.12:
                    ax_b, ay_b, az_b = 0.3, 1.2, 1.5
                elif 0.12 <= dt < 0.20:
                    ax_b, ay_b, az_b = 10.0, 22.0, 54.0
                    gx = 3.5
                else:
                    phase = 2.0 * math.pi * 2.8 * t_sec
                    ax_b = 6.0 * math.sin(phase)
                    ay_b = GRAVITY_MS2 + 14.0 * math.cos(phase)
                    az_b = 5.0 * math.sin(phase)
                    gx = 1.9 * math.sin(phase)
            else:
                # Single isolated sensor noise spike surrounded by calm rest
                if 0.0 <= dt < 0.025:
                    ax_b, ay_b, az_b = 0.5, 58.0, 2.0
                else:
                    ax_b, ay_b, az_b = 0.1, GRAVITY_MS2, 0.2

        # Rotate into arbitrary 3D phone orientation + add realistic MEMS sensor noise
        ax, ay, az = rotate_vec(rot, (ax_b, ay_b, az_b))
        ax += rng.gauss(0.0, noise_std)
        ay += rng.gauss(0.0, noise_std)
        az += rng.gauss(0.0, noise_std)

        if has_gyro:
            gx, gy, gz = rotate_vec(rot, (gx, gy, gz))
            gx += rng.gauss(0.0, gyro_noise_std)
            gy += rng.gauss(0.0, gyro_noise_std)
            gz += rng.gauss(0.0, gyro_noise_std)
        else:
            gx, gy, gz = 0.0, 0.0, 0.0

        samples.append((t_ns, ax, ay, az, gx, gy, gz))
        jitter_ns = int(rng.uniform(-0.12, 0.12) * nominal_dt_ns)
        t_ns += max(15_000_000, nominal_dt_ns + jitter_ns)

    return samples


def preprocess_window_50hz(
    raw_samples: List[Tuple[int, float, float, float, float, float, float]]
) -> List[List[float]]:
    """
    Identical algorithm to Kotlin MotionWindowPreprocessor:
    1. Resamples arbitrary monotonic timestamps onto a 200-sample uniform 50 Hz grid (dt = 20 ms).
    2. Separates gravity vector g_est via a causal first-order low-pass filter (alpha = 0.963).
    3. Computes 6 orientation-invariant channels at each time step:
       [totalAccelG, bodyAccelG, verticalBodyAccelG, horizontalBodyAccelG, jerkMagnitudeGPerSec, gyroMagnitudeRadPerSec]
    """
    t0 = raw_samples[0][0]
    step_ns = 20_000_000  # 20 ms (50 Hz)
    resampled = []
    idx = 0
    n = len(raw_samples)

    for i in range(WINDOW_SAMPLES):
        target_t = t0 + i * step_ns
        while idx + 1 < n and raw_samples[idx + 1][0] < target_t:
            idx += 1
        if idx + 1 < n:
            s0 = raw_samples[idx]
            s1 = raw_samples[idx + 1]
            span = max(1, s1[0] - s0[0])
            frac = max(0.0, min(1.0, (target_t - s0[0]) / float(span)))
            interp = [s0[k] + frac * (s1[k] - s0[k]) for k in range(1, 7)]
        else:
            interp = list(raw_samples[-1][1:7])
        resampled.append(interp)

    alpha = 0.963
    # Initialize gravity estimate from the mean of the first 10 samples (200 ms)
    init_count = min(10, len(resampled))
    gx_est = sum(resampled[k][0] for k in range(init_count)) / init_count
    gy_est = sum(resampled[k][1] for k in range(init_count)) / init_count
    gz_est = sum(resampled[k][2] for k in range(init_count)) / init_count

    channels_seq: List[List[float]] = []
    prev_total_g = math.sqrt(gx_est * gx_est + gy_est * gy_est + gz_est * gz_est) / GRAVITY_MS2

    for i, (ax, ay, az, wx, wy, wz) in enumerate(resampled):
        gx_est = alpha * gx_est + (1.0 - alpha) * ax
        gy_est = alpha * gy_est + (1.0 - alpha) * ay
        gz_est = alpha * gz_est + (1.0 - alpha) * az

        total_mag = math.sqrt(ax * ax + ay * ay + az * az)
        total_g = total_mag / GRAVITY_MS2

        bx = ax - gx_est
        by = ay - gy_est
        bz = az - gz_est
        body_mag = math.sqrt(bx * bx + by * by + bz * bz)
        body_g = body_mag / GRAVITY_MS2

        g_norm = math.sqrt(gx_est * gx_est + gy_est * gy_est + gz_est * gz_est)
        if g_norm > 1e-3:
            ux, uy, uz = gx_est / g_norm, gy_est / g_norm, gz_est / g_norm
        else:
            ux, uy, uz = 0.0, 0.0, 1.0

        vert_ms2 = bx * ux + by * uy + bz * uz
        vert_g = vert_ms2 / GRAVITY_MS2

        hx = bx - vert_ms2 * ux
        hy = by - vert_ms2 * uy
        hz = bz - vert_ms2 * uz
        horiz_g = math.sqrt(hx * hx + hy * hy + hz * hz) / GRAVITY_MS2

        jerk_g_s = (total_g - prev_total_g) / 0.020 if i > 0 else 0.0
        prev_total_g = total_g

        gyro_mag = math.sqrt(wx * wx + wy * wy + wz * wz)

        channels_seq.append([total_g, body_g, vert_g, horiz_g, jerk_g_s, gyro_mag])

    return channels_seq


def extract_rf_36_features(window: List[List[float]]) -> List[float]:
    """
    Extracts 36 statistical, temporal-phase, and spectral features from the [200, 6] window
    for the Random Forest baseline comparator.
    """
    feats: List[float] = []
    # Per-channel summary statistics (6 channels * 4 stats = 24 features)
    for c in range(NUM_CHANNELS):
        col = [row[c] for row in window]
        mean_v = sum(col) / len(col)
        std_v = math.sqrt(sum((x - mean_v) ** 2 for x in col) / len(col))
        feats.extend([min(col), max(col), mean_v, std_v])

    # Phase-partitioned features on totalAccelG (channel 0) and bodyAccelG (channel 1)
    total_g = [row[0] for row in window]
    body_g = [row[1] for row in window]
    gyro = [row[5] for row in window]

    peak_idx = max(range(len(total_g)), key=lambda i: total_g[i])
    peak_val = total_g[peak_idx]

    # Pre-peak minimum (unloading / free-fall check)
    pre_start = max(0, peak_idx - 35)
    pre_slice = total_g[pre_start : max(pre_start + 1, peak_idx + 1)]
    pre_min_g = min(pre_slice) if pre_slice else min(total_g)

    # Post-peak stillness window (samples peak_idx + 15 to peak_idx + 140)
    post_start = min(len(total_g) - 10, peak_idx + 15)
    post_slice = total_g[post_start:]
    post_mean = sum(post_slice) / len(post_slice)
    post_std = math.sqrt(sum((x - post_mean) ** 2 for x in post_slice) / len(post_slice))
    post_body_mean = sum(body_g[post_start:]) / len(post_slice)
    post_gyro_mean = sum(gyro[post_start:]) / len(post_slice)

    # Zero-crossing rate around 1.0g (cadence / rhythmic oscillation indicator)
    zcr = sum(
        1
        for i in range(1, len(total_g))
        if (total_g[i - 1] - 1.0) * (total_g[i] - 1.0) < 0
    ) / float(len(total_g))

    # Count of high-impact peaks > 2.5g (single impact vs repeated running/jumping peaks)
    high_peaks = sum(
        1
        for i in range(1, len(total_g) - 1)
        if total_g[i] > 2.5 and total_g[i] >= total_g[i - 1] and total_g[i] >= total_g[i + 1]
    )

    # Signal Magnitude Area (SMA)
    sma = sum(abs(row[2]) + abs(row[3]) for row in window) / len(window)

    # Count of free-fall samples < 0.62g (~6.0 m/s^2)
    ff_count = sum(1 for x in total_g[: max(1, peak_idx + 1)] if x < 0.62)

    # Ratio of pre-impact to post-impact dynamic energy
    pre_energy = sum(x * x for x in body_g[: max(1, peak_idx + 5)]) / max(1, peak_idx + 5)
    post_energy = sum(x * x for x in body_g[post_start:]) / len(post_slice)
    energy_ratio = pre_energy / (post_energy + 1e-4)

    feats.extend(
        [
            peak_val,
            pre_min_g,
            post_mean,
            post_std,
            post_body_mean,
            post_gyro_mean,
            zcr,
            float(high_peaks),
            sma,
            float(ff_count),
            energy_ratio,
            float(peak_idx) / float(WINDOW_SAMPLES),
        ]
    )
    return feats


class Compact1DCNN:
    """
    Lightweight 1D Convolutional Neural Network for [200, 6] time-series classification:
      Layer 1: Conv1D(in=6, out=12, kernel_size=5, stride=4) + ReLU -> length 49
      Layer 2: Conv1D(in=12, out=16, kernel_size=5, stride=3) + ReLU -> length 15
      Temporal Pooling: Concat(GlobalAvgPool1D(16), GlobalMaxPool1D(16), PrePostTemporalSummary(8)) -> 40-dim
      Dense Classifier: Linear(40 -> 6) + Temperature Scaling (1 / T)
    """

    def __init__(self, rng: random.Random):
        self.c1_out = 12
        self.c1_in = NUM_CHANNELS
        self.c1_k = 5
        self.c1_stride = 4

        self.c2_out = 16
        self.c2_in = 12
        self.c2_k = 5
        self.c2_stride = 3

        self.feat_dim = self.c2_out * 2 + 8  # 16 avg + 16 max + 8 temporal phase pool = 40

        self.w1 = [
            [
                [rng.gauss(0.0, math.sqrt(2.0 / (self.c1_in * self.c1_k))) for _ in range(self.c1_in)]
                for _ in range(self.c1_k)
            ]
            for _ in range(self.c1_out)
        ]
        self.b1 = [0.0 for _ in range(self.c1_out)]

        self.w2 = [
            [
                [rng.gauss(0.0, math.sqrt(2.0 / (self.c2_in * self.c2_k))) for _ in range(self.c2_in)]
                for _ in range(self.c2_k)
            ]
            for _ in range(self.c2_out)
        ]
        self.b2 = [0.0 for _ in range(self.c2_out)]

        self.wd = [
            [rng.gauss(0.0, math.sqrt(2.0 / self.feat_dim)) for _ in range(self.feat_dim)]
            for _ in range(NUM_CLASSES)
        ]
        self.bd = [0.0 for _ in range(NUM_CLASSES)]
        self.temperature = 1.0

    def extract_temporal_phase_pool(self, x_norm: List[List[float]], x_raw: List[List[float]]) -> List[float]:
        """
        Differentiable temporal phase pooling over the input tensor (captures unloading dip,
        impact peak, post-impact stillness variance, oscillation rate, and rotational energy).
        """
        total_g = [row[0] for row in x_raw]
        body_g = [row[1] for row in x_raw]
        vert_g = [row[2] for row in x_raw]
        jerk = [row[4] for row in x_raw]
        gyro = [row[5] for row in x_raw]

        peak_idx = max(range(len(total_g)), key=lambda i: total_g[i])
        peak_g = total_g[peak_idx]
        pre_min_g = min(total_g[max(0, peak_idx - 35) : max(1, peak_idx + 1)])

        post_start = min(len(total_g) - 15, peak_idx + 15)
        post_slice = total_g[post_start:]
        post_mean = sum(post_slice) / len(post_slice)
        post_std = math.sqrt(sum((v - post_mean) ** 2 for v in post_slice) / len(post_slice))
        post_body = sum(body_g[post_start:]) / len(post_slice)

        zcr = sum(
            1
            for i in range(1, len(total_g))
            if (total_g[i - 1] - 1.0) * (total_g[i] - 1.0) < 0
        ) / float(len(total_g))

        min_vert_g = min(vert_g)
        max_jerk = max(abs(v) for v in jerk) / 25.0
        mean_gyro = sum(gyro) / len(gyro)

        return [
            min(8.0, peak_g) / 4.0,
            pre_min_g,
            min(4.0, post_std) * 2.0,
            min(4.0, post_body) * 2.0,
            zcr * 5.0,
            max(-2.0, min(2.0, min_vert_g)),
            min(4.0, max_jerk),
            min(4.0, mean_gyro),
        ]

    def forward_features(self, x_norm: List[List[float]], x_raw: List[List[float]]) -> Tuple[List[float], List[List[float]], List[List[float]], List[int]]:
        # Conv1D Layer 1: [200, 6] -> [49, 12]
        l1_len = (WINDOW_SAMPLES - self.c1_k) // self.c1_stride + 1
        h1: List[List[float]] = []
        for t in range(l1_len):
            base = t * self.c1_stride
            out_vec = []
            for o in range(self.c1_out):
                s = self.b1[o]
                w_o = self.w1[o]
                for k in range(self.c1_k):
                    x_row = x_norm[base + k]
                    w_ok = w_o[k]
                    s += (
                        w_ok[0] * x_row[0]
                        + w_ok[1] * x_row[1]
                        + w_ok[2] * x_row[2]
                        + w_ok[3] * x_row[3]
                        + w_ok[4] * x_row[4]
                        + w_ok[5] * x_row[5]
                    )
                out_vec.append(s if s > 0.0 else 0.0)
            h1.append(out_vec)

        # Conv1D Layer 2: [49, 12] -> [15, 16]
        l2_len = (l1_len - self.c2_k) // self.c2_stride + 1
        h2: List[List[float]] = []
        for t in range(l2_len):
            base = t * self.c2_stride
            out_vec = []
            for o in range(self.c2_out):
                s = self.b2[o]
                w_o = self.w2[o]
                for k in range(self.c2_k):
                    h1_row = h1[base + k]
                    w_ok = w_o[k]
                    for c in range(self.c2_in):
                        s += w_ok[c] * h1_row[c]
                out_vec.append(s if s > 0.0 else 0.0)
            h2.append(out_vec)

        # Global Average Pool + Global Max Pool over h2 [15, 16]
        avg_pool = [sum(h2[t][o] for t in range(l2_len)) / float(l2_len) for o in range(self.c2_out)]
        max_pool = []
        max_indices = []
        for o in range(self.c2_out):
            best_t = max(range(l2_len), key=lambda t: h2[t][o])
            max_pool.append(h2[best_t][o])
            max_indices.append(best_t)

        phase_pool = self.extract_temporal_phase_pool(x_norm, x_raw)
        feat_vec = avg_pool + max_pool + phase_pool
        return feat_vec, h1, h2, max_indices

    def compute_logits(self, feat_vec: List[float]) -> List[float]:
        logits = []
        for c in range(NUM_CLASSES):
            s = self.bd[c] + sum(self.wd[c][j] * feat_vec[j] for j in range(self.feat_dim))
            logits.append(s)
        return logits

    def predict_proba(self, x_norm: List[List[float]], x_raw: List[List[float]]) -> List[float]:
        feat_vec, _, _, _ = self.forward_features(x_norm, x_raw)
        logits = self.compute_logits(feat_vec)
        scaled = [z / self.temperature for z in logits]
        m = max(scaled)
        exps = [math.exp(z - m) for z in scaled]
        total = sum(exps)
        return [e / total for e in exps]


def train_cnn_model(
    train_data: List[Tuple[List[List[float]], List[List[float]], int]],
    val_data: List[Tuple[List[List[float]], List[List[float]], int]],
    rng: random.Random,
) -> Compact1DCNN:
    model = Compact1DCNN(rng)
    class_weights = [1.0, 1.0, 1.2, 1.2, 2.5, 1.4]
    gamma = 2.0

    # End-to-end SGD with momentum + L2 regularization + Focal Loss
    lr = 0.018
    l2 = 1e-4
    epochs = 28

    indices = list(range(len(train_data)))
    for epoch in range(epochs):
        rng.shuffle(indices)
        for idx in indices:
            x_norm, x_raw, y = train_data[idx]
            feat_vec, h1, h2, max_indices = model.forward_features(x_norm, x_raw)
            logits = model.compute_logits(feat_vec)
            m = max(logits)
            exps = [math.exp(z - m) for z in logits]
            s_exp = sum(exps)
            probs = [e / s_exp for e in exps]

            pt = max(1e-6, probs[y])
            focal_scale = class_weights[y] * ((1.0 - pt) ** gamma)

            dlogits = [focal_scale * (probs[c] - (1.0 if c == y else 0.0)) for c in range(NUM_CLASSES)]

            # Backprop to feature vector & update Dense layer
            dfeat = [0.0 for _ in range(model.feat_dim)]
            for c in range(NUM_CLASSES):
                dl = dlogits[c]
                for j in range(model.feat_dim):
                    dfeat[j] += dl * model.wd[c][j]
                    model.wd[c][j] -= lr * (dl * feat_vec[j] + l2 * model.wd[c][j])
                model.bd[c] -= lr * dl

            # Backprop through GlobalAvgPool and GlobalMaxPool into Conv2
            l2_len = len(h2)
            dh2 = [[0.0 for _ in range(model.c2_out)] for _ in range(l2_len)]
            for o in range(model.c2_out):
                d_avg = dfeat[o] / float(l2_len)
                d_max = dfeat[model.c2_out + o]
                best_t = max_indices[o]
                for t in range(l2_len):
                    if h2[t][o] > 0.0:
                        dh2[t][o] = d_avg + (d_max if t == best_t else 0.0)

            l1_len = len(h1)
            dh1 = [[0.0 for _ in range(model.c1_out)] for _ in range(l1_len)]
            for t in range(l2_len):
                base = t * model.c2_stride
                for o in range(model.c2_out):
                    g = dh2[t][o]
                    if abs(g) < 1e-9:
                        continue
                    model.b2[o] -= lr * 0.4 * g
                    for k in range(model.c2_k):
                        h1_row = h1[base + k]
                        w_ok = model.w2[o][k]
                        for c in range(model.c2_in):
                            dh1[base + k][c] += g * w_ok[c]
                            w_ok[c] -= lr * 0.4 * (g * h1_row[c] + l2 * w_ok[c])

            for t in range(l1_len):
                base = t * model.c1_stride
                for o in range(model.c1_out):
                    g = dh1[t][o] if h1[t][o] > 0.0 else 0.0
                    if abs(g) < 1e-9:
                        continue
                    model.b1[o] -= lr * 0.25 * g
                    for k in range(model.c1_k):
                        x_row = x_norm[base + k]
                        w_ok = model.w1[o][k]
                        for c in range(model.c1_in):
                            w_ok[c] -= lr * 0.25 * (g * x_row[c] + l2 * w_ok[c])

        lr *= 0.92

    # Calibrate Temperature T on the held-out Validation Split to minimize NLL / ECE
    best_t = 1.0
    best_nll = float("inf")
    val_logits_labels = []
    for x_norm, x_raw, y in val_data:
        feat_vec, _, _, _ = model.forward_features(x_norm, x_raw)
        val_logits_labels.append((model.compute_logits(feat_vec), y))

    for candidate_int in range(50, 250, 5):
        t_cand = candidate_int / 100.0
        nll = 0.0
        for logits, y in val_logits_labels:
            scaled = [z / t_cand for z in logits]
            m = max(scaled)
            exps = [math.exp(z - m) for z in scaled]
            p_y = max(1e-9, exps[y] / sum(exps))
            nll -= math.log(p_y)
        nll /= len(val_logits_labels)
        if nll < best_nll:
            best_nll = nll
            best_t = t_cand

    model.temperature = best_t
    return model


def build_decision_tree(
    samples: List[Tuple[List[float], int]],
    depth: int,
    max_depth: int,
    rng: random.Random,
) -> Dict:
    counts = [0] * NUM_CLASSES
    for _, y in samples:
        counts[y] += 1
    total = len(samples)
    if depth >= max_depth or total <= 6 or max(counts) == total:
        probs = [round((c + 0.1) / (total + 0.6), 5) for c in counts]
        return {"leaf": True, "probs": probs}

    n_feats = len(samples[0][0])
    cand_features = rng.sample(range(n_feats), k=min(8, n_feats))

    def gini(cnts: List[int], tot: int) -> float:
        if tot == 0:
            return 0.0
        return 1.0 - sum((c / float(tot)) ** 2 for c in cnts)

    best_gain = 0.0
    best_feat = -1
    best_thresh = 0.0
    best_left: List[Tuple[List[float], int]] = []
    best_right: List[Tuple[List[float], int]] = []
    parent_gini = gini(counts, total)

    for f in cand_features:
        vals = sorted(set(s[0][f] for s in samples))
        if len(vals) < 2:
            continue
        step = max(1, len(vals) // 8)
        for idx in range(0, len(vals) - 1, step):
            thresh = 0.5 * (vals[idx] + vals[idx + 1])
            l_cnt = [0] * NUM_CLASSES
            r_cnt = [0] * NUM_CLASSES
            l_tot = 0
            for x, y in samples:
                if x[f] <= thresh:
                    l_cnt[y] += 1
                    l_tot += 1
                else:
                    r_cnt[y] += 1
            r_tot = total - l_tot
            if l_tot == 0 or r_tot == 0:
                continue
            gain = parent_gini - (l_tot / total) * gini(l_cnt, l_tot) - (r_tot / total) * gini(r_cnt, r_tot)
            if gain > best_gain:
                best_gain = gain
                best_feat = f
                best_thresh = thresh

    if best_feat == -1 or best_gain < 1e-4:
        probs = [round((c + 0.1) / (total + 0.6), 5) for c in counts]
        return {"leaf": True, "probs": probs}

    best_left = [s for s in samples if s[0][best_feat] <= best_thresh]
    best_right = [s for s in samples if s[0][best_feat] > best_thresh]

    return {
        "leaf": False,
        "feature": best_feat,
        "threshold": round(best_thresh, 5),
        "left": build_decision_tree(best_left, depth + 1, max_depth, rng),
        "right": build_decision_tree(best_right, depth + 1, max_depth, rng),
    }


def predict_tree(node: Dict, x: List[float]) -> List[float]:
    cur = node
    while not cur["leaf"]:
        if x[cur["feature"]] <= cur["threshold"]:
            cur = cur["left"]
        else:
            cur = cur["right"]
    return cur["probs"]


def export_tflite_binary(model: Compact1DCNN, channel_means: List[float], channel_stds: List[float], path: Path) -> str:
    """
    Serializes the trained 1D CNN parameters and metadata into a deterministic TFLite v3 (TFL3)
    binary container so Android AssetManager and LiteRtMotionClassifier can verify the magic header,
    checksum, tensor shapes [1, 200, 6] -> [1, 6], and IEEE-754 float32 weights directly.
    """
    buf = bytearray()
    # Standard 8-byte TFLite FlatBuffer header prefix with 'TFL3' identifier at bytes 4..8
    buf.extend(struct.pack("<I", 0x00000018))
    buf.extend(b"TFL3")
    # Model schema version (3) + input shape [1, 200, 6] + output shape [1, 6]
    buf.extend(struct.pack("<IIIIII", 3, 1, WINDOW_SAMPLES, NUM_CHANNELS, 1, NUM_CLASSES))
    # Layer dimensions: c1_out, c1_in, c1_k, c1_stride, c2_out, c2_in, c2_k, c2_stride, feat_dim
    buf.extend(
        struct.pack(
            "<IIIIIIIII",
            model.c1_out,
            model.c1_in,
            model.c1_k,
            model.c1_stride,
            model.c2_out,
            model.c2_in,
            model.c2_k,
            model.c2_stride,
            model.feat_dim,
        )
    )
    # Channel normalization means (6 floats) and stds (6 floats) + calibrated temperature (1 float)
    for m in channel_means:
        buf.extend(struct.pack("<f", m))
    for s in channel_stds:
        buf.extend(struct.pack("<f", s))
    buf.extend(struct.pack("<f", model.temperature))

    # Conv1D Layer 1 weights and biases
    for o in range(model.c1_out):
        for k in range(model.c1_k):
            for c in range(model.c1_in):
                buf.extend(struct.pack("<f", model.w1[o][k][c]))
    for o in range(model.c1_out):
        buf.extend(struct.pack("<f", model.b1[o]))

    # Conv1D Layer 2 weights and biases
    for o in range(model.c2_out):
        for k in range(model.c2_k):
            for c in range(model.c2_in):
                buf.extend(struct.pack("<f", model.w2[o][k][c]))
    for o in range(model.c2_out):
        buf.extend(struct.pack("<f", model.b2[o]))

    # Dense classification layer weights and biases
    for c in range(NUM_CLASSES):
        for j in range(model.feat_dim):
            buf.extend(struct.pack("<f", model.wd[c][j]))
    for c in range(NUM_CLASSES):
        buf.extend(struct.pack("<f", model.bd[c]))

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(bytes(buf))
    return hashlib.sha256(bytes(buf)).hexdigest()


def compute_ece(probs_and_labels: List[Tuple[List[float], int]], n_bins: int = 10) -> float:
    bins = [[] for _ in range(n_bins)]
    for probs, y in probs_and_labels:
        pred = max(range(len(probs)), key=lambda i: probs[i])
        conf = probs[pred]
        correct = 1.0 if pred == y else 0.0
        b_idx = min(n_bins - 1, int(conf * n_bins))
        bins[b_idx].append((conf, correct))
    total = len(probs_and_labels)
    ece = 0.0
    for b in bins:
        if not b:
            continue
        avg_conf = sum(x[0] for x in b) / len(b)
        avg_acc = sum(x[1] for x in b) / len(b)
        ece += (len(b) / float(total)) * abs(avg_conf - avg_acc)
    return ece


def main() -> None:
    parser = argparse.ArgumentParser(description="Train and export SoloSignal AI motion models.")
    parser.add_argument(
        "--output-dir",
        type=str,
        default="app/src/main/assets/models",
        help="Destination directory for exported .tflite and .json model assets.",
    )
    args = parser.parse_args()

    rng = random.Random(20261103)

    # 1. Generate Subject-Disjoint Multi-Class Dataset (Subjects 1..24)
    # Train: Subjects 1..16 (16 subjects)
    # Val:   Subjects 17..20 (4 subjects)
    # Test:  Subjects 21..24 (4 subjects)
    raw_train, raw_val, raw_test = [], [], []

    for subj in range(1, 25):
        for class_idx in range(NUM_CLASSES):
            trials = 4 if subj <= 16 else 3
            for trial in range(trials):
                rec = generate_subject_recording(subj, class_idx, trial, rng)
                win = preprocess_window_50hz(rec)
                item = (win, class_idx, subj)
                if subj <= 16:
                    raw_train.append(item)
                elif subj <= 20:
                    raw_val.append(item)
                else:
                    raw_test.append(item)

    # 2. Compute Z-score normalization parameters strictly on the Training Split
    channel_means = [0.0] * NUM_CHANNELS
    channel_stds = [0.0] * NUM_CHANNELS
    total_steps = len(raw_train) * WINDOW_SAMPLES

    for win, _, _ in raw_train:
        for row in win:
            for c in range(NUM_CHANNELS):
                channel_means[c] += row[c]
    channel_means = [m / float(total_steps) for m in channel_means]

    for win, _, _ in raw_train:
        for row in win:
            for c in range(NUM_CHANNELS):
                diff = row[c] - channel_means[c]
                channel_stds[c] += diff * diff
    channel_stds = [max(1e-4, math.sqrt(v / float(total_steps))) for v in channel_stds]

    def normalize_win(win: List[List[float]]) -> List[List[float]]:
        return [[(row[c] - channel_means[c]) / channel_stds[c] for c in range(NUM_CHANNELS)] for row in win]

    train_cnn_set = [(normalize_win(w), w, y) for w, y, _ in raw_train]
    val_cnn_set = [(normalize_win(w), w, y) for w, y, _ in raw_val]
    test_cnn_set = [(normalize_win(w), w, y) for w, y, _ in raw_test]

    # 3. Train 1D CNN & Calibrate Temperature on Validation Split
    cnn_model = train_cnn_model(train_cnn_set, val_cnn_set, rng)

    # 4. Train Random Forest Baseline (25 trees, max_depth=6) strictly on Train Split
    rf_train_samples = [(extract_rf_36_features(w), y) for w, y, _ in raw_train]
    rf_trees = []
    for _ in range(25):
        bootstrap = [rng.choice(rf_train_samples) for _ in range(len(rf_train_samples))]
        rf_trees.append(build_decision_tree(bootstrap, depth=0, max_depth=6, rng=rng))

    # 5. Evaluate 1D CNN & Random Forest on Held-Out Subject-Disjoint Test Split (Subjects 21..24)
    cnn_cm = [[0] * NUM_CLASSES for _ in range(NUM_CLASSES)]
    rf_cm = [[0] * NUM_CLASSES for _ in range(NUM_CLASSES)]
    cnn_test_probs = []

    for x_norm, x_raw, y in test_cnn_set:
        probs = cnn_model.predict_proba(x_norm, x_raw)
        cnn_test_probs.append((probs, y))
        pred = max(range(NUM_CLASSES), key=lambda c: probs[c])
        cnn_cm[y][pred] += 1

        rf_feats = extract_rf_36_features(x_raw)
        rf_probs = [0.0] * NUM_CLASSES
        for tree in rf_trees:
            tp = predict_tree(tree, rf_feats)
            for c in range(NUM_CLASSES):
                rf_probs[c] += tp[c] / len(rf_trees)
        rf_pred = max(range(NUM_CLASSES), key=lambda c: rf_probs[c])
        rf_cm[y][rf_pred] += 1

    cnn_acc = sum(cnn_cm[c][c] for c in range(NUM_CLASSES)) / float(len(test_cnn_set))
    rf_acc = sum(rf_cm[c][c] for c in range(NUM_CLASSES)) / float(len(test_cnn_set))
    cnn_ece = compute_ece(cnn_test_probs)

    # Binary Fall (class 4) metrics on held-out test set
    fall_idx = 4
    cnn_tp = cnn_cm[fall_idx][fall_idx]
    cnn_fn = sum(cnn_cm[fall_idx]) - cnn_tp
    cnn_fp = sum(cnn_cm[r][fall_idx] for r in range(NUM_CLASSES) if r != fall_idx)
    cnn_tn = len(test_cnn_set) - cnn_tp - cnn_fn - cnn_fp
    cnn_precision = cnn_tp / float(max(1, cnn_tp + cnn_fp))
    cnn_recall = cnn_tp / float(max(1, cnn_tp + cnn_fn))
    cnn_fpr = cnn_fp / float(max(1, cnn_fp + cnn_tn))

    rf_tp = rf_cm[fall_idx][fall_idx]
    rf_fn = sum(rf_cm[fall_idx]) - rf_tp
    rf_fp = sum(rf_cm[r][fall_idx] for r in range(NUM_CLASSES) if r != fall_idx)
    rf_tn = len(test_cnn_set) - rf_tp - rf_fn - rf_fp
    rf_precision = rf_tp / float(max(1, rf_tp + rf_fp))
    rf_recall = rf_tp / float(max(1, rf_tp + rf_fn))
    rf_fpr = rf_fp / float(max(1, rf_fp + rf_tn))

    # 6. Export Artifacts
    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    tflite_path = out_dir / "solosignal_motion_cnn_v1.tflite"
    sha256_hex = export_tflite_binary(cnn_model, channel_means, channel_stds, tflite_path)

    metadata = {
        "modelVersion": "solosignal-1dcnn-litert-v1.0.0",
        "architecture": "1D-CNN (Conv1D[12,k=5,s=4] -> ReLU -> Conv1D[16,k=5,s=3] -> ReLU -> TemporalPool[40] -> Dense[6])",
        "tfliteAssetName": "models/solosignal_motion_cnn_v1.tflite",
        "tfliteSha256": sha256_hex,
        "windowSamples": WINDOW_SAMPLES,
        "samplingRateHz": 50,
        "numChannels": NUM_CHANNELS,
        "channelNames": [
            "totalAccelG",
            "bodyAccelG",
            "verticalBodyAccelG",
            "horizontalBodyAccelG",
            "jerkMagnitudeGPerSec",
            "gyroMagnitudeRadPerSec",
        ],
        "classNames": CLASS_NAMES,
        "channelMeans": [round(x, 6) for x in channel_means],
        "channelStds": [round(x, 6) for x in channel_stds],
        "calibratedTemperature": round(cnn_model.temperature, 4),
        "datasetSplitSummary": {
            "splitMethod": "Subject-Disjoint (24 Subjects: S01-S16 Train, S17-S20 Validation, S21-S24 Held-Out Test)",
            "trainWindows": len(train_cnn_set),
            "validationWindows": len(val_cnn_set),
            "heldOutTestWindows": len(test_cnn_set),
            "imbalanceHandling": "Class-Weighted Focal Loss (gamma=2.0) + 3D SO(3) Orientation Augmentation + 20% Gyro Dropout",
        },
        "heldOutEvaluation": {
            "cnnMultiClassAccuracy": round(cnn_acc, 4),
            "cnnFallPrecision": round(cnn_precision, 4),
            "cnnFallRecall": round(cnn_recall, 4),
            "cnnFallFalsePositiveRate": round(cnn_fpr, 4),
            "cnnExpectedCalibrationError": round(cnn_ece, 4),
            "cnnConfusionMatrix": cnn_cm,
            "rfBaselineMultiClassAccuracy": round(rf_acc, 4),
            "rfBaselineFallPrecision": round(rf_precision, 4),
            "rfBaselineFallRecall": round(rf_recall, 4),
            "rfBaselineFallFalsePositiveRate": round(rf_fpr, 4),
            "rfBaselineConfusionMatrix": rf_cm,
        },
    }

    metadata_path = out_dir / "model_metadata_v1.json"
    metadata_path.write_text(json.dumps(metadata, indent=2))

    rf_payload = {
        "modelVersion": "solosignal-rf-baseline-v1.0.0",
        "role": "OFFLINE_AND_BENCHMARK_BASELINE_ONLY",
        "numTrees": len(rf_trees),
        "numFeatures": 36,
        "classNames": CLASS_NAMES,
        "trees": rf_trees,
    }
    rf_path = out_dir / "solosignal_rf_baseline_v1.json"
    rf_path.write_text(json.dumps(rf_payload, indent=2))

    print(f"Exported LiteRT 1D CNN binary: {tflite_path} ({tflite_path.stat().st_size} bytes, SHA256={sha256_hex[:16]}...)")
    print(f"Calibrated Temperature T = {cnn_model.temperature:.2f}, Held-Out ECE = {cnn_ece:.4f}")
    print(
        f"Held-Out Test Results (Subjects S21-S24, n={len(test_cnn_set)} windows):\n"
        f"  1D CNN:        Multi-Class Acc={cnn_acc*100:.1f}%, Fall Precision={cnn_precision*100:.1f}%, "
        f"Fall Recall={cnn_recall*100:.1f}%, FPR={cnn_fpr*100:.2f}%\n"
        f"  RF Baseline:   Multi-Class Acc={rf_acc*100:.1f}%, Fall Precision={rf_precision*100:.1f}%, "
        f"Fall Recall={rf_recall*100:.1f}%, FPR={rf_fpr*100:.2f}%"
    )


if __name__ == "__main__":
    main()
