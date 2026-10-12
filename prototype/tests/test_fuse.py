import numpy as np
import pytest

from tracking.fuse import HOLD, INTERP, BOX, POSE, box_offset, fuse_track

N = 100


def line_track(n=N):
    t = np.linspace(0, 1, n)
    return np.stack([0.3 + 0.4 * t, 0.8 - 0.4 * t], axis=1)


def box_around(c, h=0.3, w=0.15, dy=0.0):
    """중심 c(몸통)를 기준으로 한 사람 박스 (x0, y0, x1, y1). dy: 박스 중심 - 몸통."""
    cx, cy = c[0], c[1] + dy
    return (cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)


def test_full_pose_passes_through():
    pose = line_track()
    track, src = fuse_track(pose, [[] for _ in range(N)])
    np.testing.assert_allclose(track, pose)
    assert (src == POSE).all()


def test_gap_without_boxes_is_interpolated():
    truth = line_track()
    pose = truth.copy()
    pose[40:60] = np.nan
    track, src = fuse_track(pose, [[] for _ in range(N)])
    np.testing.assert_allclose(track, truth, atol=1e-9)
    assert (src[40:60] == INTERP).all()


def test_gap_filled_from_nearby_box_and_far_box_ignored():
    truth = line_track()
    truth[40:60, 0] += 0.05  # 보간으로는 알 수 없는 실제 움직임
    pose = truth.copy()
    pose[40:60] = np.nan
    boxes = [[] for _ in range(N)]
    for i in range(40, 60):
        boxes[i] = [box_around((0.95, 0.9)), box_around(truth[i])]  # 멀리 있는 사람 + 등반자
    track, src = fuse_track(pose, boxes)
    np.testing.assert_allclose(track[40:60], truth[40:60], atol=1e-6)
    assert (src[40:60] == BOX).all()


def test_only_far_box_falls_back_to_interp():
    pose = line_track()
    pose[40:60] = np.nan
    boxes = [[] for _ in range(N)]
    for i in range(40, 60):
        boxes[i] = [box_around((0.95, 0.1))]
    _, src = fuse_track(pose, boxes)
    assert (src[40:60] == INTERP).all()


def test_box_offset_is_calibrated_from_overlap_frames():
    truth = line_track()
    boxes = [[box_around(c, h=0.3, dy=0.06)] for c in truth]  # 박스 중심이 몸통보다 아래
    off = box_offset(truth, boxes)
    assert off == pytest.approx(0.06 / 0.3, abs=1e-6)  # 박스 높이 대비
    pose = truth.copy()
    pose[40:60] = np.nan
    track, _ = fuse_track(pose, boxes)
    np.testing.assert_allclose(track[40:60], truth[40:60], atol=1e-6)


def test_pose_identity_jump_is_rejected():
    truth = line_track()
    pose = truth.copy()
    pose[50:53] = [0.95, 0.9]  # 잠깐 다른 사람으로 튐
    track, src = fuse_track(pose, [[] for _ in range(N)])
    np.testing.assert_allclose(track[50:53], truth[50:53], atol=1e-6)
    assert (src[50:53] == INTERP).all()


def test_edges_are_held():
    truth = line_track()
    pose = truth.copy()
    pose[:10] = np.nan
    pose[-10:] = np.nan
    track, src = fuse_track(pose, [[] for _ in range(N)])
    np.testing.assert_allclose(track[:10], np.tile(truth[10], (10, 1)))
    np.testing.assert_allclose(track[-10:], np.tile(truth[-11], (10, 1)))
    assert (src[:10] == HOLD).all() and (src[-10:] == HOLD).all()


# --- body_boxes ---

def test_body_boxes_fill_gaps_relative_to_track():
    from tracking.fuse import body_boxes
    track = line_track()
    rel = np.array([-0.05, -0.2, 0.05, 0.1])
    raw = np.hstack([track, track]) + rel
    raw[40:60] = np.nan
    out = body_boxes(raw, track)
    np.testing.assert_allclose(out, np.hstack([track, track]) + rel, atol=1e-9)


def test_body_boxes_hold_offsets_at_edges():
    from tracking.fuse import body_boxes
    track = line_track()
    raw = np.hstack([track, track]) + [-0.05, -0.2, 0.05, 0.1]
    raw[:10] = np.nan
    out = body_boxes(raw, track)
    np.testing.assert_allclose(out[:10] - np.hstack([track[:10], track[:10]]), np.tile([-0.05, -0.2, 0.05, 0.1], (10, 1)), atol=1e-9)


# --- 리뷰 반영 ---

