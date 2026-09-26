#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 GIGI 启动图标（V21：七圣召唤「三叶交织」母题 · 极坐标单叶模板）。

设计来源
--------
把官方七圣召唤图标左侧的三叶交织徽记**抽象**重绘。对参考原图做颜色分割、
极坐标映射与三重对称折叠后得到母题：

    褐色基底（三瓣外鼓、瓣间收腰的圆钝三角剪影）
  + 三片**金色宽楔形叶**（外端是平阔的宽头，向中心螺旋收细）
  + 中心一枚金点

几何不再由公式描述，而是由 `tools/icon_template.py` 的**单叶模板表**给出。

为什么用极坐标掩码而不是「中心线 + 宽度」
----------------------------------------
参考图的叶在角向上跨越 **超过 120°** —— θ=225° 处一条射线会穿过两片叶。
因此「中心线 + 一条宽度曲线」这种单值模型**在原理上无法表达**该结构：
实测该族模型的金面 IoU 上限只有 ~0.784（且是反复调参后的结果）。
极坐标掩码没有这个限制：同一角度可以有两段金。改用掩码后金 IoU 提升到 **0.815**，
视觉上叶的「宽楔形 + 平阔宽头」也终于与原版一致。

抗锯齿
------
**不用「多边形填充 + 超采样降采样」**：那条路径有两个不可控的瑕疵 ——
多边形在窄端自交会产生毛刺；LANCZOS 降采样会在平色区产生 ringing 振铃「脏点」。

改为**解析覆盖率**：在 `size × SS` 的子采样网格上查模板表得到 in/out，
再按子像素块平均得到 0~1 的覆盖率，最后做**面积混合**：

    色 = 金·cov_gold + 褐·(cov_base − cov_gold) + 背景·(1 − cov_base)

没有栅格降采样、没有浮点振铃、没有自交。蒙版（圆 / 方圆 / 圆角方 / 方）同样走覆盖率。

为什么矢量层必须由本脚本生成
----------------------------
- Android 8+ 的 adaptive icon **实际优先使用矢量层**（mipmap-anydpi-v26 胜过 mipmap-* PNG）。
- 因此本脚本是几何的**唯一来源**：同一份模板同时导出 5 档 PNG（含圆形版）
  与 drawable 的 foreground / monochrome 矢量层，栅格与矢量严格同构。
- 金的多边形按「同一角度的每段径向游程」各自成环，与栅格的查表判定完全等价。

居中
----
三向旋转对称的图形，**面积质心必然落在几何中心，但外接矩形的中心一定不重合** ——
这是 3 重对称的固有结论。参考原图同样如此。本脚本用 `CENTER` 选择对齐基准：
    "bbox" | "centroid" | "mid"（默认，两者折中）

用法
----
  python tools/gen_launcher_icon.py --preview   # 出预览图到 docs/img（默认）
  python tools/gen_launcher_icon.py --export    # 写入 app/src/main/res
  python tools/gen_launcher_icon.py --all
  python tools/gen_launcher_icon.py --center mid
