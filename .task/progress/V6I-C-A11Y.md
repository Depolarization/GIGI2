---
task: V6I-C-A11Y
status: done
---

# V6I-C-A11Y 无障碍与截断修复

改前闸门 `git diff --quiet -- <4 files>` → 退出码 0，GATE_PASS，放行。

## 改动明细（行号为修改前实际读到的行号）

### 1. ui/screens/cardstats/CardStatsRoute.kt（5 处）
- L218 `CharTableHeader` padding：`padding(top = 4.dp, bottom = 2.dp)` → `bottom = 4.dp`（4dp 网格）。
- L233 表头列名 `Text(col.label)`：已有 `maxLines = 1`，补 `overflow = TextOverflow.Ellipsis`。
- L271 统计数值 `Text(value)`（CharCardRow）：已有 `maxLines = 1`，补 `overflow = TextOverflow.Ellipsis`。
- L307 ActionCardRow "出场"数值 `Text((card.useCount ?: 0).toString())`：已有 `maxLines = 1`，补 `overflow = TextOverflow.Ellipsis`。
- L354 展开/收起箭头 `Icon(..., contentDescription = null)` → `contentDescription = if (detailOpen) "收起详情" else "展开详情"`（复用该处已有布尔量 `detailOpen`）。

### 2. ui/screens/cardwiki/CardWikiRoute.kt（3 处）
- L169 Tab 标签：`Text(category.title)` → `Text(category.title, maxLines = 1, overflow = TextOverflow.Ellipsis)`。
- L236 搜索 Icon：`contentDescription = null` → `"搜索"`（装饰性图标位于输入框内，加描述供读屏播报入口语义）。
- L409 卡名 Text padding：`vertical = 6.dp` → `vertical = 8.dp`（4dp 网格）。

### 3. ui/login/LoginScreen.kt（1 处 + 3 import）
- L173 `Text("✓", color = Color.White, style = headlineMedium)` → `Icon(Icons.Outlined.Check, contentDescription = "已扫描", tint = Color.White)`。
  - `tint = Color.White` 保留原字符白色（黑色遮罩上必须白，否则不可见）；非改遮罩/占位底色。
  - import 补：`androidx.compose.material.icons.Icons`、`androidx.compose.material.icons.outlined.Check`、`androidx.compose.material3.Icon`（原文件均无）。
- L161 二维码 Image 已有 `contentDescription = "米游社扫码登录二维码"`，未动。

### 4. ui/dialogs/about/AboutDialog.kt（1 处）
- L131 `AboutParagraph` padding：`vertical = 2.dp` → `vertical = 4.dp`（4dp 网格）。

## 排除项（审查报告中判定不改的条目）
- **不改** LoginScreen L186 二维码占位 `Color.White` 背景 —— 主代理判定为误报：白色是二维码可扫性必需，深色模式换 surface 会把对比度打到解码阈值以下。
- **不改** LoginScreen L166 遮罩 `Color.Black.copy(alpha = 0.7f)` —— M3 `colorScheme.scrim` 明暗两档均为纯黑，改与不改等价，无收益。
- **不加** FilterChip `heightIn(min = 48.dp)` —— M3 Chip 内部已应用 `minimumInteractiveComponentSize()`，触摸目标已是 48dp，加高破坏视觉。
- **不换** CardWiki 裸 `TabRow` → `PrimaryTabRow`、搜索框 → M3 `SearchBar` —— 属风格替换 + 改交互，超出本棒范围（审查报告 L164 / L213）。
- CardStats L221 表头首列 "角色牌" Text、L294 "出场" label Text：原本无 `maxLines = 1`，按"仅给已有 maxLines 的补 overflow"原则不动。
- 未改任何布局结构 / 状态机 / 数据流 / 文案 / 路由 / 主题色引用；未新增依赖。

## 构建
`JAVA_HOME="C:/Users/oscur/.jdks/ms-21.0.12.1" ./gradlew :app:assembleDebug --rerun-tasks` → **BUILD SUCCESSFUL in 11s**（35 tasks 全部 executed，含 compileDebugKotlin）。

## 提交
- fix 提交：仅 4 个独占文件（`git diff --stat` 中 V6A-P0.md / 任务清单-main.md / task_scheduler.py 为并行棒改动，未裹挟）。
- 本探针为第二笔 task 提交。
