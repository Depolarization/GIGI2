# V7C-STATS-TITLE

status: done

## 任务
卡牌统计页"展开详情"里，分组小标题（"行动牌详情"/"足迹"）与内容行标签字号/颜色相同，层级不可辨。
只改 `CardStatsRoute.kt` 的 `DetailGroup`。

## 改前闸门
`git diff --quiet -- .../CardStatsRoute.kt` → exit 0，放行。

## 既有惯例核对（PlayerDetailDialog.kt）
- `SectionTitle`（L271-283）：`titleSmall` + `FontWeight.Bold`，默认色 onSurface，`padding(top=16.dp, bottom=4.dp)`。
- `EntriesSection`（L230-268）：行内次级标签（序号/积分数值）用 `labelMedium` + `onSurfaceVariant`，主内容才用 `bodyMedium` 默认色。
⇒ 工程惯例 = **分组标题加粗 + 行内次要标签降色 onSurfaceVariant**，与本派单要求**一致**，无冲突。

## 改动对照表（仅 DetailGroup）
| 元素 | 改前 typography / color | 改后 typography / color |
| --- | --- | --- |
| 分组标题 | `titleSmall`(14sp) / 默认 onSurface | `titleSmall` + **`FontWeight.Bold`** / `onSurface` |
| 行标签 | `bodyMedium`(14sp) / 默认 onSurface | **`bodySmall`(12sp)** / **`onSurfaceVariant`** |
| 行数值 | `bodyMedium` / 默认 | `bodyMedium` / 默认（**保持不变**，数值是主内容） |
| 组间距 | Column `padding(top=8.dp)` | Column `padding(top=12.dp)`（可选项已采纳：向 SectionTitle 的 16dp 呼吸感靠拢，未照抄 16 以免改动过大） |

参数签名与两个调用点（`DetailGroup("行动牌详情", ...)` / `DetailGroup("足迹", ...)`）未动。
新增 import：`androidx.compose.ui.text.font.FontWeight`（同文件已有 FontFamily，无新依赖）。

## 构建
命令：`JAVA_HOME="C:/Users/oscur/.jdks/ms-21.0.12.1" ./gradlew :app:assembleDebug --rerun-tasks`
结果：**BUILD SUCCESSFUL**（第 5 次运行）。前 4 次失败均为**并发干扰**，与本次改动无关：
1. 错误全落在非独占文件 AppGate.kt（@Composable 上下文）、RankRoute.kt（缺 import）——其它在跑棒的未提交中间态，本文件始终 0 错误；
2. 之后一次 `packageDebug` 报 `NoSuchFileException: .../mergeExtDexDebug/classes.dex`——并发构建互删 intermediates，重跑即过。

## 验收对照
1. assembleDebug --rerun-tasks → BUILD SUCCESSFUL ✅
2. `git diff --stat` 中本棒仅 `CardStatsRoute.kt`（14+/3-，其余 diff 为并发棒文件，未裹挟提交）✅
3. 改动前后对照表见上，与 PlayerDetailDialog `SectionTitle`（titleSmall+Bold）惯例一致 ✅

## 提交
只 `git add` 独占文件：CardStatsRoute.kt + 本探针。
