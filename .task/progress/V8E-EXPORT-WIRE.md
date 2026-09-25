---
status: doing
task: V8E-EXPORT-WIRE
files:
  - app/src/main/java/com/gigi/tcg/ui/dialogs/cardcover/CardImageSaver.kt (加 saveBitmap)
  - app/src/main/java/com/gigi/tcg/ui/screens/cardstats/CardStatsRoute.kt (加导出入口)
  - app/src/main/java/com/gigi/tcg/ui/screens/cardstats/CardStatsExport.kt (新建)
  - app/src/test/java/com/gigi/tcg/ui/screens/cardstats/CardStatsExportTest.kt (新建)
commits: [e6ad702]
---

# V8E-EXPORT-WIRE 探针

## 目标
把「卡牌使用详情长图」接进卡牌统计页：组装 TableSpec（纯函数，可测）→ 渲染 → 存相册。
前置已完成：`ui/export/TableImageRenderer.kt`（commit 72402c0，只读不改）。

## 列定义（DESIGN-V8.md §3.1）
- 角色牌 6 列：`#` / `名称` / `出场数` / `出场率%` / `胜率%` / `胜局数`（单栏）
- 行动牌 5 列：`#` / `类别` / `名称` / `使用次数` / `使用率%`（行数>60 分双栏）
- 百分比 **3 位小数**；分母为 0 ⇒ `"0.000"`（不得 NaN/Infinity）
- 分母口径：角色牌 = Σ角色牌 useCount；行动牌 = `summary.actionTotalUse`

## 关键结论
- 🔴 **`GcgSummary` 已含** `nickname/level/avatarCardNum/actionCardNum` ⇒ **不改 CardStatsViewModel.kt**
- 全量导出（`state.charList`/`actionList`，已按 useCount 降序），**不跟随页面排序/筛选**
- 复用 `CardImageSaver` 既有两条落盘路径（Q+ MediaStore / API24-28 公共目录 + MediaScanner）
- `onShowToast` 参数已存在但未使用（`@Suppress("UNUSED_PARAMETER")`）⇒ 接反馈后删 Suppress

## 进展日志
- [ ] 开工闸门 git status --porcelain 为空
- [ ] 基线单测（预期 208 用例 / 32 类）
- [ ] 写 CardStatsExport.kt（纯函数）
- [ ] 写 CardStatsExportTest.kt
- [ ] 改 CardImageSaver.kt（saveBitmap）
- [ ] 改 CardStatsRoute.kt（两个导出按钮）
- [ ] 构建 + 全量单测
- [ ] 变异验证（3 位小数 / 分母 0）
- [ ] 提交
