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
