# -*- coding: utf-8 -*-
"""
从官方七圣召唤徽章提取应用图标母题的解析几何（一次性离线工具）。

流程
  1. 参考图按三簇代表色做软隶属（金 / 褐 / 徽章底），得到两个 [0,1] 场：
       F_gold  = 金色隶属度
       F_motif = 金∪褐 的隶属度（即母题剪影）
  2. 绕徽章中心旋转 120°/240° 求平均 -> 严格三重对称（参考图本身有 ~5% 噪声）
  3. 阈值 0.5 取连通域：母题剪影取含中心的那块；单叶取质心最靠上的一片
  4. crack-follow 精确追踪二值边界 -> 沿法向吸附到 F=0.5 亚像素等值线
  5. Chaikin 切角 x3 -> 按弧长均匀重采样 -> 截断阶数的闭合傅里叶拟合
  6. 归一化（面积质心为原点，剪影最大半径 = 1.0），自检 IoU/面积，写出
     tools/icon_template.py（只存 ±K 阶系数，约 1KB）

运行：C:/Python314/python.exe tools/fit_icon_geometry.py
"""
import base64
import json
import math
import os
import sys
from collections import deque

import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REF = os.path.join(os.path.expanduser("~"), ".workbuddy", "clipboard-images",
                   "clipboard-2026-09-26T08-08-59-795Z-fbf98604.png")
TMP = os.path.join(ROOT, ".workbuddy", "tmp")
DOCS = os.path.join(ROOT, "docs", "img")

# ---- 参考图实测几何 ----
CX, CY = 67.9, 45.9          # 徽章圆心（源图像素）
ROI_R = 29.0                 # 母题最远 ~28.5px；徽章半径 29.34，外面是截图背景
BUCKET = (BADGE_R := 29.34)  # 徽章半径（仅用于归一化参考）

# ---- 三簇代表色（实测均值） ----
M_GOLD = np.array([237.5, 204.2, 129.5])
M_BROWN = np.array([132.7, 82.6, 65.8])
M_BG = np.array([210.8, 195.0, 187.9])
SIG = 35.0

N_RESAMPLE = 512
CHAINKIN = 3
KS_CAND = (8, 10, 12, 14, 16, 18, 20, 24, 28)
DEV_TOL = 0.02               # px（源图尺度）最大偏差容限
LEAF_ROT = (0.0, 120.0, 240.0)   # 三片叶的旋转角（度）
CAL_SS = 8                   # 面积校准时的超采样倍率


# ------------------------------------------------------------------ 采样
def bilinear(F, X, Y, dx=0.5, dy=0.5):
    """按「像素角点」坐标 (X,Y) 双线性采样 F（数组索引 = (X-dx, Y-dy)）"""
    H, W = F.shape
    u = np.clip(X - dx, 0, W - 1.001)
    v = np.clip(Y - dy, 0, H - 1.001)
    x0 = np.floor(u).astype(np.int32)
    y0 = np.floor(v).astype(np.int32)
    fx = u - x0
    fy = v - y0
    return (F[y0, x0] * (1 - fx) * (1 - fy) + F[y0, x0 + 1] * fx * (1 - fy) +
            F[y0 + 1, x0] * (1 - fx) * fy + F[y0 + 1, x0 + 1] * fx * fy)


def rot_field(F, xx, yy, ang_deg):
    th = math.radians(ang_deg)
    ct, st = math.cos(th), math.sin(th)
    dx, dy = xx - CX, yy - CY
    return bilinear(F, CX + dx * ct - dy * st, CY + dx * st + dy * ct, dx=0.0, dy=0.0)


def rot_about(F, cx, cy, ang_deg):
    """绕任意点旋转采样（双线性，输入输出均为像素角点坐标网格）"""
    H, W = F.shape
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float64) + 0.5
    th = math.radians(ang_deg)
    ct, st = math.cos(th), math.sin(th)
    dx, dy = xx - cx, yy - cy
    return bilinear(F, cx + dx * ct - dy * st, cy + dx * st + dy * ct, dx=0.0, dy=0.0)


