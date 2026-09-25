# V6I-A-CONTRAST — 语义色浅色档对比度修复

status: done

## 任务
- 背景：`WinColor (0xFF3F8F5F)` 对白底 3.96:1、`LoseColor (0xFFC25656)` 4.41:1，均低于 WCAG AA 正文阈值 4.5:1。
- 方案：按 gold 既有范式，`GigiTheme` 中 win/lose/gold 三项均按 darkTheme 取色；Color.kt 新增亮色档常量。
- 改前闸门：`git diff --quiet` on Color.kt / Theme.kt → exit 0 ✅ 放行。

## 改动
1. **Color.kt**（只新增，旧值一字未改）：
   - `WinColorLight = Color(0xFF2E7D4F)` — 实测对白底 **5.05:1** ✅ ≥4.5
   - `LoseColorLight = Color(0xFFB84A4A)` — 实测对白底 **5.10:1** ✅ ≥4.5
2. **Theme.kt**：`semanticColors` 改为三项均 `if (darkTheme) 暗色档 else 亮色档`；注释更新为
   列出三档暗色实测值（win 3.96 / lose 4.41 / gold 2.3）；`SemanticColors` 默认参数原样保留。
3. **ContrastTest.kt**（新建，JUnit4）：WCAG 对比度实现
   （sRGB→linear：c<=0.03928 ? c/12.92 : ((c+0.055)/1.055)^2.4；L=0.2126R+0.7152G+0.0722B；
   contrast=(Lmax+0.05)/(Lmin+0.05)）。断言：
   - `contrast(WinColorLight, White) >= 4.5` → 实测 **5.05**
   - `contrast(LoseColorLight, White) >= 4.5` → 实测 **5.10**
   - `contrast(GoldColorLight, White) >= 4.5` → 实测 **5.06**（既有值回归）
   - 固定色锁：WinColor=0xFF3F8F5F、LoseColor=0xFFC25656、GoldColor=0xFFD4A643

## 未触碰（红线遵守）
- WinColor / LoseColor / GoldColor 值；LightColors / DarkColors / dynamicColor 逻辑；
- PlayerDetailDialog.kt / HomeRoute.kt 等调用点（读字段名，无需改）；build.gradle.kts。

## 验收
- `assembleDebug + testDebugUnitTest --rerun-tasks` → BUILD SUCCESSFUL in 14s（41 tasks executed）
- ContrastTest.xml：tests=4 failures=0 errors=0；count-tests.py：✅ 全绿
