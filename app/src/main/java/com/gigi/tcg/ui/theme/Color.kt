package com.gigi.tcg.ui.theme

import androidx.compose.ui.graphics.Color

// 暗色档深绿：深色 Card 容器(0xFF36343B) 上过 WCAG AA（实测 4.63:1，旧值 0xFF439865 仅 3.46:1；
// 对 dark surface 0xFF1C1B1F 达 6.47:1）。
// 全部语义色仅作文字/图标前景色，无一处作背景块，提亮只升对比度。
// HSL 色相约 144°不变、只提亮度（观感保持深绿），亮度受 AA 下限约束。
val WinColor = Color(0xFF58B07E)
// 暗色档深红：深色 Card 容器(0xFF36343B) 上过 WCAG AA（实测 4.58:1，旧值 0xFFCA6D6D 仅 3.46:1；
// 对 dark surface 0xFF1C1B1F 达 6.39:1）。
// HSL 色相 0°、纯红（R>G=B）不变，只提亮度，观感保持深红。
val LoseColor = Color(0xFFE88080)
// 暗色档金（tokens.css --color-gold，排行榜前三固定色也用它，勿改值）
val GoldColor = Color(0xFFD4A643)
// 亮色档深金：亮底 surface 上 WCAG AA（对白底对比度约 5.1:1），仅供语义色按主题取用
val GoldColorLight = Color(0xFF8A6A16)
// 亮色档深绿：亮底 surface 上 WCAG AA（对白底对比度约 5.0:1），仅供语义色按主题取用
val WinColorLight = Color(0xFF2E7D4F)
// 亮色档深红：亮底 surface 上 WCAG AA（对白底对比度约 5.1:1），仅供语义色按主题取用
val LoseColorLight = Color(0xFFB84A4A)
