---
status: done
task: V8F-DARK-CONTRAST
files:
  - app/src/main/java/com/gigi/tcg/ui/theme/Color.kt
  - app/src/test/java/com/gigi/tcg/ui/theme/ContrastTest.kt（计划外同步锁值）
  - app/src/test/java/com/gigi/tcg/ui/theme/SemanticContrastTest.kt (新建)
commits:
  - e7993bd fix: raise dark-theme win/lose semantic colors to WCAG AA contrast
updated: 2026-09-26（V8-CLOSE 收尾棒补完）
---

# V8F-DARK-CONTRAST 探针

## 目标
深色主题下 `WinColor=0xFF3F8F5F` / `LoseColor=0xFFC25656` 对 `darkColorScheme().surface`(0xFF1C1B1F)
对比度仅约 4.33:1 / 3.88:1，低于 WCAG AA 4.5:1。只修深色档（亮色档 V6I-A 已修到约 5.0:1）。

## 主代理已实证前提
1. win/lose **全部用作文字前景色**（HomeRoute:222/305/329-330、PlayerDetailDialog:180/290-292），
   无一处是「色块背景+白字」⇒ 提亮不会破坏任何场景。
2. 对比度口径 = 对 `surface` 基准（与亮色档「对白底 surface」对称）。
   另需记录对 `Card` 容器 `surfaceContainerHighest`(0xFF36343B) 的对比度供后续决策。

## 约束
- 🔴 不改 `GoldColor`（排行榜前三固定色，注释写明勿改值；对本基准已约 7.6:1）
- 🔴 不改三个 Light 档常量
- 目标：对 0xFF1C1B1F ≥ 4.8:1（留余量），至少 ≥ 4.5:1
- 阈值就是需求，不许为了过测试放宽断言

## 进展日志
- [x] 开工闸门 git diff --quiet Color.kt 退出码 0
- [x] 基线单测：**184 用例 / 30 类，failures=0**（BUILD SUCCESSFUL in 48s）
- [x] grep 复核 win/lose 用法：✅ 确认全部为前景色，派单清单无遗漏
  - `semantic.win/lose` 使用点仅：HomeRoute.kt:222(ScoreItem→Text)、:305(SpanStyle 行内字)、
    :329-330(Text 胜/负)、PlayerDetailDialog.kt:180(ScoreItem→Text)、:290-292(resultColor()→Text)
  - 无 Box/background/containerColor 用法；grep `WinColor|LoseColor|semantic\.(win|lose)` 全仓仅此 9 处 + Theme.kt 定义处
- [x] 写脚本算色值（.task/tmp/pick_contrast.py，不提交）：
  - 复算派单参考值：Win 旧 4.33:1 ✅、Lose 旧 3.88:1 ✅、Gold 7.61:1 ✅（与主代理手算一致）
  - 方法：HSL 空间固定色相/饱和度只提亮度，扫到对 0xFF1C1B1F ≥4.8:1 的最小解；
    另做全色域 RGB 距离最小穷举交叉验证，两者解几乎重合（#419861 vs #439865）
  - 参考起点复核：#4AA36C=5.50:1、#D46A6A=4.95:1（都达标但比需要的更亮，观感偏移更大）
  - **选定：WinColor=#FF439865（4.83:1，hue 144.00°/S 38.81% 与旧值完全一致）、
    LoseColor=#FFCA6D6D（4.83:1，hue 0°/S 46.7%）**，两档余量对称，均在 4.8 线上方
  - 对 Card 容器 0xFF36343B 的对比度（供后续决策，本次不作验收）：Win 旧 3.10→新 **3.46**、
    Lose 旧 2.78→新 **3.46**、Gold 5.46、亮色档同样存在此偏差（WinColorLight 对白 5.05 但对 surfaceContainer 会更低）
    ⇒ 若日后要以 Card 容器为基准，需另立一棒统一口径
  - 不动 GoldColor 理由：实测对 surface 已 7.61:1 >> 4.5，且注释写明"排行榜前三固定色也用它，勿改值"
- [x] 改 Color.kt（仅 WinColor/LoseColor 两常量 + 注释；Gold/三个 Light 档零改动；Theme.kt 零改动——
  SemanticColors 默认值与 darkTheme 分支引用常量本身，常量改即生效）