"""
from __future__ import annotations

import argparse
import math
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFont

import icon_template as T

# ------------------------------------------------------------------ 路径
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
DOCS = os.path.join(ROOT, "docs", "img")
REFERENCE = os.path.join(os.path.expanduser("~"), ".workbuddy", "clipboard-images",
                         "clipboard-2026-09-26T08-08-59-795Z-fbf98604.png")

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

# 覆盖率子采样：画布边长上限与倍率上限。精度 = 每像素 SS² 级灰阶。
SS_MAX = 12
SS_CAP = 3072
_SS_MIN = 2
_CHUNK_PTS = 2_000_000              # 每个分块的最大子采样点数（控内存）

# ------------------------------------------------------------------ 画布
VP = 108.0                          # 自适应图标 viewport（同时也是前景 viewport 尺寸）
CX = CY = VP / 2.0
VISIBLE_DP = 72.0                   # 启动器实际可见的中央区域边长（108 -> 72）
PAD_DP = (VP - VISIBLE_DP) / 2.0    # 可见区左上角在 108dp 图层里的偏移（= 18dp）
MOTIF_R_DP = 33.0                   # 母题外缘半径(dp)，正好填满自适应图标保证区 ⌀66dp

# ------------------------------------------------------------------ 母题几何
# tools/icon_template.py 给出两条闭合傅里叶曲线：整体剪影 + 单片主叶；
# 另外两片叶由 +120° / +240° 旋转得到。本文件把它们栅格化成极坐标表，
# 供「解析覆盖率」渲染使用；矢量层直接采样同一条曲线 —— 两者严格同源。
NB_U = 3456                         # 极坐标表角向格数（0.104°，≈0.1px 精度）
R_MAX = 1.0                         # 剪影最大半径（icon_template 的归一化单位）
DOT_R = T.DOT_R                     # 中央金点半径（正圆）
LEAF_ROT = T.LEAF_ROT               # 三片叶的旋转角（度）

BASE_N = 384                        # 剪影多边形采样点数（矢量层 + 覆盖率）
GOLD_N = 320                        # 单片叶多边形采样点数

_SIL = None                         # 剪影半径表 [NB_U]
_LEAF_LO = _LEAF_HI = None          # 主叶径向区间表 [NB_U]（HI<=LO 表示该方向无叶）


def _ray_crossings(poly, d):
    """射线（原点出发，方向 d）与闭合折线的所有交点半径，按升序（不足补 inf）。"""
    A = poly
    s = np.roll(poly, -1, axis=0) - A
    cr = d[:, None, 0] * s[None, :, 1] - d[:, None, 1] * s[None, :, 0]      # cross(d,s)
    cAs = A[:, 0][None, :] * s[:, 1][None, :] - A[:, 1][None, :] * s[:, 0][None, :]
    cAd = A[:, 0][None, :] * d[:, None, 1] - A[:, 1][None, :] * d[:, None, 0]
    with np.errstate(divide="ignore", invalid="ignore"):
        u = cAs / cr
        v = cAd / cr
    ok = (np.abs(cr) > 1e-12) & (v >= 0.0) & (v < 1.0) & (u > 0.0)
    u = np.where(ok, u, np.inf)
    u.sort(axis=1)
    return u


def _build_polar():
    """把解析曲线栅格化为极坐标表（模块导入时执行一次）。"""
    global _SIL, _LEAF_LO, _LEAF_HI
    th = 2.0 * np.pi * np.arange(NB_U) / NB_U
    d = np.stack([np.cos(th), np.sin(th)], axis=1)

    us = _ray_crossings(T.sil_poly(4096), d)
    _SIL = us[:, 0].copy()                      # 剪影每列恰好 1 次穿越（已实测）
    if not np.isfinite(_SIL).all():
        raise RuntimeError("剪影不是关于中心星形的，极坐标模型不适用")

    ul = _ray_crossings(T.leaf_poly(4096), d)
    lo, hi = ul[:, 0].copy(), ul[:, 1].copy()
    keep = np.isfinite(lo)                      # 主叶每列 0 或 1 个径向区间（已实测）
    _LEAF_LO = np.where(keep, lo, 1.0)
    _LEAF_HI = np.where(keep, hi, 0.0)


_build_polar()

_LEAF_SHIFT = [int(round(r / 360.0 * NB_U)) % NB_U for r in LEAF_ROT]


def sil_at(theta_deg):
    """给定方位角（度，可用 ndarray）的剪影半径 —— 对表做周期线性插值。"""
    ul = (np.asarray(theta_deg, float) % 360.0) / 360.0 * NB_U
    i0 = np.floor(ul).astype(np.int64)
    fr = ul - i0
    i0 %= NB_U
    i1 = (i0 + 1) % NB_U
    return _SIL[i0] * (1.0 - fr) + _SIL[i1] * fr


def _u_index(theta_deg):
    """方位角 -> 极坐标表列索引（最近邻）。"""
    ul = np.floor((theta_deg % 360.0) / 360.0 * NB_U).astype(np.int64)
    return np.clip(ul, 0, NB_U - 1)

# ------------------------------------------------------------------ 居中基准
CENTER = "centroid"                 # "bbox" | "centroid" | "mid"

# ------------------------------------------------------------------ 配色
BG_TOP = (0xFD, 0xFA, 0xF3)
BG_BOTTOM = (0xEF, 0xE3, 0xCE)
# 取自参考徽章的实测色：金 (237,204,130)、褐 (133,83,66)
GOLD_FLAT = (0xED, 0xCC, 0x82)
OUTLINE_RGB = (0x85, 0x53, 0x42)

SQUIRCLE_N = 4.0                    # 超椭圆指数：4 ≈ AOSP squircle 的观感


# ================================================================== 归一化与居中
_SHAPES = None
_OFF = (0.0, 0.0)
_K = 1.0
_BBOX_RES = 0.0
_CENT_RES = 0.0
_COV = {}
_SHAPE_COV = {}


def _bounds(polys):
    xs, ys = [], []
    for p in polys:
        for x, y in p:
            xs.append(x)
            ys.append(y)
    return min(xs), min(ys), max(xs), max(ys)


def _centroid(polys):
    """多边形的面积质心（归一化坐标）。"""
    ax = ay = a = 0.0
    for p in polys:
        m = len(p)
        c = 0.0
        for i in range(m):
            x0, y0 = p[i]
            x1, y1 = p[(i + 1) % m]
            cr = x0 * y1 - x1 * y0
            c += cr
            ax += (x0 + x1) * cr
            ay += (y0 + y1) * cr
        a += c
    if abs(a) < 1e-12:
        return 0.0, 0.0
    return ax / (3.0 * a), ay / (3.0 * a)


def base_polygon(n: int = BASE_N):
    """母题外缘（剪影）多边形 —— 由同一张剪影半径表生成，与栅格严格同源。"""
    a = 360.0 * np.arange(n) / n
    r = sil_at(a)
    th = np.radians(a)
    return list(zip((r * np.cos(th)).tolist(), (r * np.sin(th)).tolist()))


def gold_polygons(n: int = GOLD_N):
    """三片金叶多边形：主叶曲线绕中心旋转 0° / 120° / 240°（解析曲线直接采样）。"""
    return [list(map(tuple, T.leaf_poly(n, rot))) for rot in LEAF_ROT]


def _setup(center=None):
    """算出绘制偏移与缩放系数（模块导入时执行一次，也可用 --center 覆盖后重算）。"""
    global _SHAPES, _OFF, _K, _BBOX_RES, _CENT_RES, CENTER
    if center:
        CENTER = center
    base = base_polygon()
    _SHAPES = (base, gold_polygons())

    x0, y0, x1, y1 = _bounds([base])
    bx, by = (x0 + x1) / 2.0, (y0 + y1) / 2.0          # 外接矩形中心
    cx_, cy_ = _centroid([base])                       # 面积质心
    if CENTER == "centroid":
        _OFF = (-cx_, -cy_)
    elif CENTER == "mid":
        _OFF = (-(bx + cx_) / 2.0, -(by + cy_) / 2.0)
    else:
        _OFF = (-bx, -by)
    _BBOX_RES = math.hypot(bx + _OFF[0], by + _OFF[1])
    _CENT_RES = math.hypot(cx_ + _OFF[0], cy_ + _OFF[1])

    far = max(math.hypot(x + _OFF[0], y + _OFF[1]) for x, y in base)
    far = max(far, DOT_R + math.hypot(*_OFF))
    _K = MOTIF_R_DP / far

    _COV.clear()
    _SHAPE_COV.clear()


def norm_to_dp(x: float, y: float):
    """归一化母题坐标 -> dp 画布坐标（108dp viewport）。"""
    return CX + (x + _OFF[0]) * _K, CY + (y + _OFF[1]) * _K


_setup()

DOT_DP = DOT_R * _K


def self_check():
    """自检：金叶是否越出剪影（越出会让叶「破」出底色）。返回最大越出量（归一化单位）。"""
    worst = 0.0
    for shift in _LEAF_SHIFT:
        hi = np.roll(_LEAF_HI, shift)
        if hi.size:
            worst = max(worst, float(np.maximum(hi - _SIL, 0.0).max()))
    return worst


# ================================================================== 覆盖率渲染核心

def _ss(size):
    """按画布尺寸挑子采样倍率（保证子采样画布不超过 SS_CAP）。"""
    ss = SS_MAX
    while ss > _SS_MIN and size * ss > SS_CAP:
        ss -= 1
    return ss


def _coverage(size):
    """返回 (cov_base, cov_gold)：母题基底与金层的覆盖率，float32，值域 0~1。"""
    if size in _COV:
        return _COV[size]
    ss = _ss(size)
    n = size * ss
    ppd = n / VISIBLE_DP
    # 画布覆盖 108dp 图层的中央 72dp：dp 从 PAD_DP 起
    xs = np.arange(n, dtype=np.float32) + 0.5
    Xn = (PAD_DP + xs / ppd - CX) / _K - _OFF[0]

    cov_b = np.empty((size, size), np.float32)
    cov_g = np.empty((size, size), np.float32)
    step = max(ss, (_CHUNK_PTS // n // ss) * ss)
    for r0 in range(0, n, step):
        r1 = min(n, r0 + step)
        ys = np.arange(r0, r1, dtype=np.float32) + 0.5
        Yn = (PAD_DP + ys / ppd - CY) / _K - _OFF[1]
        X, Y = np.meshgrid(Xn, Yn)
        R = np.hypot(X, Y)
        TH = np.degrees(np.arctan2(Y, X))

        ui = _u_index(TH)
        gold = R <= DOT_R
        for shift in _LEAF_SHIFT:                       # 三片叶 = 主叶旋转 120° 的副本
            uj = np.subtract(ui, shift, dtype=np.int64) % NB_U
            lo = _LEAF_LO[uj]
            hi = _LEAF_HI[uj]
            gold |= (hi > lo) & (R >= lo) & (R <= hi)
        base = (R <= _SIL[ui]) | gold

        rows = (r1 - r0) // ss
        cov_b[r0 // ss:r0 // ss + rows] = base.reshape(rows, ss, size, ss).mean(axis=(1, 3))
        cov_g[r0 // ss:r0 // ss + rows] = gold.reshape(rows, ss, size, ss).mean(axis=(1, 3))

    _COV[size] = (cov_b, cov_g)
    return cov_b, cov_g


def _shape_cov(size, shape="circle"):
    """蒙版覆盖率（同样抗锯齿）：circle | roundedsquare | squircle | square。"""
    key = (size, shape)
    if key in _SHAPE_COV:
        return _SHAPE_COV[key]
    ss = _ss(size)
    n = size * ss
    xs = (np.arange(n, dtype=np.float32) + 0.5) / ss      # 子采样点 -> 输出像素坐标
    X, Y = np.meshgrid(xs, xs, copy=False)
    if shape == "square":
        m = np.ones((n, n), bool)
    else:
        c = size / 2.0
        u = np.abs(X - c) / c
        v = np.abs(Y - c) / c
        if shape == "circle":
            m = u * u + v * v <= 1.0
        elif shape == "squircle":
            m = np.power(u, SQUIRCLE_N) + np.power(v, SQUIRCLE_N) <= 1.0
        elif shape == "roundedsquare":
            r = 0.22 * size
            qx = np.maximum(np.abs(X - c) - (c - r), 0.0)
            qy = np.maximum(np.abs(Y - c) - (c - r), 0.0)
            sdf = np.hypot(qx, qy) + np.minimum(
                np.maximum(np.abs(X - c) - (c - r), np.abs(Y - c) - (c - r)), 0.0) - r
            m = sdf <= 0.0
        else:
            raise ValueError(f"未知蒙版形状: {shape}")
    cov = m.reshape(size, ss, size, ss).mean(axis=(1, 3)).astype(np.float32)
    _SHAPE_COV[key] = cov
    return cov


def _bg_rgb(size, top=BG_TOP, bottom=BG_BOTTOM):
    """淡雅暖米白竖向渐变（对应 108dp 图层里的中央 72dp 段）。"""
    j = (np.arange(size, dtype=np.float32) + 0.5) / size
    y_dp = PAD_DP + j * VISIBLE_DP
    t = y_dp / VP
    t = t * t * (3.0 - 2.0 * t)                       # smoothstep，避免色带
    a = np.asarray(top, np.float32)
    b = np.asarray(bottom, np.float32)
    return (a[None, None, :] + (b - a)[None, None, :] * t[:, None, None]).astype(np.float32)


def _blend(bg_rgb, cov_base, cov_gold, gold=GOLD_FLAT, outline=OUTLINE_RGB):
    """面积混合：金按 cov_gold，褐按 (cov_base − cov_gold)，其余留背景。"""
    cg = cov_gold[..., None]
    cb = cov_base[..., None]
    gold = np.asarray(gold, np.float32)
    brown = np.asarray(outline, np.float32)
    col = (gold[None, None, :] * cg + brown[None, None, :] * (cb - cg) +
           bg_rgb * (1.0 - cb))
    return np.clip(col, 0.0, 255.0)


def render_icon(size, round_mask=False, shape=None, top=BG_TOP, bottom=BG_BOTTOM,
                gold=GOLD_FLAT, outline=OUTLINE_RGB):
    """按启动器的真实裁切渲染图标：画布对应 108dp 图层的中央 72dp。"""
    cov_b, cov_g = _coverage(size)
    col = _blend(_bg_rgb(size, top, bottom), cov_b, cov_g, gold, outline)
    rgb = Image.fromarray(np.rint(col).astype(np.uint8), "RGB")
    if not (round_mask or shape):
        return rgb
    cov_m = _shape_cov(size, shape or "circle")
    a = np.rint(np.clip(cov_m, 0.0, 1.0) * 255.0).astype(np.uint8)
    return Image.fromarray(np.dstack([np.asarray(rgb), a]), "RGBA")


def mono_icon(size, color=(0, 0, 0), shape=None):
    """单色（Android 13 Themed）层：只保留三片金叶与中心点，叶间留白成透明缝。"""
    _, cov_g = _coverage(size)
    a = cov_g
    if shape:
        a = a * _shape_cov(size, shape)
    alpha = np.rint(np.clip(a, 0.0, 1.0) * 255.0).astype(np.uint8)
    rgb = np.zeros((size, size, 3), np.uint8)
    rgb[:, :] = np.asarray(color, np.uint8)
    return Image.fromarray(np.dstack([rgb, alpha]), "RGBA")


# ================================================================== 矢量层输出

def _f(v: float) -> str:
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return s if s not in ("-0", "") else "0"


def _poly_path_data(poly):
    out = []
    for i, (x, y) in enumerate(poly):
        x, y = norm_to_dp(x, y)
        out.append(("M" if i == 0 else "L") + f"{_f(x)},{_f(y)}")
    out.append("Z")
    return "".join(out)


def _fill_path(data, color):
    return ('<path\n'
            f'        android:pathData="{data}"\n'
            f'        android:fillColor="{color}"\n'
            '        android:strokeColor="#00000000" />')


def _circle_path(cx, cy, r, color):
    return _fill_path(
        f"M{_f(cx - r)},{_f(cy)} a{_f(r)},{_f(r)} 0 1,0 {_f(2 * r)},0 "
        f"a{_f(r)},{_f(r)} 0 1,0 {_f(-2 * r)},0 Z", color)


def motif_paths(with_outline=True):
    base, gg = _SHAPES
    out = []
    if with_outline:
        out.append(_fill_path(_poly_path_data(base), "@color/ic_launcher_outline"))
    for p in gg:
        out.append(_fill_path(_poly_path_data(p), "@color/ic_launcher_gold"))
    x, y = norm_to_dp(0.0, 0.0)
    out.append(_circle_path(x, y, DOT_DP, "@color/ic_launcher_gold"))
    return out


def mono_paths():
    """单色层（Android 13 themed icon）：只保留金叶本身，叶间留白即透明缝。"""
    _, gg = _SHAPES
    out = [_fill_path(_poly_path_data(p), "#FF000000") for p in gg]
    x, y = norm_to_dp(0.0, 0.0)
    out.append(_circle_path(x, y, DOT_DP, "#FF000000"))
    return out


VEC_HEAD = """<?xml version="1.0" encoding="utf-8"?>
<!-- 由 tools/gen_launcher_icon.py 自动生成，请勿手改 —— 改形状请改脚本后重跑。
     {desc}

     几何来源：tools/icon_template.py 的**解析曲线**（由参考原图提取单片月牙叶后，
       做三重对称平均并拟合为闭合傅里叶级数 —— 天然闭合、C∞ 光滑、严格三重对称）。
       剪影 {silK} 阶 / 主叶 {leafK} 阶；栅格层用的极坐标表 {nbu} 列、R_MAX {rmax}，
       由同一条曲线射线求交而来，故栅格层与矢量层形状严格同构。
       母题外缘半径 {rad:.2f}dp（可见区半宽 36dp / 保证区 ⌀66dp）。居中基准：{center}。
     Android 8+ 的 adaptive icon 走的就是本文件（mipmap-anydpi-v26 优先于 mipmap-* PNG）。-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
