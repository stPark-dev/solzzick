"""가상 카메라: 등반자 트랙 → 프레임별 크롭 사각형.

UI·영상·ML에 의존하지 않는 순수 계산. 길이·위치는 원본 픽셀, 비율 파라미터는 크롭 높이 대비.
기본값은 핸드헬드 레퍼런스 분석(prototype/out/reference_stats.json)에서 승인된 값.
"""
from dataclasses import dataclass

import numpy as np
from scipy.ndimage import maximum_filter1d, minimum_filter1d, uniform_filter1d

from vcam.follow import simulate_follow
from vcam.noise import smooth_noise


@dataclass(frozen=True)
class CameraParams:
    aspect: float = 9 / 16  # 출력 폭/높이
    out_height: int = 1920
    zoom: float = 1.3  # 가장 넓은 크롭 높이 / 크롭 높이
    max_upscale: float = 2.0  # 출력 높이 / 크롭 높이 상한
    headroom: float = 0.12  # 카메라를 몸통보다 이만큼(크롭 높이 비율) 위에 겨눔
    deadzone_x: float = 0.02
    deadzone_y: float = 0.18
    omega: float = 8.0
    zeta: float = 0.9
    delay_s: float = 0.1
    drift_x: float = 0.03  # 느린 프레이밍 드리프트 RMS
    drift_y: float = 0.02
    drift_hz: float = 0.1
    shake_gain: float = 1.0  # 0이면 짐벌 느낌
    shake_x: float = 0.0007  # 손떨림 RMS (gain 1 기준)
    shake_y: float = 0.0003
    shake_roll_deg: float = 0.017
    shake_hz: float = 1.2
    fit_margin: float = 0.04  # 자동 줌: 몸 전체 박스 바깥 여유(박스 크기 대비)
    zoom_lead_s: float = 0.3  # 자동 줌: 필요 시점보다 최대 2배 이 시간만큼 먼저 줌아웃 시작
    motion_zoom: bool = True  # 동작에 따른 줌: 크럭스(한자리 오래)엔 타이트, 큰 동작(다이노·추락)엔 넓게
    crux_zoom: float = 1.12
    crux_window_s: float = 3.0  # 이 시간 동안
    crux_range: float = 0.06  # 몸통이 이만큼(프레임 높이 비율) 이하로만 움직이면 크럭스
    crux_min_s: float = 3.0  # 크럭스가 이만큼 이어질 때만 타이트(짧게 멈출 때마다 줌이 들썩이지 않게)
    move_speed: float = 0.3  # 몸통 속도(프레임 높이/초)가 이보다 빠르면 큰 동작
    move_zoom: float = 0.85
    move_hold_s: float = 0.6  # 큰 동작 판정 유지 시간
    speed_smooth_s: float = 0.3  # 속도 계산 전 몸통 위치 평활
    seed: int = 0


def crop_size(src_w, src_h, p):
    """zoom은 '이 비율로 원본에서 가장 넓게 잡은 크롭' 대비 배율. 확대 상한(max_upscale)이 우선."""
    widest = min(src_h, src_w / p.aspect)
    ch = min(max(widest / p.zoom, p.out_height / p.max_upscale), widest)
    return ch * p.aspect, ch


def solve_path(track, fps, src_w, src_h, p=CameraParams(), body=None):
    """track: (n,2) 등반자 몸통 중심(원본 px, 결측 없음).
    body: (n,4) 몸 전체 박스 x0,y0,x1,y1 (원본 px). 주면 몸이 잘리지 않게 자동 줌아웃.
    반환: (n,5) cx, cy, w, h, rot_deg."""
    track = np.asarray(track, float)
    n = len(track)
    if n < 2 or not np.isfinite(track).all():
        raise ValueError("track은 2프레임 이상이고 결측(nan) 없이 채워져 있어야 합니다")
    cw0, ch0 = crop_size(src_w, src_h, p)
    base = motion_heights(track, fps, src_w, src_h, p, ch0) if p.motion_zoom else np.full(n, ch0)
    ch = _smooth_heights(base, fps, p) if p.motion_zoom else base
    path = _solve(track, fps, src_w, src_h, p, ch, ch0)
    if body is not None:
        ch = _fit_heights(path, np.asarray(body, float), fps, src_w, src_h, p, base)
        path = _solve(track, fps, src_w, src_h, p, ch, ch0)
    return path


