import numpy as np
import pytest

from tracking.flow import bridge, track_points

def textured(h=240, w=320, seed=0):
    rng = np.random.default_rng(seed)
    img = rng.integers(0, 255, (h // 8, w // 8)).astype(np.uint8)
    import cv2
    return cv2.GaussianBlur(cv2.resize(img, (w, h), interpolation=cv2.INTER_NEAREST), (5, 5), 1.5)


def test_track_points_follows_moving_patch_and_ignores_static_background():
    import cv2
    bg = textured(seed=1)
    patch = textured(60, 60, seed=2)
    frames = []
    for k in range(10):
        f = bg.copy()
        x, y = 100 + 4 * k, 80 + 2 * k
        f[y:y + 60, x:x + 60] = patch
        frames.append(f)
    # 처음 패치 위치(몸 박스)에서 시작
    disp = track_points(frames, box=(100, 80, 160, 140))
    assert disp.shape == (10, 2)
    np.testing.assert_allclose(disp[-1], [36, 18], atol=1.5)


def test_track_points_follows_climber_when_most_seed_points_are_on_the_wall():
    # 사용자 영상(20260914_145634 8~11초): 몸 박스 대부분이 벽이라 중앙값이 0에 붙어 카메라가 멈춤 — Kotlin FlowTracker와 같은 기준
    bg = textured(seed=1)
    patch = textured(60, 60, seed=2)
    frames = []
    for k in range(10):
        f = bg.copy()
        x, y = 100 + 4 * k, 80 + 2 * k
        f[y:y + 60, x:x + 60] = patch
        frames.append(f)
    disp = track_points(frames, box=(55, 35, 205, 185))
    # OpenCV는 패치 경계의 강한 모서리를 많이 골라 Kotlin(약 32px)보다 덜 따라간다(약 21px) — 그래도 수정 전(약 1px, 멈춤)과는 확연히 다르게
    assert 18 <= disp[-1][0] <= 38 and 8 <= disp[-1][1] <= 20, disp[-1]


def test_track_points_still_climber_stays_still_among_wall_points():
    bg = textured(seed=1)
    patch = textured(60, 60, seed=2)
    f = bg.copy()
    f[80:140, 100:160] = patch
    disp = track_points([f] * 10, box=(55, 35, 205, 185))
    np.testing.assert_allclose(disp[-1], [0, 0], atol=0.2)


def test_bridge_corrects_drift_to_match_next_pose():
    # 흐름이 끝에서 (10, 0)만큼 어긋나면 구간에 걸쳐 선형으로 나눠 보정
    flow = np.array([[0, 0], [5, 0], [10, 0], [20, 0]], float)
    out = bridge(start=np.array([100.0, 50.0]), flow=flow, end=np.array([130.0, 50.0]))
    np.testing.assert_allclose(out[0], [100, 50])
    np.testing.assert_allclose(out[-1], [130, 50])
    np.testing.assert_allclose(out[2], [100 + 10 + 10 * 2 / 3, 50])


def test_bridge_without_end_pose_keeps_flow():
    flow = np.array([[0, 0], [3, 1], [6, 2]], float)
    out = bridge(start=np.array([10.0, 10.0]), flow=flow, end=None)
    np.testing.assert_allclose(out, [[10, 10], [13, 11], [16, 12]])


def test_drop_wall_points_never_leaves_too_few_points():
    # 움직이는 점이 딱 최소 개수일 때 벽 점을 버리면 추적이 끊긴다 — 그럴 땐 버리지 않는다(Kotlin과 같은 기준)
    from tracking.flow import MIN_POINTS, _drop_wall_points
    # 움직이는 점 5개 중 3개가 크게(100px) 움직이면 기준(중앙값의 30% = 30px)이 높아져 나머지 둘이 버려진다 → 3개만 남음
    shift = np.array([2.5, 2.5, 100.0, 100.0, 100.0] + [0.0] * 3)
    alive = np.ones(len(shift), bool)
    out = _drop_wall_points(shift, alive)
    assert out.sum() >= MIN_POINTS
