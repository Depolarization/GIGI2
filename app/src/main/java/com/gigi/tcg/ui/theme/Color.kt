package com.gigi.tcg.ui.theme

import androidx.compose.ui.graphics.Color

// 暗色档深绿：深色 surface(0xFF1C1B1F) 上过 WCAG AA（实测 4.83:1，旧值 0xFF3F8F5F 仅 4.33:1）。
// 全部语义色仅作文字/图标前景色，无一处作背景块，提亮只升对比度。
// HSL 色相 144°、饱和度 38.8% 与旧值完全一致，只提亮度（L 40.4%→42.9%），观感保持。
val WinColor = Color(0xFF439865)
// 暗色档深红：深色 surface(0xFF1C1B1F) 上过 WCAG AA（实测 4.83:1，旧值 0xFFC25656 仅 3.88:1）。
// HSL 色相 0°、饱和度 46.7% 与旧值一致，只提亮度（L 54.9%→61.0%），观感保持。
val LoseColor = Color(0xFFCA6D6D)
// 暗色档金（tokens.css --color-gold，排行榜前三固定色也用它，勿改值）
val GoldColor = Color(0xFFD4A643)
// 亮色档深金：亮底 surface 上 WCAG AA（对白底对比度约 5.1:1），仅供语义色按主题取用
val GoldColorLight = Color(0xFF8A6A16)
// 亮色档深绿：亮底 surface 上 WCAG AA（对白底对比度约 5.0:1），仅供语义色按主题取用
val WinColorLight = Color(0xFF2E7D4F)
// 亮色档深红：亮底 surface 上 WCAG AA（对白底对比度约 5.1:1），仅供语义色按主题取用
val LoseColorLight = Color(0xFFB84A4A)
