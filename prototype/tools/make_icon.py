"""사용자 아이콘 그림(둥근 사각형 + 흰 여백 PNG) → Android 적응형 아이콘 전경 PNG.

1. 테두리에서 이어진 흰 여백을 찾아 둥근 사각형 영역만 잘라낸다.
2. 잘린 사각형의 모서리(흰 부분)는 주변 색으로 채운다 — 기기 마스크 모양에 따라 보일 수 있으므로.
3. 그림을 108dp 캔버스의 보이는 영역(가운데 72dp)에 꽉 차게 두고, 바깥 여백은 그림을 반사해 채운다.

usage: python tools/make_icon.py <input.png> <out_foreground.png> [size=432]
"""
import sys

import cv2
import numpy as np


def outside_mask(img, tol=12):
    """테두리에서 이어진 거의 흰 영역(그림 바깥) = 255."""
    h, w = img.shape[:2]
    mask = np.zeros((h + 2, w + 2), np.uint8)
    flags = 4 | cv2.FLOODFILL_MASK_ONLY | cv2.FLOODFILL_FIXED_RANGE | (255 << 8)
    for seed in [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]:
        cv2.floodFill(img.copy(), mask, seed, 0, (tol,) * 3, (tol,) * 3, flags)
    return mask[1:-1, 1:-1]


def make_foreground(img, size=432):
    out = outside_mask(img)
    ys, xs = np.where(out == 0)
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    side = max(x1 - x0, y1 - y0) + 1
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
    sl = (slice(cy - side // 2, cy - side // 2 + side), slice(cx - side // 2, cx - side // 2 + side))
    art = cv2.inpaint(img[sl], cv2.dilate(out[sl], np.ones((5, 5), np.uint8)), 9, cv2.INPAINT_TELEA)
    visible = size * 2 // 3  # 108dp 중 보이는 72dp
    art = cv2.resize(art, (visible, visible), interpolation=cv2.INTER_AREA)
    pad = (size - visible) // 2
    return cv2.copyMakeBorder(art, pad, size - visible - pad, pad, size - visible - pad, cv2.BORDER_REFLECT_101)


if __name__ == "__main__":
    size = int(sys.argv[3]) if len(sys.argv) > 3 else 432
    fg = make_foreground(cv2.imread(sys.argv[1], cv2.IMREAD_COLOR), size)
    cv2.imwrite(sys.argv[2], fg)
    b, g, r = fg[size // 2 - 2 : size // 2 + 2, 2:6].reshape(-1, 3).mean(0).astype(int)
    print(f"edge color #{r:02X}{g:02X}{b:02X}")
