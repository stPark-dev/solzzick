"""포즈가 비는 구간을 광학 흐름으로 메우기.

카메라가 고정이라 배경 점은 안 움직이고 등반자 몸 위의 점만 움직인다. 마지막으로 잡힌 몸 박스 안의 특징점을
프레임마다 따라가 '몸이 얼마나 움직였는지'(점 이동량의 중앙값)를 구한다. 흐름은 조금씩 어긋나므로(드리프트)
구간 끝에서 다음 포즈와 맞도록 선형으로 보정한다(bridge).
"""
import cv2
import numpy as np

MIN_POINTS = 5
# 고정 카메라에서 벽은 안 움직인다 — 일부 점이 뚜렷이 움직이면 제자리 점(벽)은 버린다(Kotlin FlowTracker와 같은 기준)
MOVING_PX = 2.0
STILL_PX = 0.5
STILL_RATIO = 0.3
MOVING_FRACTION = 0.1


def _drop_wall_points(shift, alive):
    """shift: 점별 시작 이후 이동 거리. 움직이는 점이 충분하면 그보다 한참 덜 움직인 점(벽)을 alive에서 뺀다."""
    moving = shift[alive & (shift >= MOVING_PX)]
    if len(moving) < MIN_POINTS or len(moving) < alive.sum() * MOVING_FRACTION:
        return alive
    kept = alive & (shift >= max(STILL_PX, float(np.median(moving)) * STILL_RATIO))
    return kept if kept.sum() >= MIN_POINTS else alive  # 버리고 나면 너무 적으면 그대로(추적이 끊기지 않게)


def track_points(frames, box, max_points=60, fb_tol=1.0, win=21, iters=30):
    """frames: 회색조 프레임 목록(첫 프레임에서 box 안의 점으로 시작). 반환 (n,2) 누적 이동량(px), 놓치면 이후 nan.
    앞뒤 확인(forward-backward)으로 엉뚱하게 튄 점은 버린다."""
    # 박스 테두리는 배경(움직이지 않는 벽)이 섞여 이동량을 줄여 잡으므로 안쪽에서만 점을 고른다
    bx0, by0, bx1, by1 = box
    ix, iy = (bx1 - bx0) * 0.15, (by1 - by0) * 0.15
    x0, y0, x1, y1 = (int(round(v)) for v in (bx0 + ix, by0 + iy, bx1 - ix, by1 - iy))
    mask = np.zeros_like(frames[0])
    mask[max(0, y0):y1, max(0, x0):x1] = 255
    p = cv2.goodFeaturesToTrack(frames[0], max_points, 0.01, 4, mask=mask)
    out = np.full((len(frames), 2), np.nan)
    out[0] = 0
    if p is None or len(p) < MIN_POINTS:
        return out
    start = p.reshape(-1, 2).copy()
    cur = p
    alive = np.ones(len(start), bool)
    for k in range(1, len(frames)):
        lk = dict(winSize=(win, win), maxLevel=3, criteria=(cv2.TERM_CRITERIA_COUNT | cv2.TERM_CRITERIA_EPS, iters, 0.01))
        nxt, st, _ = cv2.calcOpticalFlowPyrLK(frames[k - 1], frames[k], cur, None, **lk)
        back, st2, _ = cv2.calcOpticalFlowPyrLK(frames[k], frames[k - 1], nxt, None, **lk)
        fb = np.linalg.norm(back.reshape(-1, 2) - cur.reshape(-1, 2), axis=1)
        alive &= (st.ravel() == 1) & (st2.ravel() == 1) & (fb < fb_tol)
        if alive.sum() < MIN_POINTS:
            break
        cur = nxt
        alive = _drop_wall_points(np.linalg.norm(cur.reshape(-1, 2) - start, axis=1), alive)
        if alive.sum() < MIN_POINTS:
            break
        out[k] = np.median(cur.reshape(-1, 2)[alive] - start[alive], axis=0)
    return out


def bridge(start, flow, end=None):
    """start에서 흐름(누적 이동량)만큼 움직인 위치. end(다음 포즈)가 있으면 끝이 맞도록 어긋남을 선형으로 나눠 보정."""
    pos = start + flow
    if end is None or len(flow) < 2:
        return pos
    err = end - pos[-1]
    w = np.linspace(0, 1, len(flow))[:, None]
    return pos + err * w


def gap_flows(gray_frames, pose, body):
    """앱(Analyzer)처럼: 포즈가 있던 프레임 a 다음이 비면, a의 몸 박스에서 점을 골라 다음 포즈 전까지 프레임마다 따라간다.
    pose/body: 높이 정규화 좌표(프레임 높이=1). 반환 (flow (n,2) 높이 정규화 위치 nan 포함, seed (n,) 시작 프레임 또는 -1)."""
    n = len(pose)
    h = gray_frames[0].shape[0]
    flow = np.full((n, 2), np.nan)
    seed = np.full(n, -1)
    ok = ~np.isnan(pose[:, 0])
    a = 0
    while a < n - 1:
        if not ok[a] or ok[a + 1] or np.isnan(body[a]).any():
            a += 1
            continue
        b = a + 1
        while b < n and not ok[b]:
            b += 1
        disp = track_points(gray_frames[a:b], body[a] * h)
        for k in range(1, b - a):
            if np.isnan(disp[k]).any():
                break
            flow[a + k] = pose[a] + disp[k] / h
            seed[a + k] = a
        a = b
    return flow, seed