"""


def write_vector(paths, out_path, desc):
    header = VEC_HEAD.format(desc=desc, nbu=NB_U, rmax=R_MAX, rad=MOTIF_R_DP,
                             center=CENTER, silK=T.SIL_K, leafK=T.LEAF_K)
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(header + "\n".join(paths) + "\n</vector>\n")
    print(f"  {os.path.relpath(out_path, ROOT)}  ({len(paths)} 个子路径)")


# ================================================================== 预览

def _font(px):
    for p in (r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\msyhbd.ttc",
              r"C:\Windows\Fonts\simhei.ttf", r"C:\Windows\Fonts\arial.ttf"):
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, px)
            except Exception:
                pass
    return ImageFont.load_default()


# 参考徽章上的母题旋转中心与母题外缘半径（源图像素，来自 tools/fit_icon_geometry.py）
REF_C = (67.82, 45.79)
REF_R = 28.779


def _ref_crop(size, frac=None):
    """按与母题**完全相同的取景**裁出参考徽章，便于并排比对。"""
    if not os.path.exists(REFERENCE):
        return None
    try:
        src = Image.open(REFERENCE).convert("RGB")
    except Exception:
        return None
    # 目标：参考母题外缘半径占画布半宽的比例 = 图标母题的同一比例
    ratio = MOTIF_R_DP / (VISIBLE_DP / 2.0)
    half = REF_R / ratio
    k = (size * 0.5) / half
    canvas = max(1, int(round(size / k)))
    pad = Image.new("RGB", (canvas, canvas), (246, 246, 246))
    pad.paste(src, (int(round(canvas / 2 - REF_C[0])), int(round(canvas / 2 - REF_C[1]))))
    return pad.resize((size, size), Image.LANCZOS)


def _label(canvas, text, cx, top, font, fill=(64, 58, 50)):
    d = ImageDraw.Draw(canvas)
    w = d.textlength(text, font=font)
    d.text((cx - w / 2.0, top), text, font=font, fill=fill)


def _tile(canvas, rgba, xy, bg_rgb):
    x, y = int(xy[0]), int(xy[1])
    tile = Image.new("RGB", rgba.size, bg_rgb)
    tile.paste(rgba, (0, 0), rgba)
    canvas.paste(tile, (x, y))


def _preview_masks(ps=320):
    shapes = [("circle", "Circle 圆形"), ("squircle", "Squircle 方圆"),
              ("roundedsquare", "Rounded 圆角方"), ("square", "Square 方形")]
    GAP = 26
    canvas = Image.new("RGB", (GAP + len(shapes) * (ps + GAP), ps + GAP * 3 + 8), (255, 255, 255))
    font = _font(19)
    for i, (shape, label) in enumerate(shapes):
        x = GAP + i * (ps + GAP)
        ic = render_icon(ps, shape=shape)
        _tile(canvas, ic, (x, GAP), (250, 248, 244))
        _label(canvas, label, x + ps / 2.0, GAP * 2 + ps + 2, font)
    canvas.save(os.path.join(DOCS, "icon-masks.png"))


def _preview_themed(ts=320):
    cols = [("Color 原色", None, (250, 248, 244)),
            ("Themed · Light", (0x2F, 0x52, 0x8F), (0xE7, 0xED, 0xF8)),
            ("Themed · Dark", (0xC6, 0xD5, 0xF6), (0x13, 0x17, 0x21))]
    GAP = 26
    canvas = Image.new("RGB", (GAP + len(cols) * (ts + GAP), ts + GAP * 3 + 8), (255, 255, 255))
    font = _font(19)
    for i, (label, tint, bg) in enumerate(cols):
        x = GAP + i * (ts + GAP)
        ic = (render_icon(ts, shape="circle") if tint is None
              else mono_icon(ts, color=tint, shape="circle"))
        _tile(canvas, ic, (x, GAP), bg)
        _label(canvas, label, x + ts / 2.0, GAP * 2 + ts + 2, font)
    canvas.save(os.path.join(DOCS, "icon-themed.png"))


def _preview_centering():
    """居中自检：画布中心十字、母题外接矩形、面积质心，并给出残偏数字。"""
    S = 480
    cov_b, _ = _coverage(S)
    bg = np.full((S, S, 3), (250, 248, 244), np.float32)
    col = bg * (1.0 - cov_b[..., None]) + np.asarray((228, 208, 172), np.float32) * cov_b[..., None]
    img = Image.fromarray(np.rint(col).astype(np.uint8), "RGB")
    d = ImageDraw.Draw(img)

    px = S / VISIBLE_DP

    def T(x, y):
        a, b = norm_to_dp(x, y)
        return (a - PAD_DP) * px, (b - PAD_DP) * px

    ctr = (CX - PAD_DP) * px
    d.line([(ctr, 10), (ctr, S - 10)], fill=(210, 90, 90), width=1)
    d.line([(10, ctr), (S - 10, ctr)], fill=(210, 90, 90), width=1)

    x0, y0, x1, y1 = _bounds([_SHAPES[0]])
    ax, ay = T(x0, y0)
    bx, by = T(x1, y1)
    d.rectangle([ax, ay, bx, by], outline=(70, 120, 200), width=2)

    mx, my = _centroid([_SHAPES[0]])
    ox, oy = T(mx, my)
    d.ellipse([ox - 5, oy - 5, ox + 5, oy + 5], fill=(60, 150, 90))

    font = _font(17)
    info = [f"居中基准 = {CENTER}",
            f"外接矩形中心残偏 {_BBOX_RES * _K:.2f} dp",
            f"面积质心残偏 {_CENT_RES * _K:.2f} dp",
            f"母题外缘半径 {MOTIF_R_DP:.1f} dp（保证区半径 33 dp）"]
    for i, t in enumerate(info):
        d.text((16, 14 + i * 23), t, font=font, fill=(50, 44, 38))
    d.text((16, S - 34), "红=画布中心线　蓝=外接矩形　绿=面积质心", font=font, fill=(90, 82, 72))
    img.save(os.path.join(DOCS, "icon-centering.png"))


def preview():
    os.makedirs(DOCS, exist_ok=True)
    SZ = 512
    square = render_icon(SZ)
    rnd = render_icon(SZ, shape="circle")
    square.save(os.path.join(DOCS, "icon.png"))
    onwhite = Image.new("RGB", (SZ, SZ), (255, 255, 255))
    onwhite.paste(rnd, (0, 0), rnd)
    onwhite.save(os.path.join(DOCS, "icon-round.png"))

    sheet = Image.new("RGB", (SZ * 2 + 40, SZ + 20), (255, 255, 255))
    sheet.paste(square, (10, 10))
    sheet.paste(onwhite, (SZ + 30, 10))
    sheet.save(os.path.join(DOCS, "icon-preview.png"))

    sizes = [192, 144, 96, 72, 48, 36]
    strip = Image.new("RGB", (sum(sizes) + 20 * (len(sizes) + 1), 240), (245, 245, 245))
    x = 20
    for s in sizes:
        ic = render_icon(s, shape="circle")
        bgc = Image.new("RGB", (s, s), (245, 245, 245))
        bgc.paste(ic, (0, 0), ic)
        strip.paste(bgc, (x, 20 + (192 - s) // 2))
        x += s + 20
    strip.save(os.path.join(DOCS, "icon-sizes.png"))

    ref = _ref_crop(360, frac=0.90)
    if ref is not None:
        mine = Image.new("RGB", (360, 360), (250, 247, 241))
        mine.paste(render_icon(360), (0, 0))
        both = Image.new("RGB", (360 * 2 + 24, 380), (255, 255, 255))
        both.paste(ref, (0, 10))
        both.paste(mine, (360 + 24, 10))
        both.save(os.path.join(DOCS, "icon-vs-reference.png"))

    _preview_masks()
    _preview_themed()
    _preview_centering()
    print("预览已写入", os.path.relpath(DOCS, ROOT))


# ================================================================== 导出

def export():
    for density, px in DENSITIES.items():
        # 覆盖率渲染本身就是抗锯齿的：直接在目标尺寸出图，不需要超采样降采样
        # （LANCZOS 降采样会在平色区产生 ringing 振铃，正是「脏点」的来源之一）。
        for is_round in (False, True):
            img = render_icon(px, shape="circle" if is_round else None)
            if not is_round:
                img = img.convert("RGB")
            name = "ic_launcher_round.png" if is_round else "ic_launcher.png"
            p = os.path.join(RES, f"mipmap-{density}", name)
            img.save(p, "PNG", optimize=True)
            print(f"  mipmap-{density}/{name}  ({px}x{px})")

    write_vector(motif_paths(), os.path.join(RES, "drawable", "ic_launcher_foreground.xml"),
                 desc="自适应图标前景层：褐底剪影 + 三片旋转 120° 的金色月牙叶 + 中心金点。")
    write_vector(mono_paths(), os.path.join(RES, "drawable", "ic_launcher_monochrome.xml"),
                 desc="单色（Themed icon）层：只保留三片金叶与中心金点，叶间留白天然成缝。")
    write_background()
    write_colors()


BG_HEAD = """<?xml version="1.0" encoding="utf-8"?>
<!-- 由 tools/gen_launcher_icon.py 自动生成，请勿手改。淡雅暖米白竖向渐变素底。 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:startX="0" android:startY="0"
                android:endX="0" android:endY="108"
                android:type="linear">
                <item android:offset="0" android:color="@color/ic_launcher_bg_top" />
                <item android:offset="1" android:color="@color/ic_launcher_bg_bottom" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
