"""포즈 몸통 중심 + 사람 검출 박스 → 끊김 없는 등반자 트랙.

좌표는 프레임 높이 = 1 정규화(x도 높이 단위). 오프라인이므로 빈 구간의 예상 위치는
앞뒤 포즈 사이 보간으로 구하고, 그 근처의 사람 박스만 등반자로 인정한다.
"""
import numpy as np

POSE, BOX, INTERP, HOLD, FLOW = 0, 1, 2, 3, 4


def _center(box):
    return np.array([(box[0] + box[2]) / 2, (box[1] + box[3]) / 2])


def box_offset(pose, boxes):
    """(박스 중심 y - 몸통 y) / 박스 높이 의 중앙값. 포즈와 박스가 겹치는 프레임으로 보정."""
    offs = []
    for p, bs in zip(pose, boxes):
        if np.isnan(p[0]) or not bs:
            continue
        inside = [b for b in bs if b[0] <= p[0] <= b[2] and b[1] <= p[1] <= b[3]]
        if inside:
            b = min(inside, key=lambda b: np.linalg.norm(_center(b) - p))
            offs.append(((b[1] + b[3]) / 2 - p[1]) / (b[3] - b[1]))
    return float(np.median(offs)) if offs else 0.0


def _reject_jumps(pose, fps, max_speed, base):
    """직전 채택 위치에서 물리적으로 불가능하게 튄 포즈(다른 사람)를 결측 처리."""
    out = pose.copy()
    last, last_i = None, None
    for i, p in enumerate(pose):
        if np.isnan(p[0]):
            continue
        if last is not None and np.linalg.norm(p - last) > base + max_speed * (i - last_i) / fps:
            out[i] = np.nan
            continue
        last, last_i = p, i
    return out


def _reject_outliers(pose, fps, window_s=0.6, k=4.0, min_dev=0.1, min_neighbors=4, min_each_side=2,
                     max_gap_s=3.0, extend_each_side=3, agree_tol=0.15, passes=3):
    """앞뒤 window_s 안의 다른 포즈들로 직선(시간→위치)을 맞추고, 그 직선에서 혼자 크게 벗어난 포즈를 결측 처리.
    그네·스윙 중 홀드 위에 잠깐 생기는 가짜 포즈처럼 '짧게 튀었다 돌아오는' 값을 잡는다.
    실제 빠른 이동(다이노·추락)은 주변 포즈들도 함께 움직여 직선에 잘 맞으므로 남는다.
    앞뒤 양쪽에 이웃이 있을 때만 판단한다 — 한쪽만으로 외삽하면 등반 끝의 뛰어내림을 오판한다.
    포즈가 드문드문해 한쪽 이웃이 window_s 안에 부족하면, 그쪽은 max_gap_s 안의 가장 가까운 extend_each_side개로 넓히고
    직선 대신 '왕복' 여부로 판단한다: 앞뒤 위치가 서로 일치(agree_tol 이내)하는데 혼자 둘 다에서 멀면 가짜
    (기기 실측: 포즈가 2초씩 끊긴 사이에 잠깐 잡힌 가짜 포즈). 앞뒤가 다르면 실제 이동(편도)이므로 남긴다.
    버린 포즈는 다음 판단의 이웃에서 빼고, 더 버릴 게 없을 때까지(최대 passes번) 반복한다 — 가짜가 둘 붙어 있으면
    서로를 이웃으로 삼아 살아남기 때문."""
    out = pose.copy()
    for _ in range(passes):
        before_count = np.isnan(out[:, 0]).sum()
        out = _reject_outliers_pass(out, fps, window_s, k, min_dev, min_neighbors, min_each_side,
                                    max_gap_s, extend_each_side, agree_tol)
        if np.isnan(out[:, 0]).sum() == before_count:
            break
    return out


def _reject_outliers_pass(pose, fps, window_s, k, min_dev, min_neighbors, min_each_side,
                          max_gap_s, extend_each_side, agree_tol):
    out = pose.copy()
    ok = np.where(~np.isnan(pose[:, 0]))[0]
    w = int(round(window_s * fps))
    g = int(round(max_gap_s * fps))
    for i in ok:
        before = ok[(ok < i) & (ok >= i - w)]
        after = ok[(ok > i) & (ok <= i + w)]
        sparse = len(before) < min_each_side or len(after) < min_each_side
        if sparse:
            before = ok[(ok < i) & (ok >= i - g)][-extend_each_side:]
            after = ok[(ok > i) & (ok <= i + g)][:extend_each_side]
            if len(before) == 0 or len(after) == 0:  # 드문 구간에선 양쪽에 하나씩만 있어도 왕복 판단
                continue
            mb = np.median(pose[before], axis=0)
            ma = np.median(pose[after], axis=0)
            if (np.linalg.norm(mb - ma) <= agree_tol
                    and np.linalg.norm(pose[i] - mb) > agree_tol and np.linalg.norm(pose[i] - ma) > agree_tol):
                out[i] = np.nan
            continue
        nb = np.concatenate([before, after])
        if len(nb) < min_neighbors:
            continue
        t = (nb - i).astype(float)
        A = np.stack([np.ones_like(t), t], axis=1)
        keep = np.ones(len(nb), bool)
        for _ in range(2):  # 이웃 중의 이상값을 한 번 걸러내고 다시 맞춘다
            coef, *_ = np.linalg.lstsq(A[keep], pose[nb[keep]], rcond=None)
            resid = np.linalg.norm(pose[nb] - A @ coef, axis=1)
            mad = np.median(resid[keep])
            keep = resid <= max(k * mad, min_dev)
            if keep.sum() < min_neighbors:
                break
        if np.linalg.norm(pose[i] - coef[0]) > max(k * mad, min_dev):  # t=0에서의 예측 = coef[0]
            out[i] = np.nan
    return out


