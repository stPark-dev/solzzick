"""고정 촬영 영상 → 가상 카메라 9:16 영상 (데스크톱 프로토타입 파이프라인).

Pass 1 추적(저해상도, 결과 캐시) → Pass 2 solve_path → Pass 3 원본 해상도 스트리밍 크롭 + ffmpeg 인코딩(오디오 복사).
--debug 이면 원본(크롭 사각형 표시)과 결과를 나란히 붙인 영상을 만든다.

usage: python tools/render.py <video> <out.mp4> [--debug] [--param key=value ...]
"""
import argparse
import subprocess
from dataclasses import fields, replace
from pathlib import Path

import cv2
import mediapipe as mp
import numpy as np
from mediapipe.tasks.python import BaseOptions, vision

from analysis.framing import torso_center
from tracking.fuse import body_boxes, fuse_track
from vcam.camera import CameraParams, solve_path

MODELS = Path(__file__).resolve().parent.parent / "models"
MODEL = MODELS / "pose_landmarker_full.task"
DETECTOR = MODELS / "efficientdet_lite2.tflite"
TRACK_H = 640
CALIB_EVERY = 15  # 포즈가 있어도 이 간격으로 검출기를 돌려 박스↔몸통 오프셋을 보정


def track_climber(video, cache):
    """프레임별 몸통 중심(원본 px), 몸 전체 박스(원본 px), 출처(POSE/BOX/INTERP/HOLD).
    포즈가 없는 프레임만 사람 검출기를 돌려 fuse_track으로 메운다. cache(npz)가 있으면 재사용."""
    key = f"v2:{Path(video).resolve()}:{Path(video).stat().st_mtime_ns}"  # v2: 원시 박스 포함
    if cache.exists():
        d = np.load(cache)
        if "key" in d and str(d["key"]) == key:
            return d["track"], d["body"], d["source"], float(d["fps"]), tuple(d["size"])
    cap = cv2.VideoCapture(video)
    fps = cap.get(cv2.CAP_PROP_FPS)
    pose_opts = vision.PoseLandmarkerOptions(
        base_options=BaseOptions(model_asset_path=str(MODEL)),
        running_mode=vision.RunningMode.VIDEO,
        num_poses=1,
    )
    det_opts = vision.ObjectDetectorOptions(
        base_options=BaseOptions(model_asset_path=str(DETECTOR)),
        score_threshold=0.2,
        category_allowlist=["person"],
        max_results=5,
    )
    lms, boxes, idx, size = [], [], 0, None
    with vision.PoseLandmarker.create_from_options(pose_opts) as lm, vision.ObjectDetector.create_from_options(det_opts) as od:
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            h, w = frame.shape[:2]
            size = (w, h)
            sw = round(w * TRACK_H / h)
            img = mp.Image(
                image_format=mp.ImageFormat.SRGB,
                data=cv2.cvtColor(cv2.resize(frame, (sw, TRACK_H)), cv2.COLOR_BGR2RGB),
            )
            res = lm.detect_for_video(img, int(idx * 1000 / fps))
            frame_boxes = []
            if res.pose_landmarks:
                lms.append([[p.x, p.y, p.z, p.visibility] for p in res.pose_landmarks[0]])
            else:
                lms.append(np.full((33, 4), np.nan))
            if not res.pose_landmarks or idx % CALIB_EVERY == 0:
                for d in od.detect(img).detections:
                    b = d.bounding_box  # 처리 해상도 px → 높이 정규화
                    frame_boxes.append(
                        (b.origin_x / TRACK_H, b.origin_y / TRACK_H,
                         (b.origin_x + b.width) / TRACK_H, (b.origin_y + b.height) / TRACK_H)
                    )
            boxes.append(frame_boxes)
            idx += 1
    w, h = size
    lms = np.array(lms)
    pose = torso_center(lms, aspect=w / h)
    track, source = fuse_track(pose, boxes, fps=fps)
    if np.isnan(track).all():
        raise ValueError(f"영상에서 등반자를 찾지 못했습니다: {video}")
    body = body_boxes(visible_extent(lms, w / h), track, source)
    track, body = track * h, body * h  # 높이 정규화 → 원본 px
    box_rows = np.array([(i, *b) for i, bs in enumerate(boxes) for b in bs]).reshape(-1, 5)  # frame, x0,y0,x1,y1
    np.savez_compressed(
        cache, key=key, track=track, body=body, source=source, landmarks=lms, boxes=box_rows,
        fps=fps, size=np.array(size),
    )
    return track, body, source, fps, size


