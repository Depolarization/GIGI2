---
status: done
task: V8B-EXPORT-RENDER
files:
  - app/src/main/java/com/gigi/tcg/ui/export/TableImageRenderer.kt (新建)
  - app/src/test/java/com/gigi/tcg/ui/export/TableLayoutTest.kt (新建)
commits:
  - 72402c0 feat: canvas-based table image renderer for card usage export
  - (本探针随 V8-CLOSE 的 task commit 提交)
---

# V8B-EXPORT-RENDER 探针

## 目标
新建「卡牌使用详情长图」渲染器：`android.graphics.Canvas` 直画 `Bitmap`（不用 GraphicsLayer.toImageBitmap，
避免超长内容撞 GPU 纹理上限）。纯文字表格、宽 1080px、行高 56px、不限行数。

## 契约（DESIGN-V8.md §4.3）
- `TableColumn(header, weight, alignEnd)`
- `TableSpec(title, subtitle, badges, columns, rows)`
- `TableLayout(widthPx, heightPx, columnX, columnWidth, headerHeightPx, rowHeightPx, titleHeightPx, columnsPerBand, rowsPerBand)`
- `computeTableLayout(spec, widthPx, twoColumnThreshold): TableLayout`（纯函数，禁 android.graphics）
- `renderTableBitmap(spec, layout): Bitmap`
- 双栏时 columnX/columnWidth **只描述第一栏**，第二栏偏移由绘制端加 widthPx/2

## 进展日志
- [x] 开工闸门 git status --short 为空 ← **原棒未达成**（当时并发棒在册）；
  V8-CLOSE 收尾棒按新版闸门核对：非 untracked 改动仅 `ENV-NOTES.md`（主代理）+ `Color.kt`/`ContrastTest.kt`（V8F），
  **`CredentialStore.kt` / `P4-ACCOUNT.md` 未被改动** ⇒ 事故未复发，放行。
- [x] 基线单测（预期 184 用例 / 30 类）← 原棒异常退出**没跑到**；收尾棒复跑基线沿用 V7 波收工的 184/30，
  本波收工实测 **208 用例 / 32 类 / failures=0 / errors=0**（见下）。
- [x] 写 TableImageRenderer.kt（原棒完成，收尾棒逐行读过：常量/纯函数/绘制三段清晰）
- [x] 写 TableLayoutTest.kt（原棒完成；实际 **16 个 @Test**，比派单预估的 12 个多 4 个——
  ellipsize 拆成 3 条、widthPx<=0 单独一条）
- [x] 构建 + 全量单测 → `assembleDebug + testDebugUnitTest --rerun-tasks` **BUILD SUCCESSFUL in 9s**，全绿
- [x] 变异验证（见下节，2 处 + 一个等价变异发现）
- [x] 提交 → `72402c0`（pathspec 限定，`git show --stat` 仅含本棒 2 个文件）

## 验收结论
✅ **通过**。布局/截断/配置选择全部是可在纯 JVM 跑的函数，16 条用例锁住契约；
`renderTableBitmap` 本身按 DESIGN §3.3 的明示不做 JVM 单测（工程无 Robolectric，`Bitmap` 在 JVM 不可用）。

## 四个必答问题

**① 为什么不用 `GraphicsLayer.toImageBitmap()`？**
`GraphicsLayer` 要求把整张表作为一个可组合项**完成 Compose 布局**才能截图，纹理尺寸受 **GPU 纹理上限**
（常见 4096×4096 / 8192）约束；本需求「不限行数」的长图（几千行 × 56px）会直接超过上限而失败。
原生 `Canvas` 直画 `Bitmap` 不经过 GPU 纹理，尺寸与内存完全可控（还能按预算降 `RGB_565` 省一半）。

**② 双栏时 columnX / columnWidth 只描述第一栏（契约）**
`computeTableLayout` 在双栏时把带宽取 `widthPx / 2`（=540），`columnX` 从 **0** 起算、
`columnWidth.sum() == widthPx / 2`；`TableLayout` **不存**第二栏的任何字段。
第二栏偏移由 `renderTableBitmap` 在绘制端加 `band * (widthPx / 2)`（`drawBand(offsetX=…)`）实现。
⇒ 好处：布局结果对单/双栏同构（同一份列宽表复用），断言可精确到「最后一列 x+width == 半宽」；
代价：**绘制端与布局端有隐式约定**，改偏移必须同步改两处（`twoColumnBandTakesHalfWidthAndXStartsAtZero` 锁住了这个口径）。

