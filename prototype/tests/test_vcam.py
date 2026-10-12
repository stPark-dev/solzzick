from dataclasses import replace

import numpy as np
import pytest

from vcam.camera import CameraParams, solve_path
from vcam.noise import smooth_noise

FPS = 30.0
W = H = 1440
STILL = replace(CameraParams(), drift_x=0.0, drift_y=0.0, shake_gain=0.0, motion_zoom=False)


def static_track(x, y, n=300):
    return np.tile([x, y], (n, 1)).astype(float)


def inside(path, w=W, h=H):
    cx, cy, cw, ch, rot = path.T
    t = np.radians(np.abs(rot))
    bw = (cw * np.cos(t) + ch * np.sin(t)) / 2
    bh = (cw * np.sin(t) + ch * np.cos(t)) / 2
    eps = 1e-6
    return np.all(cx - bw >= -eps) and np.all(cx + bw <= w + eps) and np.all(cy - bh >= -eps) and np.all(cy + bh <= h + eps)


# --- smooth_noise ---

def test_noise_is_deterministic_and_unit_rms():
    a = smooth_noise(30000, FPS, hz=1.0, seed=7)
    b = smooth_noise(30000, FPS, hz=1.0, seed=7)
    np.testing.assert_array_equal(a, b)
    assert np.sqrt(np.mean(a ** 2)) == pytest.approx(1.0, rel=0.05)  # 이론 RMS 1 (장기 평균)
    assert abs(a.mean()) < 0.1


def test_noise_is_smooth_relative_to_frequency():
    a = smooth_noise(3000, FPS, hz=0.1, seed=1)
    assert np.max(np.abs(np.diff(a))) < 0.05  # 0.1Hz면 프레임 간 변화가 아주 작다


def test_noise_seeds_differ():
    assert not np.allclose(smooth_noise(300, FPS, 1.0, seed=1), smooth_noise(300, FPS, 1.0, seed=2))


# --- crop size / zoom ---

def test_crop_size_follows_zoom_and_aspect():
    path = solve_path(static_track(720, 720), FPS, W, H, STILL)
    cw, ch = path[0, 2], path[0, 3]
    assert ch == pytest.approx(H / STILL.zoom)
    assert cw / ch == pytest.approx(9 / 16)


def test_max_upscale_limits_zoom():
    p = replace(STILL, zoom=3.0, max_upscale=2.0, out_height=1920)
    path = solve_path(static_track(720, 720), FPS, W, H, p)
    assert path[0, 3] == pytest.approx(1920 / 2.0)


# --- framing ---

def test_static_climber_centered_with_headroom():
    path = solve_path(static_track(700, 800), FPS, W, H, STILL)
    ch = path[0, 3]
    np.testing.assert_allclose(path[:, 0], 700)
    np.testing.assert_allclose(path[:, 1], 800 - STILL.headroom * ch)
    np.testing.assert_allclose(path[:, 4], 0.0)


def test_small_vertical_motion_inside_deadzone_is_ignored():
    track = static_track(720, 800)
    ch = H / STILL.zoom
    track[100:, 1] -= 0.5 * STILL.deadzone_y * ch
    path = solve_path(track, FPS, W, H, STILL)
    assert np.ptp(path[:, 1]) < 1e-6


def test_large_vertical_motion_is_followed_up_to_deadzone_edge():
    track = static_track(720, 1000)
    ch = H / STILL.zoom
    track[100:, 1] = 700
    path = solve_path(track, FPS, W, H, STILL)
    expected = 700 - STILL.headroom * ch + STILL.deadzone_y * ch
    assert path[-1, 1] == pytest.approx(expected, abs=0.5)


def test_follow_is_delayed():
    track = static_track(500, 720)
    track[100:, 0] = 900
    path = solve_path(track, FPS, W, H, STILL)
    assert path[100 + int(STILL.delay_s * FPS) - 1, 0] == pytest.approx(path[99, 0])
    assert path[-1, 0] == pytest.approx(900 - STILL.deadzone_x * H / STILL.zoom, abs=0.5)


# --- bounds ---

def test_crop_never_leaves_source_even_with_shake_and_edge_climber():
    rng = np.random.default_rng(0)
    track = np.cumsum(rng.normal(0, 15, (900, 2)), axis=0) + 720
    track[:150] = [5, 5]  # 모서리
    p = replace(CameraParams(), shake_gain=10.0)
    path = solve_path(track, FPS, W, H, p)
    assert inside(path)


def test_low_zoom_without_rotation_room_still_inside():
    p = replace(CameraParams(), zoom=1.0, shake_gain=10.0)
    path = solve_path(static_track(720, 720), FPS, W, H, p)
    assert inside(path)


# --- handheld ---