- [x] **计划外必要改动**：`app/src/test/.../ui/theme/ContrastTest.kt:45-46`（V6I-A 的"锁旧值"测试）
  断言 `Color(0xFF3F8F5F)==WinColor`/`Color(0xFFC25656)==LoseColor`，改色必红 ⇒ 同步更新锁值为新值（+注释）。
  该文件不在独占清单，属派单盲区；改动仅 2 行字面量，随生产 commit 提交并在报告注明。
- [x] 写 SemanticContrastTest.kt（8 用例：公式自检 21.0±0.1 / 同色 1.0、分量可读冒烟、
  dark win/lose ≥4.5、Gold 未误伤、light win/lose 回归锁）
- [ ] 构建 + 全量单测 ← **原棒卡在这里，且归因错了**（以下保留原记录）：`:app:compileDebugKotlin` 红，
  错误全部在 `ui/export/TableImageRenderer.kt`（未跟踪文件，另一代理 00:49 正在写，语法错）与
  AppGate/CredentialStore（非我文件）。ENV-NOTES 处置：确认自己文件无错后重跑；本棒独占文件未出现在错误列表中。
  → **V8-CLOSE 收尾接力**：主代理已从 HEAD 恢复被回退的 `CredentialStore.kt` / `P4-ACCOUNT.md`，
  `compileDebugKotlin` 恢复 BUILD SUCCESSFUL。
- [ ] 变异验证（WinColor 改回旧值 ⇒ 红）← 原棒未做
- [ ] 提交 ← 原棒未做

### 🔴 归因修正（收尾棒据实改写，原记录见上）
**构建早就绿了**（主代理恢复被回退的 `CredentialStore.kt` 之后），真正的卡点是
`SemanticContrastTest.kt` 自身有 **4 处编译错误**（位与写成 `&&`、UInt/Long 口径混用），
被"CredentialStore 缺 cookieHeaderFor"的事故噪音掩盖了归因。
⇒ **本棒不是被并发棒阻塞，是被自己独占文件的编译错误阻塞**；原记录把编译器点名在自己文件上的错误
当成"并发干扰"，是 ENV-NOTES「并发构建假象」这条纪律的**误用**（那条只适用于错误全在别人文件上时）。
详见文末「收尾」节。

- [x] 构建 + 全量单测（收尾棒）→ **BUILD SUCCESSFUL**，**208 用例 / 32 类 / failures=0 / errors=0**
- [x] 变异验证（WinColor 改回旧值 ⇒ 红）（收尾棒，详见文末）
- [x] 提交 → `e7993bd`（含计划外同步的 `ContrastTest.kt` 锁值）

---

# 收尾（V8-CLOSE 接力棒，2026-09-26）

原棒异常退出（未跑验证、未提交），以下由收尾棒据实补完。原过程记录**未删改**。

## commits
- `e7993bd` fix: raise dark-theme win/lose semantic colors to WCAG AA contrast
  （`Color.kt` + `ContrastTest.kt` + `SemanticContrastTest.kt`，pathspec 限定）

## 修了哪几处编译错误（`SemanticContrastTest.kt`）

派单给了 3 条清单 + 1 条「L46 别动」，实际按**编译器/运行器真实输出**逐条处理，结论有一处与派单不同：

1. ✅ **位与误写成逻辑与**（根因，L20-22）：`(argb shr 16) && 0xFF` → `and 0xFF`，三行同改。
   报错原文 `Condition type mismatch: inferred type is 'Long' but 'Boolean' was expected` + `Unresolved reference 'toInt'`。
2. ✅ **`const val` 用了 UInt 字面量**（L40-41）：`0xFF1C1B1Fu` → `0xFF1C1B1FL`、`0xFFFFFFFFu` → `0xFFFFFFFFL`，
   与 `relativeLuminance(argb: Long)` / `contrastRatio(a: Long, b: Long)` 形参对齐。
