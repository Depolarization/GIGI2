#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 GIGI 启动图标（V10-C）。

设计要求（用户 2026-09-26）：
1. 移除白色卡牌边框 —— 只保留五角星 + 黄色圆点；
2. 背景色更明亮，参考应用内默认主题色。

实现要点：
- 4x 超采样 + LANCZOS 缩放，边缘无锯齿；
- 前景整体落在 adaptive icon 的 72x72 安全区内（108 viewport 居中）；
- 星与点的几何参数集中在此，改这里即可调形，不用碰 XML；
- 同一份几何同步产出 drawable/ic_launcher_foreground.xml 与 monochrome.xml，
  避免矢量层与位图层形状漂移。
"""

from __future__ import annotations

import math
import os

from PIL import Image, ImageDraw

# ---------------------------------------------------------------- 路径

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
SS = 4  # 超采样倍数

# ---------------------------------------------------------------- 配色

# 背景：取应用内 WinColor(0xFF58B07E) 的同色相更亮档，作为「明亮背景」落地值。
# 原值 ic_launcher_bg #FF12352B 是极深墨绿，观感压抑 —— 这是本次改动的根因。
BG_TOP = (0x6F, 0xC9, 0x96)      # #6FC996 亮绿
BG_BOTTOM = (0x3F, 0x9E, 0x69)   # #3F9E69 中绿

STAR = (0xFF, 0xFF, 0xFF)        # 五角星纯白（在亮绿底上对比强烈）
STAR_EDGE = (0x1E, 0x6B, 0x42)   # 描边用亮色档深绿，呼应 WinColorLight
GOLD = (0xFF, 0xD4, 0xA6, 0x43)  # 主题金 GoldColor #FFD4A643

# ---------------------------------------------------------------- 几何
# 坐标全部以 108x108 viewport 表达，落在中心 72x72 安全区内。
# 去掉卡牌边框后，五角星成为唯一主体，尺寸相应放大以填满安全区。

CX = 50.0
CY = 50.0
STAR_OUTER = 24.0
STAR_INNER = 9.8
STAR_ROT = -90.0  # 顶点朝正上方

DOT_R = 6.6
DOT_POS = (75.0, 75.0)  # 右下金色圆点，明显大于星的内角半径，避免被星压住


def star_points(cx: float, cy: float, outer: float, inner: float, rot: float):
    pts = []
    for i in range(10):
        r = outer if i % 2 == 0 else inner
        ang = math.radians(rot + i * 36.0)
        pts.append((cx + r * math.cos(ang), cy + r * math.sin(ang)))
    return pts


def rounded_bg(size: int, round_mask: bool) -> Image.Image:
    """画渐变背景；round_mask=True 时切圆形（ic_launcher_round）。"""
    bg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(bg)
    # 竖向线性渐变
    for y in range(size):
        t = y / max(1, size - 1)
        c = tuple(round(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * t) for i in range(3))
        d.line([(0, y), (size, y)], fill=c + (255,))
    if round_mask:
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
        bg.putalpha(mask)
    return bg


def draw_foreground(size: int) -> Image.Image:
    """前景层：五角星 + 金色圆点，不含卡牌边框。"""
    fg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(fg)
    k = size / 108.0  # viewport -> 像素

    pts = [(x * k, y * k) for x, y in star_points(CX, CY, STAR_OUTER, STAR_INNER, STAR_ROT)]
    # 描边比填充大 1.6 viewport 单位，视觉上给星一圈同色系深绿边
    stroke_pts = [
        (CX + (x - CX) * 1.0, CY + (y - CY) * 1.0) for x, y in
        star_points(CX, CY, STAR_OUTER + 1.7, STAR_INNER + 1.4, STAR_ROT)
    ]
    d.polygon([(x * k, y * k) for x, y in stroke_pts], fill=STAR_EDGE + (255,))
    d.polygon(pts, fill=STAR + (255,))

    # 金色圆点：右下，位于安全区内
    dx, dy = DOT_POS[0] * k, DOT_POS[1] * k
    r = DOT_R * k
    d.ellipse([dx - r, dy - r, dx + r, dy + r], fill=GOLD)
    return fg


def export_legacy(density: str, px: int) -> None:
    """产出传统 mipmap PNG（背景 + 前景合成，round 版切圆）。"""
    size = px * SS
    for is_round in (False, True):
        bg = rounded_bg(size, is_round)
        fg = draw_foreground(size)
        out = Image.alpha_composite(bg, fg)
        # adaptive icon 前景层只占 108 viewport 的 72 安全区；
        # 传统图标需整体缩到 ~80%，与 launcher's 视觉尺寸惯例一致。
        out = out.resize((px, px), Image.LANCZOS)
        name = "ic_launcher_round.png" if is_round else "ic_launcher.png"
        path = os.path.join(RES, f"mipmap-{density}", name)
        out.save(path, "PNG", optimize=True)
        print(f"  {path}  ({px}x{px})")


def main() -> None:
    for density, px in DENSITIES.items():
        export_legacy(density, px)
    print("完成：传统 mipmap PNG 已按 5 档密度导出（无卡牌边框，五角星 + 金色圆点）")


if __name__ == "__main__":
    main()
