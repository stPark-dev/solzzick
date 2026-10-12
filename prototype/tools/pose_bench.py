"""Phase 0: MediaPipe Pose Landmarker 모델(lite/full/heavy) 비교 벤치마크.

영상마다 sample_fps로 샘플링해 VIDEO 모드로 추론하고,
검출률·가시성·몸통 중심 지터·추론 시간을 JSON 한 줄로 출력한다.

usage: python tools/pose_bench.py <video> <model: lite|full|heavy> [sample_fps] [num_poses]
"""
import json
import sys
import time
from pathlib import Path

import cv2
import mediapipe as mp
import numpy as np
from mediapipe.tasks.python import BaseOptions, vision

MODELS = Path(__file__).resolve().parent.parent / "models"
TORSO = [11, 12, 23, 24]  # 어깨 L/R, 골반 L/R


def run(video: str, model: str, sample_fps: float = 10.0, num_poses: int = 2) -> dict:
    opts = vision.PoseLandmarkerOptions(
        base_options=BaseOptions(model_asset_path=str(MODELS / f"pose_landmarker_{model}.task")),
        running_mode=vision.RunningMode.VIDEO,
        num_poses=num_poses,
    )
    cap = cv2.VideoCapture(video)
    fps = cap.get(cv2.CAP_PROP_FPS)
    step = max(1, round(fps / sample_fps))
    centers, vis, infer_ms, n_people = [], [], [], []
    idx = 0
    with vision.PoseLandmarker.create_from_options(opts) as lm:
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            idx += 1
            if (idx - 1) % step:
                continue
            img = mp.Image(image_format=mp.ImageFormat.SRGB, data=cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
            t0 = time.perf_counter()
            res = lm.detect_for_video(img, int((idx - 1) * 1000 / fps))
            infer_ms.append((time.perf_counter() - t0) * 1000)
            n_people.append(len(res.pose_landmarks))
            if res.pose_landmarks:
                p = res.pose_landmarks[0]
                centers.append([np.mean([p[k].x for k in TORSO]), np.mean([p[k].y for k in TORSO])])
                vis.append(np.mean([q.visibility for q in p]))
            else:
                centers.append([np.nan, np.nan])
    c = np.array(centers)
    valid = ~np.isnan(c[:, 0])
    # 지터: 2차 차분(가속도)의 중앙값 — 실제 움직임보다 추정 노이즈에 민감
    acc = np.linalg.norm(np.diff(c, n=2, axis=0), axis=1)
    return {
        "video": Path(video).name,
        "model": model,
        "num_poses": num_poses,
        "frames": len(c),
        "detect_rate": round(float(valid.mean()), 3),
        "multi_rate": round(float(np.mean(np.array(n_people) > 1)), 3),
        "mean_visibility": round(float(np.mean(vis)) if vis else 0.0, 3),
        "jitter_px1080": round(float(np.nanmedian(acc) * 1080), 2) if np.isfinite(acc).any() else None,
        "infer_ms": round(float(np.median(infer_ms)), 1),
    }


if __name__ == "__main__":
    fps = float(sys.argv[3]) if len(sys.argv) > 3 else 10.0
    num_poses = int(sys.argv[4]) if len(sys.argv) > 4 else 2
    print(json.dumps(run(sys.argv[1], sys.argv[2], fps, num_poses)), flush=True)
