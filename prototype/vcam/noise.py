"""핸드헬드 드리프트·떨림용 결정적 저주파 노이즈.

Kotlin 포팅과 결과가 같도록 numpy 난수 대신 SplitMix64 + Box-Muller를 쓴다.
"""
import math

import numpy as np

_MASK = (1 << 64) - 1


class SplitMix64:
    def __init__(self, seed):
        self.state = seed & _MASK

    def next_u64(self):
        self.state = (self.state + 0x9E3779B97F4A7C15) & _MASK
        z = self.state
        z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & _MASK
        z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & _MASK
        return z ^ (z >> 31)

    def next_double(self):
        """(0, 1] 균등분포 — log(0) 방지."""
        return ((self.next_u64() >> 11) + 1) * 2.0 ** -53

    def next_gaussian(self):
        u1, u2 = self.next_double(), self.next_double()
        return math.sqrt(-2.0 * math.log(u1)) * math.cos(2.0 * math.pi * u2)

# smoothstep 보간 value noise(격자값 ~ N(0,1))의 분산: ∫(1-s)² + s² df = 26/35
_OCTAVE_VAR = 26 / 35


def _value_noise(n, fps, hz, rng):
    """hz 간격 격자의 난수를 smoothstep 보간한 1D 노이즈."""
    t = np.arange(n) / fps * hz
    lattice = np.array([rng.next_gaussian() for _ in range(int(t[-1]) + 2)])
    i = t.astype(int)
    f = t - i
    s = f * f * (3 - 2 * f)
    return lattice[i] * (1 - s) + lattice[i + 1] * s


def smooth_noise(n, fps, hz, seed, octaves=2):
    """기본 주파수 hz 근처의 부드러운 노이즈. 이론 RMS 1(길이와 무관한 고정 정규화).
    같은 seed면 길이가 달라도 앞부분이 같다(옥타브마다 독립 난수 스트림)."""
    x = sum(0.5 ** k * _value_noise(n, fps, hz * 2 ** k, SplitMix64(seed * 1_000_003 + k)) for k in range(octaves))
    return x / np.sqrt(_OCTAVE_VAR * sum(0.25 ** k for k in range(octaves)))
