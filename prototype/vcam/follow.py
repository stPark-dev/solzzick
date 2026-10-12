"""사람 카메라맨 추종 모델: 반응 지연 + 데드존 + 2차 스프링-댐퍼."""
import math

import numpy as np


def _delayed(target, fps, delay_s):
    t = np.arange(len(target))
    return np.interp(t - delay_s * fps, t, target, left=target[0])


def simulate_follow(target, fps, omega, zeta, delay_s, deadzone=0.0, substeps=8):
    """반응 지연 + 데드존 + 2차 스프링-댐퍼로 target을 따라가는 카메라 궤적.
    목표 g는 지연된 target이 g±deadzone 밖으로 나갈 때만 경계까지 끌려가고,
    카메라는 y'' = ω²(g - y) - 2ζω y' 로 g를 따라간다."""
    xd = _delayed(np.asarray(target, float), fps, delay_s)
    substeps = max(substeps, math.ceil(omega / fps / 0.25))  # ω·dt ≤ 0.25 로 적분 안정성 확보
    dt = 1.0 / fps / substeps
    g = y = float(target[0])
    v = 0.0
    out = np.empty(len(xd))
    out[0] = y
    for i in range(1, len(xd)):
        x = xd[i]  # 프레임 i의 (지연된) 목표 — 지연 외에 한 프레임을 더 얹지 않는다
        if x - g > deadzone:
            g = x - deadzone
        elif x - g < -deadzone:
            g = x + deadzone
        for _ in range(substeps):
            v += (omega * omega * (g - y) - 2 * zeta * omega * v) * dt
            y += v * dt
        out[i] = y
    return out