"""


def _hex(rgb):
    return "#FF%02X%02X%02X" % tuple(rgb)


COLORS_XML = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!--
      启动图标调色板 —— 矢量层与 PNG 导出的唯一色源
      （由 tools/gen_launcher_icon.py 的 BG_TOP/BG_BOTTOM/GOLD_FLAT/OUTLINE_RGB 生成，
       请勿手改；改色请改生成器常量后重跑 export）。
      V22：母题由参考原图提取单片月牙叶后三重对称平均，并拟合为闭合傅里叶曲线
      ic_launcher_bg 为启动屏 splash 用的平色（渐变不能用于 windowSplashScreenBackground）。
    -->
    <color name="ic_launcher_bg_top">%s</color>
    <color name="ic_launcher_bg_bottom">%s</color>
    <color name="ic_launcher_bg">%s</color>
    <color name="ic_launcher_gold">%s</color>
    <color name="ic_launcher_outline">%s</color>
</resources>
""" % (_hex(BG_TOP), _hex(BG_BOTTOM), _hex(BG_TOP), _hex(GOLD_FLAT), _hex(OUTLINE_RGB))


def write_background():
    p = os.path.join(RES, "drawable", "ic_launcher_background.xml")
    with open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(BG_HEAD)
    print(f"  {os.path.relpath(p, ROOT)}")


