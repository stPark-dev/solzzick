"""추적 점검 결과(TrackAudit) 분석: 수상한 구간을 찾아 보고서와 확인용 그림을 만든다.

    adb pull /sdcard/Android/data/com.zzick.debug/files/audit_base out/audit_base
    python tools/audit_tracks.py report out/audit_base            # out/audit_base/report.md, sheets/
    python tools/audit_tracks.py compare out/audit_base out/audit_new

수상한 구간(좌표는 화면 높이 H 비율):
- MISMATCH  등반자만 한 사람 박스가 있는데 추적 위치가 어느 박스에도 없음, 1초 이상 — 엉뚱한 것을 따라감
- FROZEN    포즈 없이(흐름·보간·유지) 1.5초 이상 거의 안 움직임 — 흐름이 벽에 붙는 등
- JUMP      한 프레임에 0.08H 넘게 튐 — 카메라 순간이동
- SMALL     따라가는 몸보다 2배 넘게 큰 사람 박스가 다른 곳에 2초 이상 계속 보임 — 멀리 있는 작은 사람을 따라감
- WEAK      트리밍 구간에서 포즈로 직접 잡은 샘플이 30% 미만
"""
import json
import sys
from pathlib import Path

POSE, BOX, INTERP, HOLD, FLOW = 0, 1, 2, 3, 4
NAMES = {POSE: "POSE", BOX: "BOX", INTERP: "INTERP", HOLD: "HOLD", FLOW: "FLOW"}
H = 1440.0

MISMATCH_S = 1.0
FROZEN_S = 1.5
FROZEN_SPEED = 0.02  # H/s
JUMP = 0.08  # H/프레임
WEAK = 0.3
BOX_MARGIN = 0.1  # 박스 폭·높이 대비 여유
SIZE_RATIO = (0.5, 2.0)  # 등반자 몸 높이 대비 이 범위의 사람 박스만 MISMATCH 판정에 쓴다
MISMATCH_CARRY_S = 0.6  # 박스 없는 샘플은 직전 판정을 이만큼만 이어 간다(검출기는 0.5초마다는 돈다)
SMALL_RATIO = 2.0  # 따라가는 몸 높이의 이 배 넘는 사람 박스가 있으면
SMALL_S = 2.0


def load(dir_: Path):
    out = {}
    for f in sorted(dir_.glob("*.json")):
        if f.name in ("summary.json",):
            continue
        d = json.loads(f.read_text())
        out[d["name"]] = d
    return out


def runs(flags, min_len):
    """(시작, 끝) 시각 리스트로 — flags: [(t, bool)] 시간순"""
    segs, start, prev = [], None, None
    for t, on in flags:
        if on and start is None:
            start = t
        if not on and start is not None:
            if prev - start >= min_len:
                segs.append((start, prev))
            start = None
        prev = t
    if start is not None and prev - start >= min_len:
        segs.append((start, prev))
    return segs


def inside(x, y, b):
    mx = (b[2] - b[0]) * BOX_MARGIN
    my = (b[3] - b[1]) * BOX_MARGIN
    return b[0] - mx <= x <= b[2] + mx and b[1] - my <= y <= b[3] + my