def test_body_boxes_ignore_rejected_pose_frames():
    """HIGH-1: _reject_jumps로 버린 프레임(다른 사람)의 몸 박스가 남으면 안 된다."""
    from tracking.fuse import body_boxes
    truth = line_track()
    pose = truth.copy()
    pose[50] = [1.5, 0.5]  # 다른 사람
    track, src = fuse_track(pose, [[] for _ in range(N)])
    rel = np.array([-0.05, -0.2, 0.05, 0.1])
    raw = np.hstack([pose, pose]) + rel  # 원본 랜드마크 박스: 50번은 다른 사람 위치
    out = body_boxes(raw, track, src)
    np.testing.assert_allclose(out[50], np.hstack([track[50], track[50]]) + rel, atol=1e-9)


def test_body_boxes_without_any_pose_returns_nan_instead_of_crash():
    from tracking.fuse import body_boxes
    track, src = fuse_track(np.full((N, 2), np.nan), [[] for _ in range(N)])
    assert np.isnan(body_boxes(np.full((N, 4), np.nan), track, src)).all()


def test_box_offset_calibrated_from_sparse_overlap_like_pipeline():
    """HIGH-2: 파이프라인처럼 포즈 프레임엔 띄엄띄엄(15프레임마다)만 박스가 있어도 보정돼야 한다."""
    truth = line_track()
    pose = truth.copy()
    pose[40:60] = np.nan
    boxes = [[] for _ in range(N)]
    for i in list(range(0, N, 15)) + list(range(40, 60)):
        boxes[i] = [box_around(truth[i], h=0.3, dy=0.06)]
    track, src = fuse_track(pose, boxes)
    assert (src[40:60] == BOX).all()
    np.testing.assert_allclose(track[40:60], truth[40:60], atol=1e-6)


# --- 짧게 튀는 가짜 포즈 제거 (사용자 제보: 그네 동작 중 홀드 위 가짜 포즈) ---

def swing_track(n=300):
    """그네처럼 좌우로 크게 오가는 실제 움직임."""
    t = np.arange(n) / 30.0
    return np.stack([0.75 + 0.12 * np.sin(2 * np.pi * 0.5 * t), 0.5 + 0.02 * np.cos(2 * np.pi * 0.5 * t)], axis=1)


def test_short_spurious_pose_between_gaps_is_rejected():
    truth = swing_track()
    pose = truth.copy()
    pose[113:121] = np.nan
    pose[121:123] = [[0.63, 0.62], [0.62, 0.67]]  # 아래 왼쪽 홀드 위 가짜 포즈(실제 사례와 같은 크기의 튐)
    pose[123:129] = np.nan
    track, src = fuse_track(pose, [[] for _ in range(len(pose))])
    assert (src[121:123] == INTERP).all()
    assert np.max(np.linalg.norm(track[110:132] - truth[110:132], axis=1)) < 0.05


def test_fast_real_motion_is_kept():
    # 다이노: 0.4초 동안 0.3 높이를 실제로 이동 — 주변 포즈들도 같이 움직이므로 버리면 안 된다
    n = 120
    x = np.full(n, 0.5)
    y = np.r_[np.full(50, 0.7), np.linspace(0.7, 0.4, 12), np.full(n - 62, 0.4)]
    pose = np.stack([x, y], axis=1)
    pose[::2] = np.nan  # 듬성듬성한 샘플(앱은 3프레임마다 추정)
    track, src = fuse_track(pose, [[] for _ in range(n)])
    assert (src[1::2] == POSE).all()


def test_sparse_samples_still_reject_isolated_outlier():
    truth = swing_track()
    pose = np.full_like(truth, np.nan)
    pose[::3] = truth[::3]  # 앱처럼 3프레임마다
    pose[120] = [0.62, 0.67]
    track, src = fuse_track(pose, [[] for _ in range(len(pose))])
    assert src[120] == INTERP


def test_fast_drop_at_end_of_track_is_kept():
    # 등반 끝에 뛰어내림: 마지막 포즈들 뒤엔 이웃이 없다 — 한쪽 이웃만으로는 판단하지 않는다
    n = 90
    y = np.r_[np.full(80, 0.3), np.linspace(0.3, 0.8, 10)]
    pose = np.stack([np.full(n, 0.5), y], axis=1)
    pose[np.arange(n) % 3 != 0] = np.nan
    _, src = fuse_track(pose, [[] for _ in range(n)])
    assert src[87] == POSE


def test_spurious_run_inside_long_gap_is_rejected():
    # 기기 실측 재현: 포즈가 드문드문(6.4s 실제, 8.3/8.5s 왼쪽 끝 가짜, 10.6s 실제)
    n = 360
    pose = np.full((n, 2), np.nan)
    for i in [150, 165, 180, 192]:
        pose[i] = [0.90, 0.48]
    pose[250] = [0.145, 0.45]
    pose[256] = [0.145, 0.47]
    for i in [318, 321, 330, 345]:
        pose[i] = [0.92, 0.41]
    track, src = fuse_track(pose, [[] for _ in range(n)])
    assert src[250] == INTERP and src[256] == INTERP
    assert np.all(track[192:318, 0] > 0.85)