def _interp(track):
    i = np.arange(len(track))
    ok = ~np.isnan(track[:, 0])
    return np.stack([np.interp(i, i[ok], track[ok, k]) for k in range(2)], axis=1)


def _fill_flow(pose, ok, flow, seed):
    """포즈 공백(a, b) 안에서 a의 포즈로 시작한 흐름 위치를 쓴다. 흐름이 b 직전까지 이어지면 한 걸음 외삽한 b 위치가
    b의 포즈와 맞도록 어긋남을 선형으로 나눠 보정, 중간에 놓치면 거기까지만 쓴다. 반환 (위치 (n,2) nan 포함, 흐름 프레임 mask)."""
    n = len(pose)
    out = np.full((n, 2), np.nan)
    used = np.zeros(n, bool)
    idx = np.where(ok)[0]
    for a, b in zip(idx[:-1], idx[1:]):
        c = a
        while c + 1 < b and seed[c + 1] == a and np.isfinite(flow[c + 1]).all():
            c += 1
        if c == a:
            continue
        f = flow[a + 1 : c + 1].copy()
        if c == b - 1:
            prev = flow[c - 1] if c - 1 > a else pose[a]
            err = pose[b] - (flow[c] + (flow[c] - prev))
            f += err * ((np.arange(a + 1, c + 1) - a) / (b - a))[:, None]
        out[a + 1 : c + 1] = f
        used[a + 1 : c + 1] = True
    return out, used


def fuse_track(pose, boxes, fps=30.0, gate=0.15, max_speed=3.0, jump_base=0.05, flow=None, flow_seed=None):
    """pose: (n,2) 몸통 중심(결측 nan). boxes: 프레임별 사람 박스 리스트 [(x0,y0,x1,y1), ...].
    flow/flow_seed: 포즈 공백 동안 광학 흐름으로 따라간 위치와, 그 흐름을 시작한 포즈 프레임(-1: 없음).
    반환: (track (n,2), source (n,) — POSE/BOX/INTERP/HOLD/FLOW)."""
    pose = _reject_jumps(np.asarray(pose, float), fps, max_speed, jump_base)
    pose = _reject_outliers(pose, fps)
    n = len(pose)
    ok = ~np.isnan(pose[:, 0])
    if not ok.any():
        return np.full((n, 2), np.nan), np.full(n, HOLD)
    first, last = np.where(ok)[0][[0, -1]]
    track = pose.copy()
    src = np.where(ok, POSE, INTERP)
    if flow is not None:
        fpos, used = _fill_flow(pose, ok, np.asarray(flow, float), np.asarray(flow_seed))
        track[used] = fpos[used]
        src[used] = FLOW
    predicted = _interp(track)
    off = box_offset(pose, boxes)

    for i in range(first + 1, last):
        if ok[i] or src[i] == FLOW or not boxes[i]:
            continue
        cands = [_center(b) - [0, off * (b[3] - b[1])] for b in boxes[i]]
        best = min(cands, key=lambda c: np.linalg.norm(c - predicted[i]))
        if np.linalg.norm(best - predicted[i]) <= gate:
            track[i] = best
            src[i] = BOX
    track = _interp(track)
    src[:first] = HOLD
    src[last + 1 :] = HOLD
    return track, src


def body_boxes(raw, track, source=None):
    """몸 전체 박스(n,4: x0,y0,x1,y1, 결측 nan)의 빈 프레임을 몸통 기준 상대 오프셋 보간으로 채운다.
    source를 주면 채택된 포즈(POSE) 프레임의 박스만 쓴다 — 버린 포즈(다른 사람)의 박스 제외."""
    center = np.hstack([track, track])
    rel = np.asarray(raw, float) - center
    if source is not None:
        rel[np.asarray(source) != POSE] = np.nan
    ok = ~np.isnan(rel[:, 0])
    if not ok.any():
        return np.full_like(rel, np.nan)
    i = np.arange(len(rel))
    filled = np.stack([np.interp(i, i[ok], rel[ok, k]) for k in range(4)], axis=1)
    return center + filled