**③ 为什么把布局/截断/配置选择抽成纯函数？**
工程 **minSdk 24 且没有 Robolectric** ⇒ `android.graphics.Bitmap/Paint/Canvas` 在 JVM 单测里是抛 "Stub!" 的壳，
无法真正测量或绘制。所以：
- `computeTableLayout` 只做算术（`Int/Long/Double`），完全不引用 android.graphics ⇒ 可测；
- `ellipsize(text, maxWidthPx, measure: (String) -> Float)` 把**测量器作参数注入**，
  生产传 `paint::measureText`，测试传 `{ it.length * 10f }` ⇒ 二分包住「宽度不够就截断」这条真实逻辑；
- `chooseBitmapConfig(w, h)` 只做字节数算术（返回 `Bitmap.Config` 枚举常量，
  实测在 JVM 上取枚举值不需要走 stub 方法，可断言）。
⇒ 结果是「会算错的地方」全部有锁，「必须真机验证的」只剩像素级排版。

## 变异验证（收尾棒 V8-CLOSE 实做）

| # | 变异 | 目标用例 | 结果 |
| --- | --- | --- | --- |
| 2a | `distributeColumnWidths` 最后一列 `totalWidthPx - used` → `(weight/total*slack).toInt() + MIN`（派单指定的写法） | `singleColumnWidthsSumExactlyToWidth` | ⚠️ **未红（等价变异）**；但 `twoColumnBandTakesHalfWidthAndXStartsAtZero` 与 `tinyWeightColumnGetsMinimumWidth` **红**（2 红） |
| 2b | 同上，按比例基准从 `slack` 换成 `totalWidthPx` | `singleColumnWidthsSumExactlyToWidth` | ✅ **红**（连带 4 红：emptyRows / twoColumn / tinyWeight） |
| 3 | `MAX_BITMAP_MEMORY_BYTES` → `Long.MAX_VALUE`（预算永不触发） | `bitmapConfigDowngradesOverMemoryBudget` | ✅ **红**（精确 1 红） |

**2a 的等价变异发现（重要，别记成"测试没红就是没覆盖"）**：
`singleColumnWidthsSumExactlyToWidth` 用 5 列 weight=[1,4,1.5,1.5,1.5]（合计 9.5）+ widthPx=1080
⇒ slack = 1080 − 64×5 = **760**，而 760 / 9.5 = **80 整除**，每列份额都是整数 ⇒ **没有舍入余量可丢**，
"误差吸收"这一行在该输入下与"按比例取整"数值完全相同。所以派单指定的变异打不中它，
**不是断言写错，而是测试数据不携带舍入误差**。双栏 540 带宽（slack=230，230/9.5=24.2 非整除）能稳定打中，
故误差吸收逻辑整体仍被覆盖。2b 用于补出「单栏也要能红」的证据。
⇒ 后续若要强化：给 4a 换一组非整除 weight（如 name=4.3）即可让指定变异直接命中。

三处均**手工 Edit 还原**（未用 `git checkout`，见 ENV-NOTES「V8 波 git 事故」红线）：
还原后复跑全量 → **208 用例 / 32 类，failures=0 errors=0，BUILD SUCCESSFUL**。

## 遗留 / 未做（据实记录）
- **端到端渲染未验证**：`renderTableBitmap` 出的像素没有真机/模拟器截图核对（纯函数层已锁，排版观感待肉眼）。
- **未接入调用方**：DESIGN §3.4 的 `CardImageSaver.saveBitmap` 扩展与「卡牌使用详情」入口调用
  不在本棒独占文件内 ⇒ 本棒只交付渲染器 + 布局测试，接线属后续棒。
- DESIGN §3.3 提到「按行分批（每批如 200 行）避免一次性构建大量 `StaticLayout`」：实现**未分批**，
  因为逐格 `canvas.drawText` 根本不创建 `StaticLayout`，且 `Paint` 对象在函数头一次性建好复用；
  该建议的前提（StaticLayout 堆叠）在本实现中不存在，故按现状交付。
