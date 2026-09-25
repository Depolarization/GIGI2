# V6G-PLAYER 探针

- status: done
- 开工闸门：`git diff --quiet -- <3个独占文件>` → exit 0 ✅
- 独占文件：PlayerDetailDialog.kt / Theme.kt / Color.kt

## A 移除"查询码"（已完成）
- 删除 `if (isSelf) { CodeCard(code); Spacer(12.dp) }` 整块（原 L128-132）与私有 `CodeCard` @Composable（原 L180-200）。
- 符号清理核查：
  - `content.code` **保留**未动（VM 取数凭据，PlayerDetailViewModel 未被 diff）；Dialog 内不再引用属允许。
  - `PlayerDetailBody` 的 `isSelf` 形参连同调用处传参一起去掉；L68 `val isSelf` 定义与 L89 复制UID toast 用法保持不动（仍被消费，无未使用告警）。
  - 顶部注释 L3 "…+ 查询码（仅本人）+…" 已同步更正。
  - CodeCard 用到的 import（Surface / RoundedCornerShape / padding / fillMaxWidth / height）均仍被 RolesSection / ScoresRow / SectionTitle 等使用，无残留死 import。
- 删后自读：HeaderRow → Spacer(8.dp) → ScoresRow → RolesSection → EntriesSection，顺序与间距正确，无双倍空隙（原 12.dp 随块一起删除）。

## B 昵称/段位对齐（已完成）
- 方案：基线对齐。内层 Row `verticalAlignment = Alignment.Bottom`，昵称与段位两个 Text 均加 `Modifier.alignByBaseline()`。
- 依据：两段字号不同（titleMedium/Bold vs titleSmall/Medium），CenterVertically 按各自 em 框居中导致基线错位；基线对齐是混排文本的规范做法，Bottom 作无基线子项兜底。
- 截断行为保持：昵称仍 `weight(1f, fill=false)` + maxLines=1 + Ellipsis，weight 与 alignByBaseline 可共存，未破坏。
- 构建后若观感仍不理想回退 CenterVertically——当前采用基线方案（编译通过性由本构建验证）。

## C 亮/暗两档金（已完成）
- Color.kt：**新增** `GoldColorLight = Color(0xFF8A6A16)`；`GoldColor = Color(0xFFD4A643)` 原样保留（见下方自证）。
- Theme.kt：`GigiTheme` 内 `LocalSemanticColors provides SemanticColors(gold = if (darkTheme) GoldColor else GoldColorLight)`，与同函数 colorScheme 的 `darkTheme` 分支同判据，未引 `isSystemInDarkTheme()`。
- 对比度推算（sRGB 相对亮度）：
  - 旧 `#D4A643`：L≈0.416 → 对白底 contrast ≈ 1.05/0.466 ≈ **2.3:1**，不足 AA（<4.5），即"亮色看不清"根因。
  - 新 `#8A6A16`：R=0.2542 G=0.1442 B=0.0080 → L≈0.158 → 对白底 contrast ≈ 1.05/0.208 ≈ **5.1:1 ≥ 4.5**，WCAG AA 达标（段位 titleSmall≈14sp、巅峰积分 titleLarge 均按普通文本要求通过）。
- gold 全部使用点清单（grep 复核，与主代理 4 处一致，另有主题层自身定义）：
  - RankRoute.kt:209 `1 -> GoldColor`（直接 import，固定冠军金）→ 未改 GoldColor，暗/亮均**零影响**，守住设计红线8 ✅
  - HomeRoute.kt:224 `semantic.gold`（巅峰积分）→ 亮色更清晰、暗色不变 ✅
  - HomeRoute.kt:312 `SpanStyle(color = semantic.gold)` → 同上 ✅
  - PlayerDetailDialog.kt:141/162（段位文本）与 :181（巅峰积分）→ 同上 ✅
  - Theme.kt:25 默认参数、Color.kt:7 定义 → 主题层内部。
  - 未发现任何使用点依赖"亮色下很浅"，无 blocked 项。

## C 自证：GoldColor 未进 diff 删除行
```
$ git diff -- app/src/main/java/com/gigi/tcg/ui/theme/Color.kt
@@ -4,4 +4,7 @@ import androidx.compose.ui.graphics.Color
 
 val WinColor = Color(0xFF3F8F5F)
 val LoseColor = Color(0xFFC25656)
+// 暗色档金（tokens.css --color-gold，排行榜前三固定色也用它，勿改值）
 val GoldColor = Color(0xFFD4A643)
+// 亮色档深金：亮底 surface 上 WCAG AA（对白底对比度约 5.1:1），仅供语义色按主题取用
+val GoldColorLight = Color(0xFF8A6A16)
```
→ `val GoldColor = Color(0xFFD4A643)` 仅作为上下文行出现，无任何 `-` 删除行 ✅

## D 自审（Material3，3 文件范围内）
- 间距：删除块未引入双倍空隙；其余 8/12/16dp 节奏不变。触摸目标：未触碰按钮。文案：仅注释同步。未发现需额外修复的明确问题，遵守"不顺手重构"。

## 构建
- `JAVA_HOME=C:/Users/oscur/.jdks/ms-21.0.12.1 ./gradlew :app:assembleDebug --rerun-tasks` → **BUILD SUCCESSFUL in 12s, 35 tasks executed**（--rerun-tasks 强制重跑，无并发假象）

## 备注
- `git diff --stat` 中另有 CardCoverSheet / GigiNavHost / CardWikiRoute 等文件，为并发棒（V6F/V6H/V6E）改动，未裹挟进本棒 commit；本棒仅 add 独占 3 文件 + 本探针。