def write_colors():
    p = os.path.join(RES, "values", "ic_launcher_background.xml")
    with open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(COLORS_XML)
    print(f"  {os.path.relpath(p, ROOT)}")


def main():
    ap = argparse.ArgumentParser(description="GIGI 启动图标生成器（七圣召唤三叶交织 · 极坐标单叶模板）")
    ap.add_argument("--export", action="store_true", help="写入 app/src/main/res")
    ap.add_argument("--preview", action="store_true", help="只出预览图到 docs/img（默认行为）")
    ap.add_argument("--all", action="store_true", help="预览 + 导出")
    ap.add_argument("--center", choices=["bbox", "centroid", "mid"],
                    help="居中基准（默认按脚本里的 CENTER 常量）")
    args = ap.parse_args()
    if args.center:
        _setup(args.center)
        print("居中基准临时切换为", CENTER)
    if args.export or args.all:
        export()
    if args.preview or args.all or not args.export:
        preview()
    w = self_check()
    print("金越出剪影检查：最大越出 %.4f（%.2f dp）" % (w, w * _K))
    print("居中基准 %s：外接矩形中心残偏 %.2f dp，面积质心残偏 %.2f dp；母题外缘半径 %.2f dp"
          % (CENTER, _BBOX_RES * _K, _CENT_RES * _K, MOTIF_R_DP))


if __name__ == "__main__":
    main()