def visible_extent(lms, aspect, vis_thr=0.5):
    """(n,33,4) 랜드마크 → (n,4) 보이는 관절의 박스(높이 정규화). 보이는 관절이 없으면 nan."""
    v = lms[:, :, 3] >= vis_thr
    x = np.where(v, lms[:, :, 0] * aspect, np.nan)
    y = np.where(v, lms[:, :, 1], np.nan)
    out = np.full((len(lms), 4), np.nan)
    ok = v.any(axis=1)
    out[ok] = np.stack([np.nanmin(x[ok], 1), np.nanmin(y[ok], 1), np.nanmax(x[ok], 1), np.nanmax(y[ok], 1)], axis=1)
    return out


def crop_matrix(cx, cy, cw, ch, rot_deg, out_w, out_h):
    """원본 → 출력 아핀 행렬: 크롭 중심을 출력 중심으로, 크롭을 출력 크기로, 회전 상쇄."""
    s = out_h / ch
    M = cv2.getRotationMatrix2D((cx, cy), rot_deg, s)
    M[0, 2] += out_w / 2 - cx
    M[1, 2] += out_h / 2 - cy
    return M


def draw_crop(frame, cx, cy, cw, ch, rot_deg):
    box = cv2.boxPoints(((cx, cy), (cw, ch), rot_deg)).astype(np.int32)
    cv2.polylines(frame, [box], True, (0, 255, 255), 4)
    return frame


def render(video, out, params, debug=False):
    cache = Path(out).with_suffix(".track.npz")
    track, body, _, fps, (w, h) = track_climber(video, cache)
    path = solve_path(track, fps, w, h, params, body=body)
    out_h = params.out_height
    out_w = round(out_h * params.aspect / 2) * 2
    vw, vh = (out_w + round(w * out_h / h / 2) * 2, out_h) if debug else (out_w, out_h)
    ff = subprocess.Popen(
        ["ffmpeg", "-v", "error", "-y",
         "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", f"{vw}x{vh}", "-r", str(fps), "-i", "-",
         "-i", video, "-map", "0:v", "-map", "1:a?", "-c:v", "libx264", "-crf", "18", "-preset", "medium",
         "-pix_fmt", "yuv420p", "-c:a", "copy", "-shortest", out],
        stdin=subprocess.PIPE,
    )
    cap = cv2.VideoCapture(video)
    for cx, cy, cw, ch, rot in path:
        ok, frame = cap.read()
        if not ok:
            break
        M = crop_matrix(cx, cy, cw, ch, rot, out_w, out_h)
        img = cv2.warpAffine(frame, M, (out_w, out_h), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
        if debug:
            src = cv2.resize(draw_crop(frame, cx, cy, cw, ch, rot), (vw - out_w, out_h))
            img = np.hstack([src, img])
        ff.stdin.write(img.tobytes())
    ff.stdin.close()
    ff.wait()
    return path


def parse_params(pairs):
    types = {f.name: f.type for f in fields(CameraParams)}
    kw = {}
    for pair in pairs:
        k, v = pair.split("=", 1)
        kw[k] = int(v) if types[k] in (int, "int") else float(v)
    return replace(CameraParams(), **kw)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("video")
    ap.add_argument("out")
    ap.add_argument("--debug", action="store_true")
    ap.add_argument("--param", nargs="*", default=[])
    a = ap.parse_args()
    render(a.video, a.out, parse_params(a.param), a.debug)