def test_shake_gain_zero_and_drift_zero_is_deterministic_still():
    path = solve_path(static_track(720, 720), FPS, W, H, STILL)
    assert np.ptp(path[:, 0]) == 0 and np.ptp(path[:, 1]) == 0


def test_shake_scales_with_gain_and_is_reproducible():
    base = replace(STILL, shake_gain=1.0)
    a = solve_path(static_track(720, 720), FPS, W, H, base)
    b = solve_path(static_track(720, 720), FPS, W, H, base)
    c = solve_path(static_track(720, 720), FPS, W, H, replace(base, shake_gain=5.0))
    np.testing.assert_array_equal(a, b)
    ra, rc = np.std(a[:, 0]), np.std(c[:, 0])
    assert rc == pytest.approx(5 * ra, rel=0.05)
    assert np.std(a[:, 4]) > 0  # roll 포함


def test_drift_amplitude_matches_param():
    p = replace(STILL, drift_x=0.03)
    path = solve_path(static_track(720, 720, n=30000), FPS, W, H, p)
    ch = path[0, 3]
    assert np.std(path[:, 0]) == pytest.approx(0.03 * ch, rel=0.25)


# --- auto zoom (body fit) ---

def body_box(track, half_w, up, down):
    """몸통 중심 기준 몸 전체 박스 (x0, y0, x1, y1)."""
    t = np.asarray(track, float)
    return np.stack([t[:, 0] - half_w, t[:, 1] - up, t[:, 0] + half_w, t[:, 1] + down], axis=1)


def body_inside(path, body):
    cx, cy, cw, ch = path[:, 0], path[:, 1], path[:, 2], path[:, 3]
    return (body[:, 0] >= cx - cw / 2) & (body[:, 2] <= cx + cw / 2) & (body[:, 1] >= cy - ch / 2) & (body[:, 3] <= cy + ch / 2)


def test_small_body_keeps_base_zoom():
    track = static_track(720, 800)
    path = solve_path(track, FPS, W, H, STILL, body=body_box(track, 60, 150, 150))
    np.testing.assert_allclose(path[:, 3], H / STILL.zoom)


def test_large_body_zooms_out_to_fit():
    track = static_track(720, 720)
    body = body_box(track, 380, 300, 300)  # 기본 크롭 폭(623px)보다 넓게 팔을 벌림
    path = solve_path(track, FPS, W, H, STILL, body=body)
    assert path[0, 3] > H / STILL.zoom
    assert body_inside(path, body).all()


def test_transient_reach_zooms_out_ahead_and_returns():
    n = 600
    track = static_track(720, 1100, n=n)  # 아래쪽에 있는 등반자
    body = body_box(track, 80, 150, 150)
    body[300:330] = body_box(track[300:330], 80, 900, 150)  # 1초간 위로 크게 뻗음
    path = solve_path(track, FPS, W, H, STILL, body=body)
    base = H / STILL.zoom
    assert body_inside(path[300:330], body[300:330]).all()
    assert path[295, 3] > base  # 미리 줌아웃 시작
    assert path[-1, 3] == pytest.approx(base, rel=0.01)  # 다시 돌아옴


def test_zoom_changes_smoothly_and_stays_in_source():
    n = 600
    track = static_track(720, 800, n=n)
    body = body_box(track, 80, 150, 150)
    body[300:330] = body_box(track[300:330], 700, 700, 700)  # 원본보다 큰 요구
    path = solve_path(track, FPS, W, H, replace(CameraParams(), shake_gain=10.0), body=body)
    assert path[:, 3].max() <= H + 1e-6 and path[:, 2].max() <= W + 1e-6
    assert np.max(np.abs(np.diff(path[:, 3]))) < 0.02 * H  # 프레임당 2% 이하 변화
    assert inside(path)


# --- 리뷰 반영 ---

def test_solve_path_rejects_too_short_or_missing_track():
    with pytest.raises(ValueError):
        solve_path(static_track(720, 720, n=1), FPS, W, H)
    with pytest.raises(ValueError):
        solve_path(np.full((10, 2), np.nan), FPS, W, H)


def test_noise_speed_does_not_depend_on_clip_length():
    """MEDIUM-2: 짧은 클립을 RMS=1로 늘려 드리프트가 빨라지면 안 된다."""
    long = smooth_noise(30000, FPS, hz=0.1, seed=3)
    short = smooth_noise(30, FPS, hz=0.1, seed=3)
    assert np.max(np.abs(np.diff(short))) <= 2 * np.max(np.abs(np.diff(long)))
    np.testing.assert_array_equal(short, long[:30])  # 같은 seed면 앞부분이 같다


def test_noise_two_frames_is_finite():
    assert np.isfinite(smooth_noise(2, FPS, hz=1.0, seed=0)).all()
    assert np.isfinite(smooth_noise(1, FPS, hz=1.0, seed=0)).all()