def find_rotation_center(fields, guess=(CX, CY), span=2.0, step=0.1):
    """搜索使「三重旋转自相似残差」最小的旋转中心"""
    gx, gy = guess
    xs = np.arange(gx - span, gx + span + 1e-9, step)
    ys = np.arange(gy - span, gy + span + 1e-9, step)
    best = (None, None, 1e18)
    for dy in ys:
        for dx in xs:
            cost = 0.0
            for F in fields:
                r1 = rot_about(F, dx, dy, 120.0)
                r2 = rot_about(F, dx, dy, 240.0)
                cost += float(((F - r1) ** 2).sum() + ((F - r2) ** 2).sum())
            if cost < best[2]:
                best = (dx, dy, cost)
    # 精搜
    cx0, cy0, _ = best
    for dy in np.arange(cy0 - step, cy0 + step + 1e-9, step / 10):
        for dx in np.arange(cx0 - step, cx0 + step + 1e-9, step / 10):
            cost = 0.0
            for F in fields:
                r1 = rot_about(F, dx, dy, 120.0)
                r2 = rot_about(F, dx, dy, 240.0)
                cost += float(((F - r1) ** 2).sum() + ((F - r2) ** 2).sum())
            if cost < best[2]:
                best = (dx, dy, cost)
    return best[0], best[1]


# ------------------------------------------------------------------ 软分类
def load_soft():
    a = np.asarray(Image.open(REF).convert("RGB"), dtype=np.float32)
    H, W, _ = a.shape
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    rr = np.hypot(xx - CX, yy - CY)
    w = [np.exp(-((a - m) ** 2).sum(axis=2) / (2 * SIG * SIG))
         for m in (M_GOLD, M_BROWN, M_BG)]
    tot = w[0] + w[1] + w[2] + 1e-12
    roi = rr <= ROI_R
    gold = np.where(roi, w[0] / tot, 0.0)
    motif = np.where(roi, (w[0] + w[1]) / tot, 0.0)
    return gold, motif, xx, yy


# ------------------------------------------------------------------ 连通域
def components(mask, minsize=5):
    H, W = mask.shape
    lab = np.zeros((H, W), np.int32)
    nb = [(-1, 0), (1, 0), (0, -1), (0, 1), (-1, -1), (-1, 1), (1, -1), (1, 1)]
    out, cur = [], 0
    for y in range(H):
        for x in range(W):
            if mask[y, x] and lab[y, x] == 0:
                cur += 1
                lab[y, x] = cur
                q = deque([(y, x)])
                ys, xs = [], []
                while q:
                    py, px = q.popleft()
                    ys.append(py)
                    xs.append(px)
                    for dy, dx in nb:
                        ny, nx = py + dy, px + dx
                        if 0 <= ny < H and 0 <= nx < W and mask[ny, nx] and lab[ny, nx] == 0:
                            lab[ny, nx] = cur
                            q.append((ny, nx))
                if len(ys) >= minsize:
                    out.append(dict(label=cur, n=len(ys), cy=float(np.mean(ys)),
                                    cx=float(np.mean(xs)), y0=min(ys), y1=max(ys),
                                    x0=min(xs), x1=max(xs)))
    out.sort(key=lambda d: -d["n"])
    return lab, out


# ------------------------------------------------------------------ 边界追踪
def crack_contour(mask, y0, x0, y1, x1):
    """crack-follow：前景置于行进方向右侧，返回有序「像素角点」序列"""
    sub = mask[y0:y1 + 1, x0:x1 + 1]
    H, W = sub.shape
    seg = {}
    use_outer = False
    for y in range(H):
        for x in range(W):
            if not sub[y, x]:
                continue
            if y == 0 or not sub[y - 1, x]:
                seg[(x, y)] = (x + 1, y)
            if x == W - 1 or not sub[y, x + 1]:
                seg[(x + 1, y)] = (x + 1, y + 1)
            if y == H - 1 or not sub[y + 1, x]:
                seg[(x + 1, y + 1)] = (x, y + 1)
            if x == 0 or not sub[y, x - 1]:
                seg[(x, y + 1)] = (x, y)
    # 取「净转向为正」的那条环 = 外边界（前景在右）；若只剩内边界则直接用
    start = min(seg.keys(), key=lambda p: (p[1], p[0]))
    loop, cur, guard = [start], start, 0
    while True:
        nxt = seg.get(cur)
        if nxt is None:
            raise RuntimeError("crack 断链")
        if nxt == start:
            break
        loop.append(nxt)
        cur = nxt
        guard += 1
        if guard > 500000:
            raise RuntimeError("crack 未闭合")
    p = np.array(loop, dtype=np.float64)
    p[:, 0] += x0
    p[:, 1] += y0
    return p


