"""레퍼런스 영상에서 프레임별 카메라 변환·샷 경계 점수·등반자 포즈를 추출해 npz로 저장.

좌표는 프레임 높이 = 1 정규화. 등반자 영역은 특징점에서 제외해 배경 기준 카메라 움직임만 잰다.

usage: python tools/extract_motion.py <video> <out.npz>
"""
import sys
from pathlib import Path

import cv2
import mediapipe as mp
import numpy as np
from mediapipe.tasks.python import BaseOptions, vision

MODEL = Path(__file__).resolve().parent.parent / "models" / "pose_landmarker_full.task"
PROC_H = 640
IDENTITY = np.array([[1.0, 0, 0], [0, 1.0, 0]])


def climber_mask(shape, lms, pad=0.3):
    mask = np.full(shape, 255, np.uint8)
    if lms is None:
        return mask
    h, w = shape
    xs, ys = lms[:, 0] * w, lms[:, 1] * h
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    px, py = (x1 - x0) * pad, (y1 - y0) * pad
    cv2.rectangle(mask, (int(x0 - px), int(y0 - py)), (int(x1 + px), int(y1 + py)), 0, -1)
    return mask


def hist(frame):
    hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
    h = cv2.calcHist([hsv], [0, 1], None, [32, 32], [0, 180, 0, 256])
    return cv2.normalize(h, h).flatten()


def extract(video):
    cap = cv2.VideoCapture(video)
    fps = cap.get(cv2.CAP_PROP_FPS)
    opts = vision.PoseLandmarkerOptions(
        base_options=BaseOptions(model_asset_path=str(MODEL)),
        running_mode=vision.RunningMode.VIDEO,
        num_poses=1,
    )
    steps, inlier_ratio, cut_score, landmarks = [], [], [], []
    prev_gray = prev_hist = prev_mask = None
    idx = 0
    with vision.PoseLandmarker.create_from_options(opts) as lm:
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            h0, w0 = frame.shape[:2]
            frame = cv2.resize(frame, (round(w0 * PROC_H / h0), PROC_H))
            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
            res = lm.detect_for_video(
                mp.Image(image_format=mp.ImageFormat.SRGB, data=cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)),
                int(idx * 1000 / fps),
            )
            lms = None
            if res.pose_landmarks:
                lms = np.array([[p.x, p.y, p.z, p.visibility] for p in res.pose_landmarks[0]])
            landmarks.append(lms if lms is not None else np.full((33, 4), np.nan))
            fh = hist(frame)
            mask = climber_mask(gray.shape, lms)
            if prev_gray is not None:
                cut_score.append(cv2.compareHist(prev_hist, fh, cv2.HISTCMP_BHATTACHARYYA))
                m, ratio = estimate_step(prev_gray, gray, prev_mask & mask)
                steps.append(m)
                inlier_ratio.append(ratio)
            else:
                cut_score.append(0.0)
            prev_gray, prev_hist, prev_mask = gray, fh, mask
            idx += 1
    return {
        "fps": fps,
        "size": np.array([PROC_H * w0 / h0, PROC_H]),
        "steps": np.array(steps),
        "inlier_ratio": np.array(inlier_ratio),
        "cut_score": np.array(cut_score),
        "landmarks": np.array(landmarks),
    }


def estimate_step(prev, cur, mask):
    """prev → cur 유사변환을 정규화 좌표(÷프레임 높이)로 반환."""
    p0 = cv2.goodFeaturesToTrack(prev, 500, 0.01, 8, mask=mask)
    if p0 is None or len(p0) < 20:
        return IDENTITY.copy(), 0.0
    p1, st, _ = cv2.calcOpticalFlowPyrLK(prev, cur, p0, None)
    good = st.ravel() == 1
    if good.sum() < 20:
        return IDENTITY.copy(), 0.0
    a, b = p0[good] / PROC_H, p1[good] / PROC_H
    m, inl = cv2.estimateAffinePartial2D(a, b, method=cv2.RANSAC, ransacReprojThreshold=2.0 / PROC_H)
    if m is None:
        return IDENTITY.copy(), 0.0
    return m, float(inl.mean())


if __name__ == "__main__":
    np.savez_compressed(sys.argv[2], **extract(sys.argv[1]))
