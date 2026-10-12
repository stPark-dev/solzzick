"""extract_motion.py 결과(npz)로 핸드헬드 카메라 특성을 샷별로 측정하고 요약.

usage: python tools/analyze_reference.py out/motion/*.npz > out/reference_stats.json
"""
import json
import sys
from pathlib import Path

import numpy as np

from analysis.framing import body_extent, interp_nans, still_offsets, to_world, torso_center
from analysis.signals import (
    accumulate_similarity,
    estimate_delay,
    fit_follow,
    shake_stats,
    simulate_follow,
    split_shake,
    split_shots,
)

HIST_CUT = 0.35  # 색 히스토그램 급변 → 컷
INLIER_CUT = 0.3  # 배경 추적 실패 → 컷/전환
MIN_SHOT_S = 3.0
MIN_POSE = 0.7
SHAKE_CUTOFF_HZ = 1.5
STILL_SPEED = 0.05  # 높이/초 미만이면 카메라 정지로 간주
MIN_TRAVEL = 0.15  # 등반자가 이만큼(높이 단위) 움직인 축만 추종 모델 피팅
MIN_FOLLOW_S = 8.0  # 추종 동특성은 충분히 긴 샷에서만 피팅


def shots(d):
    ir = np.r_[1.0, d["inlier_ratio"]]
    score = np.maximum(d["cut_score"], (ir < INLIER_CUT).astype(float))
    return split_shots(score, threshold=HIST_CUT, min_len=int(MIN_SHOT_S * float(d["fps"])))


def analyze_shot(d, a, b):
    fps = float(d["fps"])
    w, h = d["size"]
    aspect = float(w / h)
    center = (aspect / 2, 0.5)
    lm = d["landmarks"][a:b]
    coverage = float((~np.isnan(lm[:, 0, 0])).mean())
    if coverage < MIN_POSE:
        return None
    torso = np.stack([interp_nans(c) for c in torso_center(lm, aspect).T], axis=1)
    ext = np.stack([interp_nans(c) for c in body_extent(lm).T], axis=1)
    cam = accumulate_similarity(list(d["steps"][a : b - 1]), center)
    world = to_world(torso, cam, center)

    low_x, hi_x = split_shake(cam[:, 0], fps, SHAKE_CUTOFF_HZ)
    low_y, hi_y = split_shake(cam[:, 1], fps, SHAKE_CUTOFF_HZ)
    _, hi_r = split_shake(cam[:, 3], fps, SHAKE_CUTOFF_HZ)
    tgt_x, _ = split_shake(world[:, 0], fps, 3.0)  # 포즈 지터 제거
    tgt_y, _ = split_shake(world[:, 1], fps, 3.0)

    follow = {}
    for axis, tgt, c in [("x", tgt_x, cam[:, 0]), ("y", tgt_y, cam[:, 1])]:
        if np.ptp(tgt) >= MIN_TRAVEL and (b - a) / fps >= MIN_FOLLOW_S:
            f = fit_follow(tgt, c, fps)
            f["xcorr_delay_s"] = estimate_delay(tgt, c, fps, max_lag_s=1.5)
            f["travel"] = float(np.ptp(tgt))
            # 모델이 설명 못 하는 잔차 = 조작자의 불완전함(손 떨림 + 미세 드리프트)
            model = simulate_follow(tgt, fps, f["omega"], f["zeta"], f["delay_s"], f["deadzone"]) + f["offset"]
            f["residual"] = shake_stats(c - model, fps)
            follow[axis] = f

    offset = torso - np.asarray(center)
    still = still_offsets(np.stack([low_x, low_y], 1), offset, fps, STILL_SPEED)
    speed = np.linalg.norm(np.gradient(np.stack([tgt_x, tgt_y], 1), axis=0), axis=1) * fps
    body_h = ext[:, 2]
    ok = ~np.isnan(body_h)
    return {
        "frames": [int(a), int(b)],
        "duration_s": (b - a) / fps,
        "pose_coverage": coverage,
        "framing": {
            "torso_x_offset_med": float(np.median(offset[:, 0])),
            "torso_y_med": float(np.median(torso[:, 1])),
            "head_top_med": float(np.nanmedian(ext[:, 0])),
            "body_height_med": float(np.nanmedian(body_h)),
            "body_height_p10_p90": [float(np.nanpercentile(body_h, 10)), float(np.nanpercentile(body_h, 90))],
        },
        "deadzone": {
            "still_fraction": len(still) / len(offset),
            "still_abs_offset_p75": np.percentile(np.abs(still), 75, axis=0).tolist() if len(still) else None,
        },
        "follow": follow,
        "shake": {"x": shake_stats(hi_x, fps), "y": shake_stats(hi_y, fps), "rot_deg": shake_stats(hi_r, fps)},
        "zoom": {
            "cam_scale_range": float(np.ptp(cam[:, 2])),
            "corr_bodyheight_vs_speed": float(np.corrcoef(body_h[ok], speed[ok])[0, 1]) if ok.sum() > 10 else None,
        },
    }


def summarize(all_shots):
    dur = np.array([s["duration_s"] for s in all_shots])

    def wmed(vals, weights):
        vals, weights = np.asarray(vals, float), np.asarray(weights, float)
        o = np.argsort(vals)
        cw = np.cumsum(weights[o]) / weights.sum()
        return float(vals[o][np.searchsorted(cw, 0.5)])

    def stat(get, weights=dur):
        pairs = [(get(s), w) for s, w in zip(all_shots, weights) if get(s) is not None]
        v = [p[0] for p in pairs]
        return {"wmedian": wmed(v, [p[1] for p in pairs]), "p25": float(np.percentile(v, 25)),
                "p75": float(np.percentile(v, 75)), "n": len(v)}

    out = {"shots": len(all_shots), "total_s": float(dur.sum())}
    for k in ["torso_x_offset_med", "torso_y_med", "head_top_med", "body_height_med"]:
        out[k] = stat(lambda s, k=k: s["framing"][k])
    out["still_fraction"] = stat(lambda s: s["deadzone"]["still_fraction"])
    for i, ax in enumerate("xy"):
        out[f"deadzone_{ax}_p75"] = stat(lambda s, i=i: s["deadzone"]["still_abs_offset_p75"][i] if s["deadzone"]["still_abs_offset_p75"] else None)
    for ax in "xy":
        for k in ["omega", "zeta", "delay_s", "deadzone", "offset", "xcorr_delay_s", "rmse"]:
            out[f"follow_{ax}_{k}"] = stat(lambda s, ax=ax, k=k: s["follow"][ax][k] if ax in s["follow"] else None)
        out[f"follow_{ax}_residual_rms"] = stat(lambda s, ax=ax: s["follow"][ax]["residual"]["rms"] if ax in s["follow"] else None)
    for ax in ["x", "y", "rot_deg"]:
        for k in ["rms", "dominant_hz"]:
            out[f"shake_{ax}_{k}"] = stat(lambda s, ax=ax, k=k: s["shake"][ax][k])
    out["zoom_cam_scale_range"] = stat(lambda s: s["zoom"]["cam_scale_range"])
    out["zoom_corr_bodyheight_vs_speed"] = stat(lambda s: s["zoom"]["corr_bodyheight_vs_speed"])
    return out


if __name__ == "__main__":
    per_video, all_shots = {}, []
    for path in sys.argv[1:]:
        d = np.load(path)
        res = [r for a, b in shots(d) if (r := analyze_shot(d, a, b))]
        per_video[Path(path).stem] = res
        all_shots += res
    print(json.dumps({"summary": summarize(all_shots), "per_video": per_video}, indent=1))