def signed_area(P):
    x, y = P[:, 0], P[:, 1]
    return 0.5 * float((x * np.roll(y, -1) - np.roll(x, -1) * y).sum())


def refine_subpixel(P, F, step=0.05, span=1.7):
    """把折线顶点沿局部法向吸附到 F = 0.5 的等值线上"""
    tg = np.roll(P, -1, axis=0) - np.roll(P, 1, axis=0)
    L = np.hypot(tg[:, 0], tg[:, 1])
    L[L == 0] = 1.0
    tg = tg / L[:, None]
    nrm = np.stack([tg[:, 1], -tg[:, 0]], axis=1)
    ts = np.arange(-span, span + 1e-9, step)
    out = P.copy()
    for i in range(len(P)):
        pts = P[i][None, :] + ts[:, None] * nrm[i][None, :]
        v = bilinear(F, pts[:, 0], pts[:, 1]) - 0.5
        s = np.sign(v)
        idx = np.nonzero(s[:-1] * s[1:] < 0)[0]
        if len(idx) == 0:
            continue
        # 取离顶点最近的那次穿越
        j = idx[np.argmin(np.abs(ts[idx]))]
        t0, t1 = ts[j], ts[j + 1]
        v0, v1 = v[j], v[j + 1]
        t = t0 if v1 == v0 else t0 + (t1 - t0) * (-v0) / (v1 - v0)
        if abs(t) <= span:
            out[i] = P[i] + t * nrm[i]
    return out


def chaikin(P, n=3):
    for _ in range(n):
        nxt = np.roll(P, -1, axis=0)
        q = np.empty((len(P) * 2, 2))
        q[0::2] = 0.75 * P + 0.25 * nxt
        q[1::2] = 0.25 * P + 0.75 * nxt
        P = q
    return P


def resample_closed(P, n):
    d = np.roll(P, -1, axis=0) - P
    sl = np.hypot(d[:, 0], d[:, 1])
    s = np.concatenate([[0.0], np.cumsum(sl)])
    t = np.linspace(0.0, s[-1], n, endpoint=False)
    idx = np.clip(np.searchsorted(s, t, side="right") - 1, 0, len(sl) - 1)
    f = (t - s[idx]) / np.maximum(sl[idx], 1e-12)
    return P[idx] + d[idx] * f[:, None]


def poly_perimeter(P):
    d = np.roll(P, -1, axis=0) - P
    return float(np.hypot(d[:, 0], d[:, 1]).sum())


def offset_closed(P, d):
    """把闭合折线沿外法向整体偏移 d（正数=面积变大）"""
    tg = np.roll(P, -1, axis=0) - np.roll(P, 1, axis=0)
    L = np.hypot(tg[:, 0], tg[:, 1])
    L[L == 0] = 1.0
    tg = tg / L[:, None]
    n = np.stack([tg[:, 1], -tg[:, 0]], axis=1)
    if abs(signed_area(P + 0.1 * n)) < abs(signed_area(P)):
        n = -n
    return P + d * n


# ------------------------------------------------------------------ 傅里叶
def fourier_coeffs(P, K):
    N = len(P)
    z = P[:, 0] + 1j * P[:, 1]
    Fk = np.fft.fft(z) / N
    ks = np.arange(-K, K + 1)
    return Fk[ks % N], ks


def fourier_eval(C, ks, M, rot_deg=0.0, shift=0.0):
    t = np.arange(M, dtype=np.float64) / M + shift
    z = (C[:, None] * np.exp(2j * np.pi * np.outer(ks, t))).sum(axis=0)
    P = np.stack([z.real, z.imag], axis=1)
    if rot_deg:
        th = math.radians(rot_deg)
        ct, st = math.cos(th), math.sin(th)
        P = np.stack([P[:, 0] * ct - P[:, 1] * st, P[:, 0] * st + P[:, 1] * ct], axis=1)
    return P


