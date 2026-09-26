# -*- coding: utf-8 -*-
"""生成两张评审汇总图（docs/img/icon-review.png、icon-review-2.png）。

汇总：参考原图 / 新图标（方形·圆形）/ 四种启动器蒙版 / 多尺寸与单色层
      / 与原版等比套合 / 居中自检 / 真机截图
运行：C:/Python314/python.exe tools/make_review_sheet.py
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_launcher_icon as G   # noqa: E402

DOCS = G.DOCS
BG = (255, 255, 255)
INK = (58, 52, 46)
SUB = (128, 118, 106)
F_TITLE = G._font(26)
F_LBL = G._font(17)


def paste_masked(canvas, im, xy):
    if im.mode == "RGBA":
        canvas.paste(im, xy, im)
    else:
        canvas.paste(im, xy)


def tile_label(d, xy, text, font=None):
    d.text(xy, text, font=font or F_LBL, fill=INK)


def board1():
    W, H = 1320, 1030
    c = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(c)
    d.text((28, 20), "GIGI 应用图标 V22 —— 由官方七圣召唤徽记抽象重绘", font=F_TITLE, fill=INK)
    d.text((28, 56), "几何：单片月牙叶 + 三重对称平均 + 闭合傅里叶曲线（解析光滑）　"
                     "栅格层与矢量层同源", font=F_LBL, fill=SUB)

    y = 96
    ref = G._ref_crop(300)
    if ref is not None:
        c.paste(ref, (28, y))
    paste_masked(c, G.render_icon(300), (356, y))
    paste_masked(c, G.render_icon(300, shape="circle"), (684, y))
    themed_light = G.mono_icon(300, (0x1B, 0x4F, 0x8A))
    themed_dark = G.mono_icon(300, (0xC7, 0xD9, 0xF0))
    lw = Image.new("RGB", (300, 300), (0xEA, 0xEF, 0xF7))
    lw.paste(themed_light, (0, 0), themed_light)
    c.paste(lw, (1012, y))
    tile_label(d, (28, y + 306), "官方参考（等比取景）")
    tile_label(d, (356, y + 306), "新图标 · 方形 108dp")
    tile_label(d, (684, y + 306), "新图标 · 圆形蒙版")
    tile_label(d, (1012, y + 306), "单色层（Themed Light）")

    y2 = y + 348
    ms = 220
    for i, shp in enumerate(("circle", "squircle", "roundedsquare", "square")):
        x = 28 + i * (ms + 22)
        c.paste(G.render_icon(ms, shape=shp), (x, y2))
        tile_label(d, (x, y2 + ms + 6),
                   ("Circle 圆形", "Squircle 方圆", "Rounded 圆角方", "Square 方形")[i])
    ms2 = 220
    x = 28 + 4 * (ms + 22)
    dark = Image.new("RGB", (ms2, ms2), (0x14, 0x16, 0x1B))
    dm = G.mono_icon(ms2, (0xC7, 0xD9, 0xF0))
    dark.paste(dm, (0, 0), dm)
    c.paste(dark, (x, y2))
    tile_label(d, (x, y2 + ms2 + 6), "单色层（Themed Dark）")

    y3 = y2 + ms + 44
    sizes = [192, 144, 96, 72, 48, 36]
    x = 28
    for s in sizes:
        ic = G.render_icon(s, shape="circle")
        strip = Image.new("RGB", (s, s), (246, 244, 240))
        strip.paste(ic, (0, 0), ic)
        c.paste(strip, (x, y3 + (192 - s) // 2))
        d.text((x + s // 2 - 12, y3 + 200), f"{s}", font=F_LBL, fill=SUB)
        x += s + 22
    tile_label(d, (x + 16, y3 + 88), "各密度实拍尺寸（192/144/96/72/48/36 px）")
    c.save(os.path.join(DOCS, "icon-review.png"))
    print("写出 docs/img/icon-review.png", c.size)


def board2():
    W, H = 1320, 940
    c = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(c)
    d.text((28, 20), "GIGI 应用图标 V23 —— 与原版等比套合 / 居中自检 / 真机验证",
           font=F_TITLE, fill=INK)
    d.text((28, 56), "参考徽章按「母题外缘半径 = 图标母题半径」等比缩放后对齐："
                     "三片叶、中央金点、整体剪影均重合", font=F_LBL, fill=SUB)

    S = 300
    ref = Image.open(G.REFERENCE).convert("RGB")
    k = (S / 2.0) / 29.34
    pad = int(round(S / k))
    big = Image.new("RGB", (pad, pad), (185, 172, 166))
    big.paste(ref, (int(round(pad / 2 - 67.9)), int(round(pad / 2 - 45.9))))
    refc = big.resize((S, S), Image.LANCZOS)
    ours = G.render_icon(S, shape="circle").convert("RGB")
    blend = Image.blend(refc, ours, 0.5)

    y = 100
    c.paste(refc, (28, y))
    c.paste(ours, (28 + S + 22, y))
    c.paste(blend, (28 + 2 * (S + 22), y))
    tile_label(d, (28, y + S + 8), "官方徽章（等比放大）")
    tile_label(d, (28 + S + 22, y + S + 8), "我们的圆形图标")
    tile_label(d, (28 + 2 * (S + 22), y + S + 8), "半透明重合（越重合越好）")

    # 居中自检
    cen = Image.open(os.path.join(DOCS, "icon-centering.png")).convert("RGB").resize((300, 300))
    c.paste(cen, (28 + 3 * (S + 22), y))
    tile_label(d, (28 + 3 * (S + 22), y + S + 8), "居中自检（外接矩形=画布中心）")

    # 真机截图
    dev = os.path.join(DOCS, "device-icon-v23.png")
    if os.path.exists(dev):
        shot = Image.open(dev).convert("RGB")
        shot = shot.resize((int(shot.width * 300 / shot.height), 300), Image.LANCZOS)
        c.paste(shot, (28, y + S + 44))
        tile_label(d, (28 + shot.width + 24, y + S + 120),
                   "真机（Redmi Note 7 / MIUI）", font=G._font(20))
        tile_label(d, (28 + shot.width + 24, y + S + 152),
                   "启动器实际渲染的 adaptive icon", font=F_LBL)

    info = [
        "形状误差（对齐后，源图尺度逐像素）：",
        "　剪影 IoU 0.932　面积比 1.013",
        "　金色 IoU 0.830　面积比 1.015",
        "　褐色 IoU 0.719　面积比 1.011",
        "　金/褐 面积比　参考 1.000　我们 1.004",
        "",
        "居中：外接矩形中心残偏 %.2f dp（视觉居中）" % (G._BBOX_RES * G._K),
        "母题外缘半径 %.1f dp（可见区半径 36dp，留白 %.0f%%）"
        % (G.MOTIF_R_DP, (1.0 - G.MOTIF_R_DP / (G.VISIBLE_DP / 2.0)) * 100),
        "金越出剪影 0.00 dp（描边不会破底）",
        "",
        "本轮同时修掉：硬边锯齿（预览曾只有 64 色）、",
        "降采样振铃、多边形自交尖刺。",
    ]
    bx = 28 + shot.width + 24 if os.path.exists(dev) else 700
    for i, t in enumerate(info):
        d.text((bx, y + S + 196 + i * 26), t, font=F_LBL, fill=INK if i % 2 == 0 else SUB)
    c.save(os.path.join(DOCS, "icon-review-2.png"))
    print("写出 docs/img/icon-review-2.png", c.size)


if __name__ == "__main__":
    board1()
    board2()