def analyze(d):
    if "error" in d:
        return {"error": d["error"]}
    pts, src, track = d["pts"], d["src"], d["track"]
    t0 = pts[0]
    ts = [(p - t0) / 1e6 for p in pts]
    ws, we = ((x - t0) / 1e6 for x in d["trim"])
    flags = {"MISMATCH": [], "FROZEN": [], "JUMP": [], "SMALL": []}

    # MISMATCH: 등반자 몸과 비슷한 크기의 사람 박스만 본다(멀리 있는 작은 사람, 카메라 앞을 지나는 큰 사람 제외).
    # 그런 박스가 있는 샘플만 판정하고, 없는 샘플은 직전 판정을 이어간다
    # 등반자 대표 몸 높이: 포즈로 잡은 프레임 몸 높이의 중앙값(지금 추적이 엉뚱한 사람이어도 흔들리지 않게)
    body = d.get("body")
    hs = sorted(b[3] - b[1] for b, s in zip(body or [], src) if s == POSE and b[0] == b[0] and b[3] > b[1])
    bh = hs[len(hs) // 2] if hs else None
    seq, last, last_t = [], False, None
    for s in d["samples"]:
        i, boxes = s[0], s[5]
        if bh:
            boxes = [b for b in boxes if SIZE_RATIO[0] <= (b[3] - b[1]) / bh <= SIZE_RATIO[1]]
        if boxes:
            x, y = track[i]
            last, last_t = not any(inside(x, y, b) for b in boxes), ts[i]
        carried = last_t is not None and ts[i] - last_t <= MISMATCH_CARRY_S
        seq.append((ts[i], last and carried and ws <= ts[i] <= we))
    flags["MISMATCH"] = runs(seq, MISMATCH_S)

    # SMALL: 지금 따라가는 몸보다 훨씬 큰 사람이 계속 보이면 멀리 있는 작은 사람을 따라가는 것(MISMATCH의 크기 기준이
    # 그 작은 사람 것이 되면 못 잡는다 — 점검: 20260821_155813). 박스 없는 샘플은 MISMATCH처럼 짧게만 이어 간다
    seq, last, last_t = [], False, None
    for s in d["samples"]:
        i, boxes = s[0], s[5]
        if boxes and body and body[i][3] > body[i][1]:
            own = body[i][3] - body[i][1]
            x, y = track[i]
            last, last_t = any((b[3] - b[1]) > SMALL_RATIO * own and not inside(x, y, b) for b in boxes), ts[i]
        carried = last_t is not None and ts[i] - last_t <= MISMATCH_CARRY_S
        seq.append((ts[i], last and carried and ws <= ts[i] <= we))
    flags["SMALL"] = runs(seq, SMALL_S)

    # FROZEN: 0.5초 창의 이동 속도
    fps = d.get("fps") or 30.0
    half = max(1, int(fps * 0.25))
    seq = []
    for i in range(len(ts)):
        a, b = max(0, i - half), min(len(ts) - 1, i + half)
        dx = track[b][0] - track[a][0]
        dy = track[b][1] - track[a][1]
        speed = (dx * dx + dy * dy) ** 0.5 / H / max(1e-6, ts[b] - ts[a])
        seq.append((ts[i], src[i] != POSE and speed < FROZEN_SPEED and ws <= ts[i] <= we))
    flags["FROZEN"] = runs(seq, FROZEN_S)

    for i in range(1, len(ts)):
        dx = track[i][0] - track[i - 1][0]
        dy = track[i][1] - track[i - 1][1]
        if (dx * dx + dy * dy) ** 0.5 / H > JUMP and ws <= ts[i] <= we:
            jumps = flags["JUMP"]
            if jumps and abs(jumps[-1][1] - ts[i - 1]) < 1e-6:  # 이어지는 프레임은 한 번으로
                jumps[-1] = (jumps[-1][0], ts[i])
            else:
                jumps.append((ts[i - 1], ts[i]))

    in_win = [s for s in d["samples"] if ws <= ts[s[0]] <= we]
    pose_frac = sum(1 for s in in_win if s[2] is not None) / max(1, len(in_win))
    crop = sum(1 for s in in_win if s[4])
    low_sim = sum(1 for s in in_win if s[3] is not None and s[2] is not None and s[3] < 0.5)
    sec = {k: round(sum(e - s for s, e in v), 2) for k, v in flags.items()}
    return {
        "duration": round(ts[-1], 1),
        "trim": [round(ws, 1), round(we, 1)],
        "pose_frac": round(pose_frac, 3),
        "weak": pose_frac < WEAK,
        "crop_samples": crop,
        "low_sim_accepts": low_sim,
        "analyze_s": round(d.get("analyzeMs", 0) / 1000, 1),
        "segments": {k: [[round(s, 1), round(e, 1)] for s, e in v] for k, v in flags.items()},
        "seconds": sec,
    }


def sheet(dir_: Path, d, segs, out: Path):
    """수상한 구간(앞뒤 1초)의 1초 썸네일에 추적 위치(빨강)·사람 박스(노랑)·소스를 그린 한 장"""
    from PIL import Image, ImageDraw

    base = d["name"].removesuffix(".mp4")
    pts, src, track = d["pts"], d["src"], d["track"]
    t0 = pts[0]
    ts = [(p - t0) / 1e6 for p in pts]
    samples = {s[0]: s for s in d["samples"]}
    secs = sorted({int(t) for s, e in segs for t in range(int(s) - 1, int(e) + 2) if 0 <= t <= ts[-1]})[:30]
    tiles = []
    for sec in secs:
        f = dir_ / base / f"t{sec:03d}.jpg"
        if not f.exists():
            continue
        im = Image.open(f).convert("RGB")
        k = im.height / H
        dr = ImageDraw.Draw(im)
        i = min(range(len(ts)), key=lambda j: abs(ts[j] - sec))
        near = min(samples, key=lambda j: abs(j - i))
        for b in samples[near][5]:
            dr.rectangle([b[0] * k, b[1] * k, b[2] * k, b[3] * k], outline="yellow", width=2)
        x, y = track[i]
        dr.ellipse([x * k - 7, y * k - 7, x * k + 7, y * k + 7], outline="red", width=3)
        dr.rectangle([0, 0, 120, 16], fill="black")
        dr.text((3, 2), f"{sec}s {NAMES[src[i]]}", fill="white")
        tiles.append(im)
    if not tiles:
        return False
    w, h = tiles[0].size
    cols = 6
    rows = (len(tiles) + cols - 1) // cols
    canvas = Image.new("RGB", (w * cols, h * rows), "white")
    for n, im in enumerate(tiles):
        canvas.paste(im, ((n % cols) * w, (n // cols) * h))
    out.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(out, quality=85)
    return True


HOURS = (9, 16)  # 등반 영상 시간대(기본값) — 파일명(YYYYMMDD_HHMMSS) 시각으로 등반 영상만


def in_hours(name: str, hours=HOURS) -> bool:
    part = name.split("_")[1] if "_" in name else ""
    if len(part) < 2 or not part[:2].isdigit():
        return True
    return hours[0] <= int(part[:2]) < hours[1]


MAX_CLIMB_MS = 120_000  # 등반 영상 최대 길이(기본값) — 이보다 긴 영상은 등반 영상이 아닌 것으로 본다


def is_climb(d) -> bool:
    """등반 영상으로 볼지 — 등반 시간대(09~16시)에 찍었고 2분 이하"""
    return in_hours(d["name"]) and d.get("durationMs", 0) <= MAX_CLIMB_MS


def excluded(dir_: Path):
    """exclude.txt(한 줄에 영상 이름, # 뒤는 메모) — 등반 영상이 아닌 것 등 점검에서 뺄 영상"""
    f = dir_ / "exclude.txt"
    if not f.exists():
        return set()
    return {line.split("#")[0].strip() for line in f.read_text().splitlines() if line.split("#")[0].strip()}


def report(dir_: Path):
    skip = excluded(dir_)
    all_ = load(dir_)
    data = {k: v for k, v in all_.items() if k not in skip and is_climb(v)}
    off_hours = len([k for k, v in all_.items() if not is_climb(v)])
    summary = {name: analyze(d) for name, d in data.items()}
    (dir_ / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1))
    lines = ["# 추적 점검", "", f"영상 {len(summary)}개", "",
             "| 영상 | 길이 | 포즈 비율 | 잘라서 찾음 | MISMATCH s | FROZEN s | JUMP | SMALL s | 구간 |", "|---|---|---|---|---|---|---|---|---|"]
    flagged = 0
    for name, r in summary.items():
        if "error" in r:
            lines.append(f"| {name} | 오류: {r['error'][:60]} | | | | | | | |")
            continue
        s = r["seconds"]
        segs = [(k, a, b) for k, v in r["segments"].items() for a, b in v]
        bad = s["MISMATCH"] > 0 or s["FROZEN"] > 0 or s["SMALL"] > 0 or r["segments"]["JUMP"] or r["weak"]
        flagged += bool(bad)
        where = ", ".join(f"{k} {a}–{b}s" for k, a, b in segs[:4]) + (" …" if len(segs) > 4 else "")
        lines.append(f"| {name}{' ⚠' if bad else ''} | {r['duration']}s | {r['pose_frac']:.0%}{' (WEAK)' if r['weak'] else ''} "
                     f"| {r['crop_samples']} | {s['MISMATCH']} | {s['FROZEN']} | {len(r['segments']['JUMP'])} | {s['SMALL']} | {where} |")
        if bad and segs:
            sheet(dir_, data[name], [(a, b) for _, a, b in segs], dir_ / "sheets" / f"{name.removesuffix('.mp4')}.jpg")
    lines[2] = (f"영상 {len(summary)}개, 수상한 영상 {flagged}개" + (f" (제외 {len(skip)}개: exclude.txt)" if skip else "")
                + (f" · 등반 시간대(09~16시) 밖이거나 2분 넘는 영상 {off_hours}개 제외" if off_hours else ""))
    (dir_ / "report.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))


def compare(a: Path, b: Path):
    sa = json.loads((a / "summary.json").read_text())
    sb = json.loads((b / "summary.json").read_text())
    tot = {"MISMATCH": [0, 0], "FROZEN": [0, 0], "JUMP": [0, 0], "SMALL": [0, 0]}
    print("| 영상 | MISMATCH s | FROZEN s | JUMP | SMALL s | 포즈 비율 |\n|---|---|---|---|---|---|")
    for name in sorted(set(sa) & set(sb)):
        ra, rb = sa[name], sb[name]
        if "error" in ra or "error" in rb:
            print(f"| {name} | 오류 {'전' if 'error' in ra else ''}{'후' if 'error' in rb else ''} | | | | |")
            continue
        cells = []
        for k in tot:
            va = len(ra["segments"]["JUMP"]) if k == "JUMP" else ra["seconds"].get(k, 0)  # SMALL 전에 만든 요약엔 없음
            vb = len(rb["segments"]["JUMP"]) if k == "JUMP" else rb["seconds"].get(k, 0)
            tot[k][0] += va
            tot[k][1] += vb
            cells.append(f"{va} → {vb}" if va != vb else f"{va}")
        pf = f"{ra['pose_frac']:.0%} → {rb['pose_frac']:.0%}" if ra["pose_frac"] != rb["pose_frac"] else f"{ra['pose_frac']:.0%}"
        if any("→" in c for c in cells) or "→" in pf:
            print(f"| {name} | " + " | ".join(cells) + f" | {pf} |")
    print("\n합계: " + ", ".join(f"{k} {round(v[0], 1)} → {round(v[1], 1)}" for k, v in tot.items()))


if __name__ == "__main__":
    if sys.argv[1] == "report":
        report(Path(sys.argv[2]))
    elif sys.argv[1] == "compare":
        compare(Path(sys.argv[2]), Path(sys.argv[3]))