def hausdorff_dev(C, ks, P_ref, M=4096):
    """双向 Hausdorff：曲线采样点 -> 参考折线，以及参考折线 -> 曲线采样点"""
    q = fourier_eval(C, ks, M)

    def directed(A, B, chunk=256):
        worst = 0.0
        for i in range(0, len(A), chunk):
            blk = A[i:i + chunk]
            d = np.sqrt(((blk[:, None, :] - B[None, :, :]) ** 2).sum(axis=2))
            worst = max(worst, float(d.min(axis=1).max()))
        return worst

    return max(directed(q, P_ref), directed(P_ref, q))


# ------------------------------------------------------------------ 等值线
_MS_CASES = {
    1: [("L", "T")], 2: [("T", "R")], 3: [("L", "R")], 4: [("R", "B")],
    5: [("L", "T"), ("R", "B")], 6: [("T", "B")], 7: [("L", "B")],
    8: [("B", "L")], 9: [("B", "T")], 10: [("T", "R"), ("B", "L")],
    11: [("B", "R")], 12: [("R", "L")], 13: [("R", "T")], 14: [("T", "L")],
}


def marching_squares(F, level=0.5):
    """在软场上取 level 等值线，返回有序闭合折线列表（像素角点坐标）"""
    H, W = F.shape
    a = F[:-1, :-1]
    b = F[:-1, 1:]
    c = F[1:, 1:]
    d = F[1:, :-1]
    code = ((a > level) * 1 + (b > level) * 2 + (c > level) * 4 + (d > level) * 8)

    def ip(v0, v1):
        dv = v1 - v0
        return np.where(np.abs(dv) < 1e-12, 0.5, (level - v0) / np.where(dv == 0, 1e-12, dv))

    ys, xs = np.nonzero((code > 0) & (code < 15))
    P = {"T": np.stack([xs + ip(a[ys, xs], b[ys, xs]), ys + 0.0], 1),
         "R": np.stack([xs + 1.0, ys + ip(b[ys, xs], c[ys, xs])], 1),
         "B": np.stack([xs + ip(d[ys, xs], c[ys, xs]), ys + 1.0], 1),
         "L": np.stack([xs + 0.0, ys + ip(a[ys, xs], d[ys, xs])], 1)}

    segs = {}
    for k, (yi, xi) in enumerate(zip(ys, xs)):
        for p1, p2 in _MS_CASES.get(int(code[yi, xi]), []):
            q1 = tuple(np.round(P[p1][k], 7))
            q2 = tuple(np.round(P[p2][k], 7))
            segs.setdefault(q1, []).append(q2)
            segs.setdefault(q2, []).append(q1)

    loops, used = [], set()
    for s0 in list(segs.keys()):
        if s0 in used:
            continue
        loop = [s0]
        used.add(s0)
        prev, cur = None, s0
        while True:
            nxts = [q for q in segs[cur] if q != prev and q not in used]
            if not nxts:
                break
            prev, cur = cur, nxts[0]
            used.add(cur)
            if cur == s0:
                break
            loop.append(cur)
        if len(loop) >= 8:
            loops.append(np.array(loop, dtype=np.float64))
    return loops


