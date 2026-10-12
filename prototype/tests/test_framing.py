import numpy as np
import pytest

from analysis.framing import body_extent, interp_nans, still_offsets, to_world, torso_center


def pose(cx, cy, h=0.4, vis=1.0):
    """정규화(0..1, x는 폭 기준) 33개 랜드마크: 머리(0)가 위, 발(27~32)이 아래."""
    lm = np.zeros((33, 4))
    lm[:, 0] = cx
    lm[:, 1] = cy
    lm[:, 3] = vis
    lm[0, 1] = cy - h / 2
    lm[27:33, 1] = cy + h / 2
    return lm


def test_torso_center_converts_x_to_height_units():
    lm = pose(0.5, 0.6)
    lm[[11, 23], 0] = 0.4
    lm[[12, 24], 0] = 0.6
    c = torso_center(lm[None], aspect=9 / 16)
    np.testing.assert_allclose(c[0], [0.5 * 9 / 16, 0.6])


def test_torso_center_nan_when_missing():
    c = torso_center(np.full((1, 33, 4), np.nan), aspect=1.0)
    assert np.isnan(c).all()


def test_body_extent_ignores_low_visibility():
    lm = pose(0.5, 0.5, h=0.4)
    lm[27:33, 3] = 0.1  # 발 안 보임
    top, bottom, height = body_extent(lm[None], vis_thr=0.5)[0]
    assert top == pytest.approx(0.3)
    assert bottom == pytest.approx(0.5)
    assert height == pytest.approx(0.2)


def test_interp_nans_fills_gaps_and_edges():
    x = np.array([np.nan, 1.0, np.nan, 3.0, np.nan])
    np.testing.assert_allclose(interp_nans(x), [1, 1, 2, 3, 3])


def test_to_world_inverts_camera_offset_and_scale():
    cam = np.array([[0.1, 0.2, 2.0, 0.0]])  # 카메라 중심 월드 (0.1,0.2), 2배 줌
    p = np.array([[0.7, 0.5]])  # 프레임 중심 (0.5,0.5)에서 +0.2
    np.testing.assert_allclose(to_world(p, cam, center=(0.5, 0.5)), [[0.2, 0.2]])


def test_still_offsets_selects_frames_where_camera_rests():
    fps = 30
    cam_xy = np.zeros((60, 2))
    cam_xy[30:, 1] = np.linspace(0, 1, 30)  # 후반부만 움직임
    offset = np.full((60, 2), 0.05)
    off = still_offsets(cam_xy, offset, fps, speed_thr=0.05)
    assert len(off) == 30
