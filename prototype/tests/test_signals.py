import numpy as np
import pytest

from analysis.signals import (
    accumulate_similarity,
    estimate_delay,
    fit_follow,
    shake_stats,
    simulate_follow,
    split_shake,
    split_shots,
)

FPS = 30.0


def smooth_steps(n=600):
    """등반자처럼 '움직임-정지'가 반복되는 1D 궤적."""
    t = np.arange(n)
    x = np.zeros(n)
    for start, amp in [(60, 0.2), (200, 0.15), (350, -0.1), (480, 0.25)]:
        x += amp / (1 + np.exp(-(t - start) / 6.0))
    return x


# --- split_shots ---

def test_split_shots_cuts_at_high_scores():
    score = np.zeros(100)
    score[[30, 70]] = 1.0
    assert split_shots(score, threshold=0.5, min_len=5) == [(0, 30), (30, 70), (70, 100)]


def test_split_shots_drops_short_segments():
    score = np.zeros(100)
    score[[30, 33]] = 1.0
    assert split_shots(score, threshold=0.5, min_len=5) == [(0, 30), (33, 100)]


# --- accumulate_similarity ---

def test_accumulate_pure_translation_sums():
    # 프레임 간 변환이 매번 +0.01 x 이동이면, 고정된 월드 점은 프레임에서 오른쪽으로 흐른다
    # → 카메라 중심의 월드 좌표는 왼쪽(-)으로 이동
    steps = [np.array([[1, 0, 0.01], [0, 1, 0.0]])] * 10
    cam = accumulate_similarity(steps, center=(0.0, 0.0))
    assert cam.shape == (11, 4)  # x, y, scale, rot_deg
    np.testing.assert_allclose(cam[-1, 0], -0.10, atol=1e-9)
    np.testing.assert_allclose(cam[:, 2], 1.0)


def test_accumulate_scale_reports_zoom_in():
    steps = [np.array([[1.01, 0, 0], [0, 1.01, 0]])] * 10
    cam = accumulate_similarity(steps, center=(0.0, 0.0))
    assert cam[-1, 2] == pytest.approx(1.01 ** 10)


# --- estimate_delay ---

def test_estimate_delay_recovers_shift():
    x = smooth_steps()
    lag = 6  # 0.2s
    y = np.concatenate([np.full(lag, x[0]), x[:-lag]])
    assert estimate_delay(x, y, FPS, max_lag_s=1.0) == pytest.approx(lag / FPS, abs=1 / FPS)


# --- simulate_follow / fit_follow ---

def test_simulate_follow_converges_to_step():
    target = np.r_[np.zeros(30), np.ones(300)]
    y = simulate_follow(target, FPS, omega=6.0, zeta=0.9, delay_s=0.0)
    assert y[0] == 0.0
    assert y[-1] == pytest.approx(1.0, abs=1e-3)


def test_simulate_follow_underdamped_overshoots():
    target = np.r_[np.zeros(30), np.ones(300)]
    y = simulate_follow(target, FPS, omega=6.0, zeta=0.5, delay_s=0.0)
    assert y.max() > 1.05


def test_simulate_follow_delay_postpones_response():
    target = np.r_[np.zeros(30), np.ones(300)]
    y = simulate_follow(target, FPS, omega=20.0, zeta=1.0, delay_s=0.3)
    assert abs(y[30 + 8]) < 1e-9  # 0.27s 시점엔 아직 반응 전
    assert y[30 + 20] > 0.5


def test_fit_follow_recovers_parameters():
    target = smooth_steps()
    cam = simulate_follow(target, FPS, omega=4.0, zeta=0.7, delay_s=0.2)
    p = fit_follow(target, cam, FPS)
    assert p["omega"] == pytest.approx(4.0, rel=0.1)
    assert p["zeta"] == pytest.approx(0.7, abs=0.08)
    assert p["delay_s"] == pytest.approx(0.2, abs=0.05)
    assert p["rmse"] < 1e-3


# --- split_shake / shake_stats ---

def test_split_shake_separates_bands():
    t = np.arange(900) / FPS
    slow = 0.2 * np.sin(2 * np.pi * 0.2 * t)
    fast = 0.005 * np.sin(2 * np.pi * 5.0 * t)
    low, high = split_shake(slow + fast, FPS, cutoff_hz=1.5)
    np.testing.assert_allclose(low[60:-60], slow[60:-60], atol=2e-3)
    s = shake_stats(high, FPS)
    assert s["rms"] == pytest.approx(0.005 / np.sqrt(2), rel=0.15)
    assert s["dominant_hz"] == pytest.approx(5.0, abs=0.3)


# --- deadzone ---

def test_simulate_follow_ignores_motion_inside_deadzone():
    target = np.r_[np.zeros(30), np.full(300, 0.05)]
    y = simulate_follow(target, FPS, omega=6.0, zeta=0.9, delay_s=0.0, deadzone=0.1)
    np.testing.assert_allclose(y, 0.0)


def test_simulate_follow_deadzone_follows_only_excess():
    target = np.r_[np.zeros(30), np.full(300, 0.5)]
    y = simulate_follow(target, FPS, omega=6.0, zeta=0.9, delay_s=0.0, deadzone=0.1)
    assert y[-1] == pytest.approx(0.4, abs=1e-3)  # 데드존 경계까지만 따라감


def test_fit_follow_recovers_deadzone():
    target = smooth_steps() * 2
    cam = simulate_follow(target, FPS, omega=4.0, zeta=0.7, delay_s=0.2, deadzone=0.08)
    p = fit_follow(target, cam, FPS)
    assert p["deadzone"] == pytest.approx(0.08, abs=0.02)
    assert p["omega"] == pytest.approx(4.0, rel=0.2)
    assert p["delay_s"] == pytest.approx(0.2, abs=0.08)


def test_fit_follow_handles_constant_framing_offset():
    target = smooth_steps()
    cam = simulate_follow(target, FPS, omega=4.0, zeta=0.7, delay_s=0.2) - 0.15
    p = fit_follow(target, cam, FPS)
    assert p["offset"] == pytest.approx(-0.15, abs=0.02)
    assert p["rmse"] < 5e-3


# --- 리뷰 반영 ---

def test_simulate_follow_stable_at_high_omega():
    """LOW-1: 큰 omega에서도 발산하지 않아야 한다(슬라이더 노출 대비)."""
    target = np.r_[np.zeros(30), np.ones(300)]
    y = simulate_follow(target, FPS, omega=400.0, zeta=0.9, delay_s=0.0)
    assert np.isfinite(y).all()
    assert y[-1] == pytest.approx(1.0, abs=1e-3)


def test_simulate_follow_delay_independent_of_fps():
    """LOW-2: 같은 delay_s면 fps가 달라도 반응 시작 시각이 같아야 한다."""
    starts = []
    for fps in (30.0, 60.0):
        n = int(3 * fps)
        k = int(fps)  # 1초에 스텝
        target = np.r_[np.zeros(k), np.ones(n - k)]
        y = simulate_follow(target, fps, omega=20.0, zeta=1.0, delay_s=0.2)
        starts.append(np.argmax(y > 1e-9) / fps)
    assert starts == pytest.approx([1.2, 1.2], abs=1e-9)  # 지연 외에 프레임 하나를 더 얹지 않는다