# ------------------------------------------------------------------ 主流程
def main():
    gold, motif, xx, yy = load_soft()

    # 先找母题自身的旋转中心（徽章圆心未必是它的旋转中心）
    cxc, cyc = find_rotation_center([gold, motif])
    print(f"旋转中心 = ({cxc:.2f},{cyc:.2f})（徽章圆心 {CX:.2f},{CY:.2f}，"
          f"偏移 {math.hypot(cxc-CX, cyc-CY):.2f}px）")

    g_sym = (gold + rot_about(gold, cxc, cyc, 120) + rot_about(gold, cxc, cyc, 240)) / 3.0
    m_sym = (motif + rot_about(motif, cxc, cyc, 120) + rot_about(motif, cxc, cyc, 240)) / 3.0

    # ---------- 剪影 ----------
    lab, comps = components(m_sym > 0.5, 8)
    sil_c = max(comps, key=lambda t: t["n"] - 4 * math.hypot(t["cx"] - cxc, t["cy"] - cyc))
    print(f"剪影分量: n={sil_c['n']} bbox x[{sil_c['x0']},{sil_c['x1']}] y[{sil_c['y0']},{sil_c['y1']}]")
    sub = m_sym[max(sil_c["y0"] - 2, 0):sil_c["y1"] + 3, max(sil_c["x0"] - 2, 0):sil_c["x1"] + 3]
    oy, ox = max(sil_c["y0"] - 2, 0), max(sil_c["x0"] - 2, 0)
    loops = marching_squares(sub)
    loops = [L + np.array([ox, oy]) for L in loops]
    sil_ref = max(loops, key=lambda L: abs(signed_area(L)))
    print(f"  剪影等值线 {len(loops)} 条，取最长 {len(sil_ref)} 点，面积 {abs(signed_area(sil_ref)):.1f}px²")
    if signed_area(sil_ref) < 0:
        sil_ref = sil_ref[::-1]

    # ---------- 单叶 ----------
    labg, compsg = components(g_sym > 0.5, 8)
    for c in compsg[:5]:
        print(f"  金色分量 n={c['n']:4d} 质心=({c['cx']:.1f},{c['cy']:.1f}) "
              f"d中心={math.hypot(c['cx']-cxc, c['cy']-cyc):.1f}")
    leaves = [c for c in compsg if c["n"] > 20 and math.hypot(c["cx"] - cxc, c["cy"] - cyc) > 6]
    leaf_c = min(leaves, key=lambda t: t["cy"])
    print(f"主叶: n={leaf_c['n']} 质心=({leaf_c['cx']:.1f},{leaf_c['cy']:.1f})")
    m = (labg == leaf_c["label"])
    md = m.copy()
    for _ in range(2):                                    # 膨胀 1~2 px，保留边界过渡
        md = md | np.roll(md, 1, 0) | np.roll(md, -1, 0) | np.roll(md, 1, 1) | np.roll(md, -1, 1)
    glf = g_sym * md
    y0, y1 = leaf_c["y0"] - 3, leaf_c["y1"] + 3
    x0, x1 = leaf_c["x0"] - 3, leaf_c["x1"] + 3
    loops = marching_squares(glf[y0:y1 + 1, x0:x1 + 1])
    loops = [L + np.array([x0, y0]) for L in loops]
    leaf_ref = max(loops, key=lambda L: abs(signed_area(L)))
    print(f"  主叶等值线 {len(loops)} 条，取最长 {len(leaf_ref)} 点，面积 {abs(signed_area(leaf_ref)):.1f}px²")
    if signed_area(leaf_ref) < 0:
        leaf_ref = leaf_ref[::-1]

    # ---------- 圆点半径（沿射线找 0.5 穿越，取中位数） ----------
    rs = []
    for k in range(720):
        th = 2 * math.pi * k / 720
        tt = np.arange(0.4, 8.0, 0.02)
        v = bilinear(g_sym, CX + tt * math.cos(th), CY + tt * math.sin(th)) - 0.5
        idx = np.nonzero(np.sign(v[:-1]) * np.sign(v[1:]) < 0)[0]
        if len(idx):
            j = idx[0]
            t0, t1, v0, v1 = tt[j], tt[j + 1], v[j], v[j + 1]
            rs.append(t0 + (t1 - t0) * (-v0) / (v1 - v0))
    dot_r = float(np.median(rs))
    print(f"圆点半径 {dot_r:.3f}px（{len(rs)} 条射线，散布 {np.std(rs):.3f}）")

    # ---------- 面积校准：对齐「参考图渲染出的金/褐像素数」 ----------
    X0, Y0 = int(CX - 31), int(CY - 31)
    X1, Y1 = X0 + 64, Y0 + 64
    _ref = np.asarray(Image.open(REF).convert("RGB"), dtype=np.float32)[Y0:Y1, X0:X1]
    _r, _g, _b = _ref[:, :, 0], _ref[:, :, 1], _ref[:, :, 2]
    _lum = .299 * _r + .587 * _g + .114 * _b
    _yg = (_r + _g) / 2 - _b
    _yy, _xx = np.mgrid[Y0:Y1, X0:X1].astype(np.float32)
    _rr = np.hypot(_xx - CX, _yy - CY)
    rg_ref = (_yg > 70) & (_lum > 150) & (_rr <= ROI_R)
    base_ref = (rg_ref | (_lum < 165)) & (_rr <= ROI_R)
    gold_tgt, brown_tgt = int(rg_ref.sum()), int((base_ref & ~rg_ref).sum())
    print(f"参考面积目标：金 {gold_tgt}px^2  褐 {brown_tgt}px^2")

    def render_counts(P_sil, P_leaf, org_, dot_r_):
        im = Image.new("L", ((X1 - X0) * CAL_SS, (Y1 - Y0) * CAL_SS), 0)
        dd = ImageDraw.Draw(im)

        def put(P, col):
            dd.polygon([((p[0] - X0) * CAL_SS, (p[1] - Y0) * CAL_SS) for p in P], fill=col)

        put(P_sil, 128)
        for rot in LEAF_ROT:
            th = math.radians(rot)
            ct, st = math.cos(th), math.sin(th)
            Q = P_leaf - org_
            Q = np.stack([Q[:, 0] * ct - Q[:, 1] * st, Q[:, 0] * st + Q[:, 1] * ct], 1) + org_
            put(Q, 255)
        dd.ellipse([(org_[0] - X0 - dot_r_) * CAL_SS, (org_[1] - Y0 - dot_r_) * CAL_SS,
                    (org_[0] - X0 + dot_r_) * CAL_SS, (org_[1] - Y0 + dot_r_) * CAL_SS], fill=255)
        a = np.asarray(im.resize((X1 - X0, Y1 - Y0), Image.BOX))
        lab = np.where(a > 192, 1, np.where(a > 64, 2, 0))
        return int((lab == 1).sum()), int((lab == 2).sum())

    def area_centroid(P):
        x, y = P[:, 0], P[:, 1]
        x2, y2 = np.roll(x, -1), np.roll(y, -1)
        cr = x * y2 - x2 * y
        A = cr.sum() / 2.0
        return np.array([((x + x2) * cr).sum(), ((y + y2) * cr).sum()]) / (6 * A), abs(A)

    d_sil = d_leaf = 0.0
    for it in range(7):
        P_sil, P_leaf = offset_closed(sil_ref, d_sil), offset_closed(leaf_ref, d_leaf)
        org_c, _ = area_centroid(P_sil)
        gc, bc = render_counts(P_sil, P_leaf, org_c, dot_r)
        print(f"  校准 {it}: 偏移(剪影 {d_sil:+.3f}, 叶 {d_leaf:+.3f})px -> "
              f"金 {gc}/{gold_tgt}  褐 {bc}/{brown_tgt}")
        if abs(gc - gold_tgt) < 3 and abs(bc - brown_tgt) < 3:
            break
        d_leaf += (gold_tgt - gc) / max(3 * poly_perimeter(P_leaf), 1.0)
        d_sil += (brown_tgt - bc) / max(poly_perimeter(P_sil), 1.0)
    sil_ref, leaf_ref = offset_closed(sil_ref, d_sil), offset_closed(leaf_ref, d_leaf)

    # ---------- 归一化 ----------
    org, area_sil = area_centroid(sil_ref)
    sil0 = sil_ref - org
    S = 1.0 / np.hypot(sil0[:, 0], sil0[:, 1]).max()
    sil_n, leaf_n = sil0 * S, (leaf_ref - org) * S
    print(f"原点(质心) = ({org[0]:.2f},{org[1]:.2f})  剪影最大半径 = {1/S:.3f}px  "
          f"面积 = {area_sil:.1f}px^2")

    # ---------- 傅里叶拟合 ----------
    curves = {}
    for name, pts in (("sil", sil_n), ("leaf", leaf_n)):
        dense = resample_closed(pts, 4096)
        pick = None
        for K in KS_CAND:
            C, ks = fourier_coeffs(pts, K)
            dev = hausdorff_dev(C, ks, dense)
            print(f"   {name:4s} K={K:2d} 最大偏差 {dev:.4f}px")
            if pick is None and dev <= DEV_TOL:
                pick = (K, C, ks, dev)
        if pick is None:
            K = KS_CAND[-1]
            C, ks = fourier_coeffs(pts, K)
            pick = (K, C, ks, hausdorff_dev(C, ks, dense))
        curves[name] = pick
        print(f"   -> {name} 定稿 K={pick[0]} 偏差 {pick[3]:.4f}px")

    # ---------- 自检：并排 + 叠加 ----------
    selfcheck(curves, org, S, dot_r, m_sym, g_sym)

    # ---------- 写出 icon_template.py ----------
    write_template(curves, float(dot_r * S))
    json.dump(dict(origin=[float(org[0]), float(org[1])],
                   r_sil_px=float(1 / S), dot_r_px=float(dot_r),
                   dot_r_norm=float(dot_r * S),
                   sil_K=int(curves["sil"][0]), leaf_K=int(curves["leaf"][0]),
                   sil_coeffs=np.stack([curves["sil"][1].real, curves["sil"][1].imag], 1).tolist(),
                   leaf_coeffs=np.stack([curves["leaf"][1].real, curves["leaf"][1].imag], 1).tolist()),
              open(os.path.join(TMP, "v22_shapes.json"), "w"), indent=1)


