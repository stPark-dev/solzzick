"""Kotlin 포팅 검증용 기준 데이터(golden) 내보내기.

render.py 캐시(npz)의 원시 입력(포즈 몸통 중심, 사람 박스, 관절 박스)으로 Python 구현을 돌리고
입력·출력을 JSON으로 저장한다. Kotlin 테스트가 같은 입력으로 같은 출력을 내는지 비교한다.

usage: python tools/export_golden.py <track.npz> <out.json> [video — 주면 광학 흐름 공백 메우기도 포함]
"""
import json
import sys
from dataclasses import asdict, replace

import numpy as np

from analysis.framing import torso_center
from tools.render import visible_extent
from tracking.fuse import body_boxes, fuse_track
from vcam.camera import CameraParams, solve_path

CAMERA_CASES = {
    "default": {},
    "shake5_seed3": {"shake_gain": 5.0, "seed": 3},
    "zoom15_nodrift": {"zoom": 1.5, "drift_x": 0.0, "drift_y": 0.0},
    "wide169": {"aspect": 16 / 9, "out_height": 1080},
    "portrait45_shake3": {"aspect": 4 / 5, "out_height": 1350, "shake_gain": 3.0},
    "no_motion_zoom": {"motion_zoom": False},
    "fps60_style_even_windows": {"crux_window_s": 2.0, "speed_smooth_s": 0.2, "move_hold_s": 0.4},
    "lead_calm": {},  # 0.4.x: 미리 겨누기 + 빠른 이동 중 드리프트 줄이기(기본값)
    "lead_calm_strict": {"lead_s": 0.5, "drift_calm_speed": 0.1, "seed": 2},
}


def nan_to_none(a):
    return [[None if np.isnan(v) else float(v) for v in row] for row in np.asarray(a, float)]


def gray_frames(video, height=360):
    import cv2
    cap = cv2.VideoCapture(video)
    out = []
    while True:
        ok, f = cap.read()
        if not ok:
            break
        h, w = f.shape[:2]
        out.append(cv2.cvtColor(cv2.resize(f, (round(w * height / h), height), interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2GRAY))
    return out


def export(npz_path, video=None):
    d = np.load(npz_path)
    fps = float(d["fps"])
    w, h = (int(v) for v in d["size"])
    lms = d["landmarks"]
    pose = torso_center(lms, aspect=w / h)
    boxes = [[] for _ in range(len(lms))]
    for i, *b in d["boxes"]:
        boxes[int(i)].append(tuple(float(v) for v in b))
    raw_body = visible_extent(lms, w / h)

    flow = seed = None
    if video:
        from tracking.flow import gap_flows
        flow, seed = gap_flows(gray_frames(video), pose, raw_body)
    track, source = fuse_track(pose, boxes, fps=fps, flow=flow, flow_seed=seed)
    body = body_boxes(raw_body, track, source)
    cases = {}
    for name, overrides in CAMERA_CASES.items():
        p = replace(CameraParams(), **overrides)
        cases[name] = {"params": asdict(p), "path": solve_path(track * h, fps, w, h, p, body=body * h).tolist()}
    p = CameraParams()
    cases["default_no_body"] = {"params": asdict(p), "path": solve_path(track * h, fps, w, h, p).tolist()}
    return {
        "fps": fps,
        "src_w": w,
        "src_h": h,
        "pose": nan_to_none(pose),
        "boxes": [[list(b) for b in bs] for bs in boxes],
        "raw_body": nan_to_none(raw_body),
        "flow": nan_to_none(flow) if flow is not None else None,
        "flow_seed": seed.tolist() if seed is not None else None,
        "fused_track": track.tolist(),
        "fused_source": source.tolist(),
        "body": body.tolist(),
        "cases": cases,
    }


if __name__ == "__main__":
    with open(sys.argv[2], "w") as f:
        json.dump(export(sys.argv[1], sys.argv[3] if len(sys.argv) > 3 else None), f)
