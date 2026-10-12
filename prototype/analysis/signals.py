"""레퍼런스 카메라 움직임 분석용 순수 신호 처리 함수들.

좌표는 모두 '프레임 높이 = 1' 정규화 단위. 시간축은 프레임 인덱스, fps로 초 환산.
"""
import numpy as np
from scipy.optimize import least_squares
from scipy.signal import butter, filtfilt, welch

from vcam.follow import simulate_follow


def split_shots(cut_score, threshold, min_len):
    """cut_score[i] > threshold 이면 프레임 i에서 새 샷 시작. min_len 미만 샷은 버린다."""
    n = len(cut_score)
    cuts = [i for i in range(1, n) if cut_score[i] > threshold]
    bounds = [0, *cuts, n]
    return [(a, b) for a, b in zip(bounds[:-1], bounds[1:]) if b - a >= min_len]


def accumulate_similarity(steps, center):
    """프레임 간 2x3 변환(이전 프레임 좌표 → 현재 프레임 좌표)을 누적해
    프레임 중심이 바라보는 월드(첫 프레임) 좌표와 누적 scale/회전을 반환. shape (n+1, 4)."""
    M = np.eye(3)
    c = np.array([center[0], center[1], 1.0])
    out = []
    for i in range(len(steps) + 1):
        if i > 0:
            M = np.vstack([steps[i - 1], [0, 0, 1]]) @ M
        w = np.linalg.solve(M, c)
        out.append([w[0], w[1], np.hypot(M[0, 0], M[1, 0]), np.degrees(np.arctan2(M[1, 0], M[0, 0]))])
    return np.array(out)


def estimate_delay(target, follower, fps, max_lag_s):
    """속도 교차상관으로 follower가 target보다 늦은 시간(초)을 추정. 양수 = 늦음."""
    a = np.diff(target)
    b = np.diff(follower)
    a = (a - a.mean()) / (a.std() + 1e-12)
    b = (b - b.mean()) / (b.std() + 1e-12)
    max_lag = int(round(max_lag_s * fps))
    best_lag, best = 0, -np.inf
    for lag in range(-max_lag, max_lag + 1):
        if lag >= 0:
            c = np.dot(a[: len(a) - lag], b[lag:]) / (len(a) - lag)
        else:
            c = np.dot(a[-lag:], b[: len(b) + lag]) / (len(a) + lag)
        if c > best:
            best_lag, best = lag, c
    return best_lag / fps


def fit_follow(target, follower, fps):
    """follower ≈ simulate_follow(target, ...) + offset 이 되도록
    omega, zeta, delay_s, deadzone, offset을 최소제곱으로 추정."""
    target = np.asarray(target, float)
    follower = np.asarray(follower, float)

    def resid(p):
        return simulate_follow(target, fps, p[0], p[1], p[2], deadzone=p[3], substeps=2) + p[4] - follower

    off0 = float(np.median(follower - target))
    best = None
    for w0, z0, d0, dz0 in [(2.0, 0.7, 0.1, 0.0), (5.0, 0.9, 0.3, 0.05), (8.0, 0.5, 0.2, 0.1)]:
        r = least_squares(
            resid,
            (w0, z0, d0, dz0, off0),
            bounds=([0.3, 0.1, 0.0, 0.0, -1.0], [20.0, 2.0, 1.0, 0.5, 1.0]),
            diff_step=1e-3,
        )
        if best is None or r.cost < best.cost:
            best = r
    w, z, d, dz, off = (float(v) for v in best.x)
    rmse = float(np.sqrt(np.mean(best.fun ** 2)))
    return {"omega": w, "zeta": z, "delay_s": d, "deadzone": dz, "offset": off, "rmse": rmse}


def split_shake(x, fps, cutoff_hz):
    """zero-phase 저역통과로 의도된 움직임(low)과 흔들림(high = x - low) 분리."""
    b, a = butter(2, cutoff_hz, fs=fps)
    low = filtfilt(b, a, x, padlen=min(len(x) - 1, 3 * max(len(a), len(b))))
    return low, x - low


def shake_stats(high, fps):
    high = np.asarray(high, float)
    f, p = welch(high, fs=fps, nperseg=min(256, len(high)))
    return {
        "rms": float(np.sqrt(np.mean(high ** 2))),
        "p95": float(np.percentile(np.abs(high), 95)),
        "dominant_hz": float(f[1:][np.argmax(p[1:])]),
    }