def selfcheck(curves, org, S, dot_r, m_sym, g_sym):
    """把结果按源图尺度画回，与参考标签比对"""
    Ks, Cs = curves["sil"][0], curves["sil"][1]
    Kl, Cl = curves["leaf"][0], curves["leaf"][1]
    ksS, ksL = curves["sil"][2], curves["leaf"][2]
    SS = 8
    x0, y0 = int(CX - ROI_R - 2), int(CY - ROI_R - 2)
    x1, y1 = int(CX + ROI_R + 2), int(CY + ROI_R + 2)
    W, H = x1 - x0, y1 - y0
    img = Image.new("L", (W * SS, H * SS), 0)
    dr = ImageDraw.Draw(img)

    def to_px(P):
        return [((p[0] + org[0] - x0) * SS, (p[1] + org[1] - y0) * SS) for p in P]

    dr.polygon(to_px(fourier_eval(Cs, ksS, 3000) / S), fill=128)
    for rot in (0.0, 120.0, 240.0):
        dr.polygon(to_px(fourier_eval(Cl, ksL, 3000, rot_deg=rot) / S), fill=255)
    dr.ellipse([(CX - x0 - dot_r) * SS, (CY - y0 - dot_r) * SS,
                (CX - x0 + dot_r) * SS, (CY - y0 + dot_r) * SS], fill=255)
    fit = np.asarray(img.resize((W, H), Image.NEAREST))
    fb, fg = fit > 0, fit == 255

    a = np.asarray(Image.open(REF).convert("RGB"), dtype=np.float32)[y0:y1, x0:x1]
    R, G, B = a[:, :, 0], a[:, :, 1], a[:, :, 2]
    lum = .299 * R + .587 * G + .114 * B
    yg = (R + G) / 2 - B
    yy, xx = np.mgrid[y0:y1, x0:x1].astype(np.float32)
    rr = np.hypot(xx - CX, yy - CY)
    rg = (yg > 70) & (lum > 150) & (rr <= ROI_R)
    rgb_ = rg | ((lum < 165) & (rr <= ROI_R))

    iou = lambda p, q: (p & q).sum() / max((p | q).sum(), 1)

    def cen(m):
        ys, xs = np.nonzero(m)
        return (xs.mean() + x0, ys.mean() + y0) if len(xs) else (0.0, 0.0)

    # 整体平移对图标无意义（图标会重新居中），先对齐质心再评 IoU
    best = None
    for dy in range(-2, 3):
        for dx in range(-2, 3):
            sb = np.roll(np.roll(fb, dy, 0), dx, 1)
            sg = np.roll(np.roll(fg, dy, 0), dx, 1)
            sc = iou(sb, rgb_) + iou(sg, rg)
            if best is None or sc > best[0]:
                best = (sc, dy, dx, sb, sg)
    _, sdy, sdx, fb_a, fg_a = best

    print("\n=== 自检（源图尺度逐像素）===")
    print(f"  质心 剪影 拟合{cen(fb)} 参考{cen(rgb_)}   金色 拟合{cen(fg)} 参考{cen(rg)}")
    print(f"  最优对齐平移 = ({sdx:+d},{sdy:+d})px")
    print(f"  剪影 IoU {iou(fb_a, rgb_):.4f}（未对齐 {iou(fb, rgb_):.4f}）  面积比 {fb.sum()/rgb_.sum():.3f}")
    print(f"  金色 IoU {iou(fg_a, rg):.4f}（未对齐 {iou(fg, rg):.4f}）  面积比 {fg.sum()/rg.sum():.3f}")
    print(f"  褐色 IoU {iou(fb_a & ~fg_a, rgb_ & ~rg):.4f}  面积比 "
          f"{(fb & ~fg).sum()/max((rgb_ & ~rg).sum(),1):.3f}")
    print(f"  金/褐 比 参考 {rg.sum()/max((rgb_ & ~rg).sum(),1):.3f}  "
          f"拟合 {fg.sum()/max((fb & ~fg).sum(),1):.3f}")

    K = 6
    for name, ref, fitm in (("base", rgb_, fb_a), ("gold", rg, fg_a)):
        ov = np.zeros((H * K, W * K, 3), np.uint8)
        ov[:, :, 0] = np.asarray(Image.fromarray((ref * 255).astype(np.uint8))
                                 .resize((W * K, H * K), Image.NEAREST))
        ov[:, :, 1] = np.asarray(Image.fromarray((fitm * 255).astype(np.uint8))
                                 .resize((W * K, H * K), Image.NEAREST))
        Image.fromarray(ov).save(os.path.join(DOCS, f"_diag_v22_overlay_{name}.png"))


