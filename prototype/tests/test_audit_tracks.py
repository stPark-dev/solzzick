"""추적 점검 분석(tools/audit_tracks.py)의 판정 규칙."""
import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location("audit_tracks", Path(__file__).parent.parent / "tools" / "audit_tracks.py")
at = importlib.util.module_from_spec(spec)
spec.loader.exec_module(at)

FPS = 30.0


def video(track, src, boxes_at=lambda i: [], trim=None, body_h=400.0):
    n = len(track)
    pts = [int(i * 1e6 / FPS) for i in range(n)]
    body = [[x - 100, y - body_h / 2, x + 100, y + body_h / 2] for x, y in track]
    samples = []
    for i in range(0, n, 3):
        chosen = list(track[i]) if src[i] == at.POSE else None
        samples.append([i, 1 if chosen else 0, chosen, 0.9 if chosen else None, False, boxes_at(i)])
    return {"name": "v.mp4", "pts": pts, "src": src, "track": track, "body": body, "fps": FPS,
            "trim": trim or [pts[0], pts[-1]], "samples": samples, "analyzeMs": 1000}


def test_runs_merges_consecutive_flags_and_drops_short_ones():
    flags = [(t / 10, 3 <= t <= 20 or t == 25) for t in range(30)]
    assert at.runs(flags, 1.0) == [(0.3, 2.0)]


def test_clean_climb_has_no_flags():
    n = 300
    track = [[400 + i * 2.0, 900 - i * 1.5] for i in range(n)]
    box = lambda i: [[track[i][0] - 100, track[i][1] - 200, track[i][0] + 100, track[i][1] + 200]]
    r = at.analyze(video(track, [at.POSE] * n, box))
    assert r["seconds"] == {"MISMATCH": 0, "FROZEN": 0, "JUMP": 0}
    assert not r["weak"]


def test_flow_stuck_on_wall_is_frozen():
    # 3~6초: 포즈 없이 흐름만, 위치 그대로(벽에 붙음)
    n = 300
    track = [[400 + min(i, 90) * 2.0, 900.0] for i in range(n)]
    src = [at.FLOW if 90 <= i < 180 else at.POSE for i in range(n)]
    r = at.analyze(video(track, src))
    assert r["segments"]["FROZEN"] and 2.5 <= r["seconds"]["FROZEN"] <= 3.2


def test_resting_climber_with_pose_is_not_frozen():
    n = 300
    r = at.analyze(video([[500.0, 800.0]] * n, [at.POSE] * n))
    assert r["seconds"]["FROZEN"] == 0


def test_following_hold_while_detector_sees_climber_elsewhere_is_mismatch():
    # 4~8초: 추적은 홀드(370) 위, 사람 박스는 등반자(770) 위
    n = 300
    track = [[370.0, 660.0] if 120 <= i < 240 else [770.0, 770.0] for i in range(n)]
    box = lambda i: [[690, 600, 850, 950]]
    r = at.analyze(video(track, [at.POSE] * n, box))
    assert r["segments"]["MISMATCH"] == [[4.0, 7.9]]


def test_teleport_is_jump_but_fast_dyno_is_not():
    n = 120
    track = [[400.0 + (200 if i >= 60 else 0), 800.0] for i in range(n)]  # 한 프레임에 200px(0.14H)
    assert len(at.analyze(video(track, [at.POSE] * n))["segments"]["JUMP"]) == 1
    dyno = [[400.0 + 90 * min(max(i - 50, 0), 10), 800.0] for i in range(n)]  # 프레임당 90px(0.06H)
    assert at.analyze(video(dyno, [at.POSE] * n))["segments"]["JUMP"] == []


def test_flags_only_count_inside_trim_window():
    n = 300
    track = [[400.0, 900.0]] * n
    src = [at.HOLD] * 100 + [at.POSE] * 200
    pts_trim = [int(100 * 1e6 / FPS), int(299 * 1e6 / FPS)]
    r = at.analyze(video(track, src, trim=pts_trim))
    assert r["seconds"]["FROZEN"] == 0


def test_consecutive_jump_frames_count_as_one_event():
    # 빠른 다이노가 여러 프레임 이어지는 순간이동은 한 번으로
    n = 120
    track = [[400.0 + (130 * min(max(i - 60, 0), 4)), 800.0] for i in range(n)]  # 4프레임 연속 130px
    r = at.analyze(video(track, [at.POSE] * n))
    assert len(r["segments"]["JUMP"]) == 1


def test_excluded_videos_are_listed_but_not_counted(tmp_path):
    (tmp_path / "exclude.txt").write_text("v.mp4  # 등반 영상 아님\n")
    assert at.excluded(tmp_path) == {"v.mp4"}


def test_videos_outside_climbing_hours_are_skipped():
    # 등반 영상 시간대(기본 09~16시) — 파일명 시각으로 거른다
    assert at.in_hours("20260914_145634.mp4")
    assert at.in_hours("20260914_090000.mp4")
    assert not at.in_hours("20260409_001725.mp4")
    assert not at.in_hours("20260905_080224.mp4")
    assert not at.in_hours("20260904_224100.mp4")
    assert not at.in_hours("20260914_160000.mp4")
    assert at.in_hours("SNOW_123.mp4")  # 시각을 모르면 남긴다


def test_small_background_people_do_not_make_mismatch():
    # 실측(20260927_122338~2): 등반자는 검출기가 못 찾고, 멀리 있는 작은 사람들만 박스가 잡힘 — 추적은 맞다
    n = 300
    track = [[400.0, 700.0]] * n
    far_people = lambda i: [[1300, 900, 1340, 1000]]  # 높이 100px(등반자 몸 400px의 1/4)
    r = at.analyze(video(track, [at.POSE] * n, far_people))
    assert r["seconds"]["MISMATCH"] == 0


def test_climber_sized_box_elsewhere_is_mismatch():
    n = 300
    track = [[400.0, 700.0]] * n
    other = lambda i: [[1100, 500, 1300, 950]]  # 등반자와 비슷한 크기(450px)
    assert at.analyze(video(track, [at.POSE] * n, other))["seconds"]["MISMATCH"] > 9


def test_videos_longer_than_two_minutes_are_not_climbs():
    # 등반 영상 최대 길이(기본 2분)
    assert at.is_climb({"name": "20260914_145634.mp4", "durationMs": 47_648})
    assert not at.is_climb({"name": "20260905_120224.mp4", "durationMs": 407_229})
    assert not at.is_climb({"name": "20260409_001725.mp4", "durationMs": 56_000})  # 시간대 밖


def test_following_small_seated_person_while_climber_box_elsewhere_is_mismatch():
    # 실측(20260821_155813): 추적이 매트에 앉은 작은 사람(몸 60px)에 붙고, 등반자 박스(480px)는 다른 곳 —
    # 지금 추적 중인 몸 크기가 아니라 영상 전체의 등반자 대표 크기로 박스를 고른다
    n = 300
    track = [[750.0, 700.0] if i < 150 else [960.0, 1100.0] for i in range(n)]
    d = video(track, [at.POSE] * n, lambda i: [[620, 470, 880, 950]] if i >= 150 else [[650, 460, 850, 940]])
    d["body"] = [[x - 120, y - 240, x + 120, y + 240] if i < 150 else [x - 20, y - 30, x + 20, y + 30]
                 for i, (x, y) in enumerate(track)]
    assert at.analyze(d)["seconds"]["MISMATCH"] > 4