3. ✅ **L54 黑值**：`contrastRatio(0xFF000000u, White)` → `0xFF000000L`。
4. 🔴 **派单说「L46 的 `Color(0xFF439865u)` 编译是过的，不要动它」——编译确实过的，但运行时是坏的。**
   改完 1-3 后编译通过，`colorComponents_readableOnJvm` 仍然**红**（`expected:<67> but was:<0>`）。
   加临时探针实测两种构造方式的分量：
   ```
   Color(0xFF439865u)  (UInt 重载)  → r=0.0   g=0.0   b=NaN   a=0.5953   value=4282620005
   Color(0xFF439865)   (Int→Long 重载) → r=0.2627451 g=0.59607846 b=0.39607844 a=1.0
   ```
   ⇒ 本工程 Compose 版本的 **UInt 重载没有重排位段**，分量解出 0/NaN（`value` 就是裸的 0xFF439865）。
   生产 `Color.kt` 用的是不带 `u` 的写法（Int 字面量 → `Color(Long)`），**正常**，所以本棒的
   `ContrastTest.darkTierColors_areLocked` 一直是对的。
   **处置**：把测试里的基准色改成与生产同口径的 `Color(0xFF439865)` / `Color(0xFF1C1B1Fu)` 常量保持 Long 口径，
   并在类注释里写下这个坑。**没有**放宽任何阈值（4.5 原样保留）。

### 数学口径复核（派单要求）
- 取分量 `(argb shr 16) and 0xFF` = R、`(argb shr 8) and 0xFF` = G、`argb and 0xFF` = B；
  `Color.argbLong()` 产出 **0x00RRGGBB**（不含 alpha），两侧口径一致 ✅。
- `Color.red/green/blue` 在本工程返回的是 **sRGB 0..1**（非线性），所以 `lin()` 里那次 gamma 展开是**必需**的
  （一度怀疑它重复 gamma、把 helper 改成直接用 `red` 加权，结果 8 条全红/NaN ⇒ 证伪，已回退到原设计）。
- `formula_selfCheck_blackOnWhiteIs21` 实测 **21.000**（0xFF000000 对 0xFFFFFFFF），与理论值吻合。

## 最终全量测试
`JAVA_HOME=…/ms-21.0.12.1 ./gradlew :app:assembleDebug :app:testDebugUnitTest --rerun-tasks`
→ **BUILD SUCCESSFUL in 9s**；`.task/count-tests.py` → **32 类 / 208 用例 / failures=0 / errors=0 / skipped=0** ✅
（基线 184/30 + `TableLayoutTest` 16 + `SemanticContrastTest` 8 = 208/32；派单预估 204 是按 12 条 V8B 用例算的，实际 16 条）

## 变异验证（任务 C 第 1 处）
| 变异 | 结果 |
| --- | --- |
| `Color.kt` `WinColor` → `Color(0xFF3F8F5F)`（旧值） | ✅ **2 红**：`SemanticContrastTest.winColor_meetsWcagAA_onDarkSurface`
失败信息 **`WinColor vs dark surface = 4.327786119629385:1, need >= 4.5`**；`ContrastTest.darkTierColors_areLocked` 同时红（预期） |

**这个 4.3277861 是本棒最有价值的证据**：收尾棒用独立脚本（Python，`0.04045` 阈值 + 2.4 gamma）算的旧值对比度是
**4.3278**，与本测试打印的 **4.3277861** 逐位吻合 ⇒ 证明测试算的是**真 WCAG 比值**，
不是"两边同错所以自洽"。新值同理：Win **4.8272** / Lose **4.8286** / Gold **7.615**。
还原（手工 Edit，**未用** `git checkout`）后复跑全量 → 208/208 绿。

## Card 容器对比度复核（派单要求「复核一下即可」）
独立脚本对 `surfaceContainerHighest = 0xFF36343B` 复算，与本棒上文记录一致：

| 色 | 对 dark surface 0xFF1C1B1F | 对 Card 容器 0xFF36343B |
| --- | --- | --- |
| WinColor `#439865`（新） | **4.827** | **3.458** |
| LoseColor `#CA6D6D`（新） | **4.829** | **3.459** |
| GoldColor `#D4A643`（未动） | 7.615 | 5.455 |
| WinColor `#3F8F5F`（旧） | 4.328 | 3.10 |
| LoseColor `#C25656`（旧） | 3.882 | 2.78 |

⇒ 上文记录的 **Win 3.46 / Lose 3.46 复核无误**。两档对 Card 容器仍 **< 4.5**（这次不作验收口径），
"若要以 Card 容器为基准需另立一棒统一口径"的结论保持不变。

## 验收结论
✅ **通过**。深色档 win/lose 对 dark surface 各约 **4.83:1**，过 WCAG AA 4.5:1 且留余量；
Gold 与三个 Light 档常量零改动；`ContrastTest` 锁值同步为新值（唯一的计划外改动，2 行字面量）。