def write_template(curves, dot_r_norm):
    def pack(key):
        C = curves[key][1]
        arr = np.stack([C.real, C.imag], 1).astype("<f4")
        return base64.b64encode(arr.tobytes()).decode()

    src = '''# -*- coding: utf-8 -*-
"""应用图标母题的解析几何（由 tools/fit_icon_geometry.py 自动生成，请勿手改）。

来源：官方「七圣召唤」徽章左侧三叶交织徽记，抽象重绘。
表示法：每条闭合曲线 = 有限项闭合傅里叶级数 p(t) = Σ c_k e^{2πi k t}，
        天然闭合、C∞ 光滑、严格三重对称。
坐标系：原点 = 母题剪影的面积质心（= 三重旋转中心），y 轴向下（图像坐标），
        单位 = 剪影最大半径（即 |p| 的最大值 = 1.0）。
"""
import math

import numpy as np

SIL_K = %(silK)d
LEAF_K = %(leafK)d
SIL_N = 2 * SIL_K + 1
LEAF_N = 2 * LEAF_K + 1
SIL_KS = np.arange(-SIL_K, SIL_K + 1)
LEAF_KS = np.arange(-LEAF_K, LEAF_K + 1)

# 母题剪影（金 ∪ 褐 的外轮廓）
_SIL_B64 = "%(sil)s"
# 单片月牙金叶（另外两片由 +120° / +240° 旋转得到）
_LEAF_B64 = "%(leaf)s"

LEAF_ROT = (0.0, 120.0, 240.0)   # 三片叶的旋转角（度）
DOT_R = %(dotr).6f               # 中央金点半径（归一化单位）


def _unpack(s, n):
    raw = np.frombuffer(__import__("base64").b64decode(s), dtype="<f4")
    return (raw[0::2] + 1j * raw[1::2]).astype(np.complex128)[:n]


SIL_COEFF = _unpack(_SIL_B64, SIL_N)
LEAF_COEFF = _unpack(_LEAF_B64, LEAF_N)


def curve(coeff, ks, m, rot_deg=0.0):
    """按弧长参数 t∈[0,1) 采样闭合曲线，返回 (m,2) 数组"""
    t = np.arange(m, dtype=np.float64) / m
    z = (coeff[:, None] * np.exp(2j * np.pi * np.outer(ks, t))).sum(axis=0)
    P = np.stack([z.real, z.imag], axis=1)
    if rot_deg:
        th = math.radians(rot_deg)
        ct, st = math.cos(th), math.sin(th)
        P = np.stack([P[:, 0] * ct - P[:, 1] * st,
                      P[:, 0] * st + P[:, 1] * ct], axis=1)
    return P


def sil_poly(m=512):
    return curve(SIL_COEFF, SIL_KS, m)


def leaf_poly(m=512, rot_deg=0.0):
    return curve(LEAF_COEFF, LEAF_KS, m, rot_deg)


def leaf_polys(m=512):
    return [curve(LEAF_COEFF, LEAF_KS, m, r) for r in LEAF_ROT]
''' % dict(silK=curves["sil"][0], leafK=curves["leaf"][0],
           sil=pack("sil"), leaf=pack("leaf"), dotr=dot_r_norm)
    path = os.path.join(ROOT, "tools", "icon_template.py")
    open(path, "w", encoding="utf-8").write(src)
    print(f"\n已写出 {path}  ({len(src)} 字节)")


if __name__ == "__main__":
    main()