# --- 언어 간 재현 가능한 난수 (Kotlin 포팅과 비트 단위 일치용) ---

def test_splitmix64_reference_vectors():
    from vcam.noise import SplitMix64
    r = SplitMix64(0)
    assert [r.next_u64() for _ in range(3)] == [0xE220A8397B1DCDAF, 0x6E789E6AA1B965F4, 0x06C45D188009454F]


def test_gaussian_is_standard_normal():
    from vcam.noise import SplitMix64
    r = SplitMix64(42)
    z = np.array([r.next_gaussian() for _ in range(20000)])
    assert abs(z.mean()) < 0.03 and z.std() == pytest.approx(1.0, abs=0.03)


# --- 비율 선택: 확대 배율은 '그 비율의 가장 넓은 크롭' 기준 ---

def test_zoom_is_relative_to_widest_crop_for_wide_aspect():
    from vcam.camera import crop_size
    p = replace(CameraParams(), aspect=16 / 9, out_height=1080, zoom=1.0)
    cw, ch = crop_size(1440, 1440, p)
    assert (cw, ch) == pytest.approx((1440, 810))  # 정사각 원본에서 16:9로 가장 넓게
    _, ch13 = crop_size(1440, 1440, replace(p, zoom=1.3))
    assert ch13 == pytest.approx(810 / 1.3)


def test_vertical_aspect_zoom_unchanged():
    from vcam.camera import crop_size
    _, ch = crop_size(1440, 1440, CameraParams())  # 9:16, zoom 1.3
    assert ch == pytest.approx(1440 / 1.3)


def test_wide_aspect_path_stays_inside():
    p = replace(CameraParams(), aspect=16 / 9, out_height=1080, shake_gain=10.0)
    rng = np.random.default_rng(1)
    track = np.cumsum(rng.normal(0, 15, (600, 2)), axis=0) + 720
    assert inside(solve_path(track, FPS, W, H, p))



# --- 동작에 따른 자동 줌: 크럭스(한자리 오래)엔 타이트, 큰 동작(다이노·추락)엔 넓게 ---
MOTION = replace(CameraParams(), drift_x=0.0, drift_y=0.0, shake_gain=0.0)


def test_crux_tightens_then_returns_when_moving_again():
    n = 360
    y = np.r_[np.full(180, 900.0), 900.0 - 0.1 * 1440 * np.arange(1, 181) / FPS]  # 6초 정지 → 6초 꾸준히 오름(0.1 높이/초)
    track = np.stack([np.full(n, 720.0), y], axis=1)
    path = solve_path(track, FPS, W, H, MOTION)
    base = H / MOTION.zoom
    assert path[90, 3] < base * 0.95  # 크럭스 한가운데: 더 타이트
    assert path[330, 3] == pytest.approx(base, rel=0.01)  # 움직이는 중: 기본


def test_big_move_widens_slightly_before_it_happens():
    n = 300
    y = np.r_[np.full(150, 1000.0), np.linspace(1000, 300, 15), np.full(135, 300.0)]  # 0.5초 만에 크게 이동(다이노)
    x = 720.0 + 2.0 * np.arange(n)  # 크럭스로 잡히지 않게 조금씩 이동
    path = solve_path(np.stack([x, y], axis=1), FPS, W, H, MOTION)
    base = H / MOTION.zoom
    assert path[157, 3] > base * 1.08  # 큰 동작 중: 넓게
    assert path[144, 3] > base * 1.02  # 직전부터 미리 넓어지기 시작


def test_motion_zoom_respects_upscale_limit_and_source():
    n = 300
    track = np.tile([720.0, 900.0], (n, 1))
    p = replace(MOTION, zoom=1.5, max_upscale=2.0, crux_zoom=1.5)
    path = solve_path(track, FPS, W, H, p)
    assert path[:, 3].min() >= 1920 / 2.0 - 1e-6
    assert path[:, 3].max() <= H + 1e-6


def test_short_pause_does_not_pulse_the_zoom():
    # 4초 잠깐 멈췄다 다시 움직임 — 크럭스 조건(3초 창)을 잠깐만 만족해 줌이 숨쉬듯 들썩였었다(실측 그래프)
    n = 360
    v = 0.1 * 1440 / FPS
    seg = np.r_[np.arange(120) * v, np.full(120, 119 * v), 119 * v + np.arange(1, 121) * v]
    track = np.stack([np.full(n, 720.0), 1100.0 - seg], axis=1)
    base = H / MOTION.zoom
    bumpy = solve_path(track, FPS, W, H, replace(MOTION, crux_min_s=0.0))
    assert bumpy[:, 3].min() < base * 0.97  # 규칙이 없으면 짧게 타이트해짐(이 상황을 재현하는지 확인)
    path = solve_path(track, FPS, W, H, MOTION)
    assert path[:, 3].min() > base * 0.98
