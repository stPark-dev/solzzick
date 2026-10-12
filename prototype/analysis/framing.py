"""포즈 랜드마크 → 프레이밍 지표 변환. 좌표는 프레임 높이 = 1 정규화."""
import warnings

import numpy as np

TORSO = [11, 12, 23, 24]  # 어깨 L/R, 골반 L/R


def torso_center(landmarks, aspect):
    """(n,33,4) MediaPipe 정규화 랜드마크 → (n,2) 몸통 중심. x는 폭/높이 비율을 곱해 높이 단위로."""
    t = landmarks[:, TORSO, :2]
    c = t.mean(axis=1)
    return np.stack([c[:, 0] * aspect, c[:, 1]], axis=1)


def body_extent(landmarks, vis_thr=0.5):
    """(n,3): 보이는 관절 기준 몸 위끝 y, 아래끝 y, 높이. 보이는 관절이 없으면 nan."""
    y = np.where(landmarks[:, :, 3] >= vis_thr, landmarks[:, :, 1], np.nan)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)  # 전부 nan인 프레임
        top, bottom = np.nanmin(y, axis=1), np.nanmax(y, axis=1)
    return np.stack([top, bottom, bottom - top], axis=1)


def interp_nans(x):
    x = np.asarray(x, float)
    ok = ~np.isnan(x)
    if not ok.any():
        return x
    i = np.arange(len(x))
    return np.interp(i, i[ok], x[ok])


def to_world(points, cam, center):
    """프레임 좌표 → 월드(샷 첫 프레임) 좌표. 회전은 무시(수 도 이내라 근사).
    cam: accumulate_similarity 출력 (x, y, scale, rot)."""
    return cam[:, :2] + (points - np.asarray(center)) / cam[:, 2:3]


def still_offsets(cam_xy, offset, fps, speed_thr):
    """카메라가 멈춰 있는(속도 < speed_thr 높이/초) 프레임에서 등반자의 화면 중심 대비 오프셋."""
    speed = np.linalg.norm(np.gradient(cam_xy, axis=0), axis=1) * fps
    return offset[speed < speed_thr]