def test_steady_move_with_sparse_samples_is_kept():
    # 드문드문한 샘플이라도 꾸준한 실제 이동(트래버스)은 직선에 맞으므로 남는다
    n = 360
    pose = np.full((n, 2), np.nan)
    for i in [150, 165, 180, 250, 256, 318, 330]:
        pose[i] = [0.9 - 0.004 * (i - 150), 0.5]
    _, src = fuse_track(pose, [[] for _ in range(n)])
    assert all(src[i] == POSE for i in [150, 165, 180, 250, 256, 318, 330])


def test_real_lunge_between_sparse_clusters_is_kept():
    # 실측(그네 영상 10.6~11.8s): 오른쪽에 머물다(포즈 2개) 1초 뒤 왼쪽으로 크게 이동 — 왕복이 아니라 편도이므로 남긴다
    n = 400
    pose = np.full((n, 2), np.nan)
    for i in [240, 241, 242]:
        pose[i] = [0.92, 0.45]  # 8.0s 근처
    pose[319] = [0.918, 0.406]  # 10.64s
    pose[322] = [0.923, 0.404]  # 10.75s
    for i in range(352, 380):
        pose[i] = [0.62 - 0.001 * (i - 352), 0.47]  # 11.75s~ 왼쪽
    _, src = fuse_track(pose, [[] for _ in range(n)])
    assert src[319] == POSE and src[322] == POSE


def test_device_pattern_single_real_before_fake_pair_then_real():
    # 기기 실측 그대로: 6.43s 실제 1개 → 8.33/8.53s 가짜 2개 → 10.63/10.73s 실제
    n = 400
    pose = np.full((n, 2), np.nan)
    pose[193] = [0.903, 0.489]
    pose[250] = [0.144, 0.450]
    pose[256] = [0.145, 0.476]
    pose[319] = [0.922, 0.412]
    pose[322] = [0.918, 0.409]
    _, src = fuse_track(pose, [[] for _ in range(n)])
    assert src[250] == INTERP and src[256] == INTERP
    assert src[193] == POSE and src[319] == POSE and src[322] == POSE


# --- 광학 흐름으로 포즈 공백 메우기 ---
from tracking.fuse import FLOW


def test_flow_fills_gap_and_is_corrected_to_next_pose():
    n = 40
    truth = np.stack([np.linspace(0.3, 0.7, n), np.full(n, 0.5)], axis=1)
    pose = truth.copy()
    pose[11:30] = np.nan
    flow = np.full((n, 2), np.nan)
    seed = np.full(n, -1)
    # 흐름은 실제보다 조금씩 덜 움직였다고 측정(드리프트) — 구간 끝에서 다음 포즈에 맞게 보정돼야 한다
    for i in range(11, 30):
        flow[i] = truth[10] + (truth[i] - truth[10]) * 0.9
        seed[i] = 10
    track, src = fuse_track(pose, [[] for _ in range(n)], flow=flow, flow_seed=seed)
    assert (src[11:30] == FLOW).all()
    np.testing.assert_allclose(track[29], truth[29], atol=0.004)  # 끝은 다음 포즈와 거의 일치
    assert np.max(np.abs(track[11:30, 0] - truth[11:30, 0])) < 0.01


def test_flow_from_rejected_seed_is_ignored():
    n = 300
    t = np.arange(n) / 30.0
    truth = np.stack([0.75 + 0.12 * np.sin(np.pi * t), 0.5 + 0.02 * np.cos(np.pi * t)], axis=1)
    pose = truth.copy()
    pose[113:121] = np.nan
    pose[121] = [0.63, 0.62]  # 가짜 포즈(거부됨)
    pose[122:129] = np.nan
    flow = np.full((n, 2), np.nan)
    seed = np.full(n, -1)
    for i in range(122, 129):
        flow[i] = [0.63, 0.62]  # 가짜에서 시작한 흐름
        seed[i] = 121
    _, src = fuse_track(pose, [[] for _ in range(n)], flow=flow, flow_seed=seed)
    assert (src[122:129] != FLOW).all()


def test_flow_lost_midway_then_interpolates_to_next_pose():
    n = 40
    pose = np.full((n, 2), np.nan)
    pose[:11] = [0.3, 0.5]
    pose[30:] = [0.7, 0.5]
    flow = np.full((n, 2), np.nan)
    seed = np.full(n, -1)
    for i in range(11, 18):  # 7프레임만 따라가다 놓침
        flow[i] = [0.3 + 0.01 * (i - 10), 0.5]
        seed[i] = 10
    track, src = fuse_track(pose, [[] for _ in range(n)], flow=flow, flow_seed=seed)
    assert (src[11:18] == FLOW).all() and (src[18:30] == INTERP).all()
    # 놓친 뒤엔 마지막 흐름 위치에서 다음 포즈로 이어진다
    assert track[18, 0] > track[17, 0] - 1e-9 and track[29, 0] < 0.7