def motion_heights(track, fps, src_w, src_h, p, ch0):
    """몸통 움직임으로 프레임별 목표 크롭 높이: 크럭스면 ch0/crux_zoom(타이트), 큰 동작이면 ch0/move_zoom(넓게)."""
    k = max(1, int(round(p.speed_smooth_s * fps)))
    sm = np.stack([uniform_filter1d(track[:, i] / src_h, k, mode="nearest") for i in range(2)], axis=1)
    speed = np.linalg.norm(np.gradient(sm, axis=0), axis=1) * fps
    big = maximum_filter1d(speed, max(1, int(round(p.move_hold_s * fps))), mode="nearest") > p.move_speed
    w = max(1, int(round(p.crux_window_s * fps)))
    spread = np.maximum(
        maximum_filter1d(sm[:, 0], w, mode="nearest") - minimum_filter1d(sm[:, 0], w, mode="nearest"),
        maximum_filter1d(sm[:, 1], w, mode="nearest") - minimum_filter1d(sm[:, 1], w, mode="nearest"),
    )
    crux = _drop_short_runs((spread < p.crux_range) & ~big, int(round(p.crux_min_s * fps)))
    mult = np.where(big, p.move_zoom, np.where(crux, p.crux_zoom, 1.0))
    widest = min(src_h, src_w / p.aspect)
    return np.clip(ch0 / mult, min(p.out_height / p.max_upscale, widest), widest)


def _drop_short_runs(mask, min_len):
    """연속 True 구간 중 min_len보다 짧은 것은 False로."""
    out = mask.copy()
    i, n = 0, len(mask)
    while i < n:
        if not mask[i]:
            i += 1
            continue
        j = i
        while j < n and mask[j]:
            j += 1
        if j - i < min_len:
            out[i:j] = False
        i = j
    return out


def _smooth_heights(target, fps, p):
    """넓어질 땐 미리(최대 필터), 좁아질 땐 늦게, 그리고 부드럽게(이동평균 두 번)."""
    r = max(1, int(round(p.zoom_lead_s * fps)))
    ch = maximum_filter1d(target, size=4 * r + 1, mode="nearest")
    for _ in range(2):
        ch = uniform_filter1d(ch, size=2 * r + 1, mode="nearest")
    return ch


def _fit_heights(path, body, fps, src_w, src_h, p, base):
    """몸 박스를 담는 데 필요한 크롭 높이(최소 base — 동작 줌 목표) → 미리 줌아웃·유지·부드럽게 복귀하는 높이 곡선.
    max 필터 반경(2r)이 이동평균 두 번의 총 반경(2r) 이상이라 결과는 항상 필요값 이상."""
    cx, cy = path[:, 0], path[:, 1]
    m = 1 + 2 * p.fit_margin
    need_h = 2 * np.maximum(cy - body[:, 1], body[:, 3] - cy) * m
    need_w = 2 * np.maximum(cx - body[:, 0], body[:, 2] - cx) * m
    need = np.maximum(np.maximum(need_h, need_w / p.aspect), base)
    need = np.minimum(need, min(src_h, src_w / p.aspect))  # 불가능한 요구는 먼저 자른다(급경사 방지)
    return _smooth_heights(need, fps, p)


def _solve(track, fps, src_w, src_h, p, ch, ch0):
    """크롭 높이 곡선 ch(프레임별)가 주어졌을 때의 카메라 경로. 데드존은 기본 높이 ch0 기준."""
    n = len(track)
    cw = ch * p.aspect
    tx = track[:, 0]
    ty = track[:, 1] - p.headroom * ch
    cx = simulate_follow(tx, fps, p.omega, p.zeta, p.delay_s, deadzone=p.deadzone_x * ch0)
    cy = simulate_follow(ty, fps, p.omega, p.zeta, p.delay_s, deadzone=p.deadzone_y * ch0)

    def noise(amp, hz, channel):
        return amp * ch * smooth_noise(n, fps, hz, seed=p.seed * 16 + channel) if amp > 0 else 0.0

    cx = cx + noise(p.drift_x, p.drift_hz, 0) + noise(p.shake_gain * p.shake_x, p.shake_hz, 2)
    cy = cy + noise(p.drift_y, p.drift_hz, 1) + noise(p.shake_gain * p.shake_y, p.shake_hz, 3)
    rot = np.zeros(n)
    if p.shake_gain * p.shake_roll_deg > 0:
        rot = p.shake_gain * p.shake_roll_deg * smooth_noise(n, fps, p.shake_hz, seed=p.seed * 16 + 4)

    # 회전해도 원본 안에 들어가도록: sinθ ≤ 남는 여유 / 반대 변 (cosθ ≤ 1이므로 보수적)
    room = np.minimum((src_w - cw) / ch, (src_h - ch) / cw)
    max_rot = np.degrees(np.arcsin(np.clip(room, 0.0, 1.0)))
    rot = np.clip(rot, -max_rot, max_rot)

    t = np.radians(np.abs(rot))
    bw = (cw * np.cos(t) + ch * np.sin(t)) / 2
    bh = (cw * np.sin(t) + ch * np.cos(t)) / 2
    cx = np.clip(cx, bw, src_w - bw)
    cy = np.clip(cy, bh, src_h - bh)
    return np.stack([cx, cy, cw, ch, rot], axis=1)
