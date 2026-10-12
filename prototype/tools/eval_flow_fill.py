"""포즈 공백 메우기 정확도 비교(가린 구간 검증): 실제 포즈가 있는 구간을 일부러 가리고
① 앞뒤 보간 ② 광학 흐름(+끝 보정)으로 채운 위치를 실제 몸통 위치와 비교한다.

usage: python tools/eval_flow_fill.py <track.npz> <video> [step] → JSON 한 줄(창 길이별·빠른 구간 오차, 원본 px)
"""
import json
import sys

import cv2
import numpy as np

from analysis.framing import torso_center
from tools.render import visible_extent
from tracking.flow import bridge, track_points

SCALE_H = 360  # 흐름 계산 해상도


def main(npz, video, step=1, lk=None):
    d = np.load(npz)
    L = d["landmarks"]
    fps = float(d["fps"])
    W, H = (int(v) for v in d["size"])
    torso = torso_center(L, W / H) * H
    ext = visible_extent(L, W / H) * H
    sw = round(W * SCALE_H / H)
    cap = cv2.VideoCapture(video)
    frames = []
    while True:
        ok, f = cap.read()
        if not ok:
            break
        frames.append(cv2.cvtColor(cv2.resize(f, (sw, SCALE_H), interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2GRAY))
    k = SCALE_H / H
    valid = ~np.isnan(torso[:, 0])
    res = {}
    for length_s in (1.0, 2.0):
        n = int(round(length_s * fps))
        rows = []
        for a in range(0, len(torso) - n - 1, int(fps)):
            b = a + n
            if not valid[a:b + 1].all() or np.isnan(ext[a]).any():
                continue
            truth = torso[a + 1:b]
            t = np.linspace(0, 1, n + 1)[1:-1, None]
            interp = torso[a] + (torso[b] - torso[a]) * t
            box = ext[a] * k
            # step>1: 앱처럼 포즈 추정 프레임에서만 흐름 — 사이 프레임은 이동량을 선형 보간
            idx = list(range(a, b + 1, step))
            if idx[-1] != b:
                idx.append(b)
            ds = track_points([frames[i] for i in idx], box, **(lk or {})) / k
            disp = np.stack([np.interp(np.arange(a, b + 1), idx, ds[:, c]) for c in range(2)], axis=1)
            if np.isnan(disp).any():
                flow = interp  # 흐름을 놓치면 보간과 같게
            else:
                flow = bridge(torso[a], disp, torso[b])[1:-1]
            path = np.linalg.norm(np.diff(torso[a:b + 1], axis=0), axis=1).sum()
            rows.append((np.linalg.norm(interp - truth, axis=1).mean(), np.linalg.norm(flow - truth, axis=1).mean(), path))
        if not rows:
            continue
        r = np.array(rows)
        fast = r[:, 2] > np.percentile(r[:, 2], 75)
        res[f"{length_s:.0f}s"] = {
            "n": len(r),
            "interp_err": float(r[:, 0].mean()), "flow_err": float(r[:, 1].mean()),
            "fast_interp_err": float(r[fast, 0].mean()), "fast_flow_err": float(r[fast, 1].mean()),
            "flow_better_frac": float((r[:, 1] < r[:, 0]).mean()),
        }
    return res


if __name__ == "__main__":
    step = int(sys.argv[3]) if len(sys.argv) > 3 else 1
    lk = json.loads(sys.argv[4]) if len(sys.argv) > 4 else None  # 예: '{"max_points":30,"win":15,"iters":10}'
    print(json.dumps({"video": sys.argv[2].split("/")[-1], "step": step, "lk": lk, **main(sys.argv[1], sys.argv[2], step, lk)}))
