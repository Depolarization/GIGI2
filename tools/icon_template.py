# -*- coding: utf-8 -*-
"""应用图标母题的解析几何（由 tools/fit_icon_geometry.py 自动生成，请勿手改）。

来源：官方「七圣召唤」徽章左侧三叶交织徽记，抽象重绘。
表示法：每条闭合曲线 = 有限项闭合傅里叶级数 p(t) = Σ c_k e^{2πi k t}，
        天然闭合、C∞ 光滑、严格三重对称。
坐标系：原点 = 母题剪影的面积质心（= 三重旋转中心），y 轴向下（图像坐标），
        单位 = 剪影最大半径（即 |p| 的最大值 = 1.0）。
"""
import math

import numpy as np

SIL_K = 24
LEAF_K = 10
SIL_N = 2 * SIL_K + 1
LEAF_N = 2 * LEAF_K + 1
SIL_KS = np.arange(-SIL_K, SIL_K + 1)
LEAF_KS = np.arange(-LEAF_K, LEAF_K + 1)

# 母题剪影（金 ∪ 褐 的外轮廓）
_SIL_B64 = "znkAORj1U7pq9w67oGrsus8qpjlTlbg5ebg7OK2iJzm8ojK6VHOuOr1hqbneHKu6PIEOuoQnu7kzxlY764yBOe3r7Tl+iIo6p/RqOom2SrgvQ/O6nicKu4GoBTu6G6q6cUE5OVC7u7r+K2q7Fj/tOtYeSDmhzh66EfJFO8/HoLoEHkw8CcGWu9pLPDrQujS7CnU+uTXUCLtn9a07qM6POqOLhztOFRq7hAepOpKCLDtZbeK9gF1QPf0ntTgdrQ28aHwHOzMRwDs+6MC+7awsvyasgTtSDsm7BXsNPGqSDjwM2UY9/KwbvVWcpDnjvMi6ei20uj/XkDvI5ki60QE2PDXvFznifVw7h7aEOtiU/rlrVMy6HDwLPFmGnzodXq45wYiGOgC04DkTuhY77Pcsu/zkPTsilwo6JTHaOohAvTnSdoq6gN8Pu97vQbqri9g54zsiuVjrPjq0Frg6u79CO8VMBDoCRwY6SlQcONd6SLodByI6WEGWupqDADlwc7K5X/FVuZTvLDo="
# 单片月牙金叶（另外两片由 +120° / +240° 旋转得到）
_LEAF_B64 = "weu/uiguiLv5VKs53MbZuY4RZzlM7QY7Js2cOQZol7ukV5Y6QLT0OnqPZTu3qhu5shzZO+K9PjyttQW9dlILPbtHvjv7u5a9Ypd9vY08OT6Z5om+6oKlvsaP5r3jYZ2+GxO6vcKDrL1oX908q1Szu3FJwTwn2FQ7gcKovM/nZrsTZ4c790GLufjlDjzU8Fk7waPNuyJgCbnBsRS7dCgkO4GBTjp4oZg6"

LEAF_ROT = (0.0, 120.0, 240.0)   # 三片叶的旋转角（度）
DOT_R = 0.119351               # 中央金点半径（归一化单位）


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
