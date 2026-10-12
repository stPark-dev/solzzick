"""포즈 추정 간격(stride)별 검출 비율 비교 — VIDEO 모드에서 프레임을 건너뛰면 빠른 동작에서 놓치는지 측정.

usage: python tools/stride_coverage.py <video> [strides=1,3]
출력: stride별 '샘플 프레임 중 포즈 검출 비율'과 1초 단위 검출 비율
"""
import json
import sys

import cv2
import mediapipe as mp
import numpy as np
from mediapipe.tasks.python import BaseOptions, vision

from tools.render import MODEL, TRACK_H


def coverage(video, stride, num_poses=1):
    opts = vision.PoseLandmarkerOptions(
        base_options=BaseOptions(model_asset_path=str(MODEL)),
        running_mode=vision.RunningMode.VIDEO,
        num_poses=num_poses,
    )
    cap = cv2.VideoCapture(video)
    fps = cap.get(cv2.CAP_PROP_FPS)
    hits, idx = [], 0
    with vision.PoseLandmarker.create_from_options(opts) as lm:
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            if idx % stride == 0:
                h, w = frame.shape[:2]
                small = cv2.resize(frame, (round(w * TRACK_H / h), TRACK_H))
                img = mp.Image(image_format=mp.ImageFormat.SRGB, data=cv2.cvtColor(small, cv2.COLOR_BGR2RGB))
                hits.append((idx, bool(lm.detect_for_video(img, int(idx * 1000 / fps)).pose_landmarks)))
            idx += 1
    per_sec = {}
    for i, h in hits:
        per_sec.setdefault(int(i / fps), []).append(h)
    return {
        "stride": stride,
        "rate": float(np.mean([h for _, h in hits])),
        "per_sec": [round(float(np.mean(v)), 2) for _, v in sorted(per_sec.items())],
    }


if __name__ == "__main__":
    strides = [int(s) for s in (sys.argv[2] if len(sys.argv) > 2 else "1,3").split(",")]
    print(json.dumps({"video": sys.argv[1].split("/")[-1], "results": [coverage(sys.argv[1], s) for s in strides]}))
