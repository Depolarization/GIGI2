# V8 设计：数据统计页 + 卡牌使用详情长图

> 状态：已与用户三轮问答确认，可直接据此派单。
> 工程：`D:/AndroidStudioProjects/GIGI`（Kotlin + Jetpack Compose，material3 1.3.2，minSdk 24）

---

## 实施状态（2026-09-26 更新）

**用户决策**：**功能 1（统计总览页）暂不做**；本轮只做功能 2（长图导出）+ 深色对比度修复。

| 范围 | 状态 | commit |
| --- | --- | --- |
| 功能 2 渲染器 `ui/export/TableImageRenderer.kt` | ✅ 已完成（V8B） | `72402c0` |
| 功能 2 接线 `CardStatsExport.kt` + `CardImageSaver.saveBitmap` + `CardStatsRoute` 导出按钮 | ✅ 已完成（V8E） | `e6ad702` / `f283211` |
| 长图打磨（V8H）：图宽 1600、列宽/间距重算、导出按钮各自独立状态、按钮间距 | ✅ 已完成 | `663c342` / `c7cba37` |
| 深色档 win/lose 对比度修到 WCAG AA | ✅ 已完成（V8F） | `e7993bd` |
| **功能 1（§2 全节：总览卡 + 区块 A/B/C/D）** | ⏸️ **暂缓，未实施** | — |
| §4.1 `domain/StatsAggregate.kt`（V8A） | ⏸️ 暂缓（属功能 1） | — |
| §4.2 `domain/RecentForm.kt`（V8C） | ⏸️ 暂缓（属功能 1） | — |
| §4.3 `ui/export/TableImageRenderer.kt`（V8B） | ✅ 已实施 | `72402c0` |
| §4.4 `CardImageSaver.saveBitmap`（V8E） | ✅ 已实施 | `e6ad702` |

**实施期相对本设计的偏差（以后者为准）**：
1. **§4.3 签名不变**，但新增了 `internal fun chooseBitmapConfig(widthPx, heightPx): Bitmap.Config`
   与 `internal fun ellipsize(text, maxWidthPx, measure): String`（两者都是纯函数，已纳入 JVM 单测）。
   双栏契约明确为：`columnX`/`columnWidth` **只描述第一栏**，第二栏偏移由绘制端加 `widthPx / 2`。
2. **新增 §4.5 `ui/screens/cardstats/CardStatsExport.kt`**（本设计原未列出，实施时补的）：
   `buildCharTableSpec(summary, uid, cards)` / `buildActionTableSpec(...)` —— 组装 `TableSpec` 的纯函数。
   把「内容组装」与「渲染」分开，前者可 JVM 单测。另有常量
   `EXPORT_IMAGE_WIDTH_PX = 1080` / `EXPORT_TWO_COLUMN_THRESHOLD = 60`。
3. **§3.2 的 uid 来源**：`GcgSummary` 已含 `nickname`/`level`/`avatarCardNum`/`actionCardNum`，
   但**不含 uid** ⇒ uid 由 `CardStatsExport` 的函数参数传入（调用方取 `container.sessionUid.value`）。
   ⇒ **`CardStatsViewModel.kt` 无需改动**（原先以为要改，实为误判）。
4. **§3.4 权限**：`saveBitmap` 不做权限申请流程，缺权限时透出 `IOException.message` 到 toast；
   统一抽 helper 留待后续棒。
5. **导出数据范围（产品决策）**：取 `state.charList` / `state.actionList`（**全量、已按 useCount 降序**），
   **不跟随页面上的排序按钮/类型筛选** —— 长图是分享制品，口径必须稳定可复现。
6. **`onShowToast`** 已接入导出成功/失败反馈（该参数原先一直未被使用）。
7. 🔴 **角色牌恒单栏**（V8H 修，原为"套 60 行阈值"导致角色牌 147 行被误分双栏）：
   `EXPORT_CHAR_TWO_COLUMN_THRESHOLD = Int.MAX_VALUE`，经 `exportTwoColumnThreshold(charTable)` 分发。
   理由：角色牌 6 列在双栏每栏 800px 下**无解**（表头 8% 余量 + 名称 ≥8 中文字 + 6 位数字共需约 671px，
   而可用 slack 仅 560px）。这也符合 §3.5「角色牌表保持单栏」。
8. **列权重以约束验算为准，不是拍脑袋**（V8H）：派单给的初版权重实测「使用次数」表头余量仅 6.7%
   （不足 8% 底线），已重算为 `#`0.9 / `类别`1.5 / `名称`3.8 / `使用次数`1.9 / `使用率%`1.9。
   ⇒ **今后改列宽，必须跑列宽校验脚本而非手算**。

**新增测试**：`TableLayoutTest`（16）、`SemanticContrastTest`（8）、`CardStatsExportTest`（11）。
基线 184 → **219 用例 / 33 类**，全绿。

---


## 0. 已确认决策（用户原话确认，不得擅自变更）

| # | 决策点 | 结论 |
| --- | --- | --- |
| 1 | 平台范围 | **只做 Android**，跨平台（iOS/Windows）已明确放弃 |
| 2 | 功能 1 方向 | **统计总览 + 维度分解**（不做按时间区间的趋势） |
| 3 | 功能 1 入口 | **独立二级页面** |
| 4 | 功能 2 入口与范围 | 卡牌统计页加导出，**角色牌表、行动牌表分别导出两张** |
| 5 | 功能 2 是否画图标 | **纯文字表格，不画卡牌图标** |
| 6 | 行动牌表「数量」列 | **省略**（数据源拿不到） |
| 7 | 本地累积（DataStore） | **暂不做** |
| 8 | 技术选型 | **全自绘**（不引图表库） |
| 9 | 长图行数上限 | **不限行数** |
| 10 | 长图水印 | 不加米游社水印，用自有标题 |

---

## 1. 数据现实（理解这一节才能理解为什么这样设计）

### 1.1 两个数据源，能力完全不同

| 接口 | 内容 | 窗口 | 能否做时间序列 |
| --- | --- | --- | --- |
| `get_game_records`（`fetchGameRecordsCached`） | 对局**明细**：`result`(Win/Lose)、`timestamp`(秒级字符串)、`ladderScore{score,scoreChange}`、`peakScore{...}`、对手昵称/头像 | **≤10 条**（服务端固定，无分页） | ❌ **不能**。玩家对局频率 > 打开 App 频率，中间对局永久丢失，切时间区间必然失真 |
| `game_record gcg/cardList`（`fetchGcgCardListCached`） | 每张卡的 `useCount`(累计使用次数)、`proficiency`(累计胜场) | **全历史累计** | ❌ 无时间维度，但**总量可靠** |

### 1.2 全历史总量我们已经能算（`domain/Summary.kt` 已有）

```kotlin
totalGames = floor(Σ角色牌 useCount / 3)      // 每局上 3 个角色牌
winGames   = floor(Σ角色牌 proficiency / 3)
winRate    = winGames / totalGames
```

**实测校验**：米游社页面显示「共进行 3493 场游戏 / 胜率 58.918%」，
反推 `Σ useCount ≈ 10479`、`Σ proficiency ≈ 6174`，147 张角色牌平均每张出场约 71 次 —— 口径一致 ✅

⇒ **功能 1 的「总局数 / 总胜场 / 总胜率」直接复用 `GcgSummary`，不要另写算法。**

### 1.3 由此推出的功能 1 边界

- ✅ 可做：全历史总览、按角色牌聚合、按行动牌类型聚合、最近 ≤10 局的天梯分走势
- ❌ 不可做：按时间区间切分的胜率/胜局变化

---

## 2. 功能 1：数据统计页（独立二级页面）

### 2.1 页面结构（自上而下）

```
┌ 顶栏：数据统计（返回）
├ 总览卡：总局数 3493 · 总胜场 2058 · 总胜率 58.9%
├ 区块 A：角色牌出场 Top 10（横向条形图）
├ 区块 B：角色牌胜率 Top 10（横向条形图，带最小出场数门槛）
├ 区块 C：行动牌类型占比（装备 / 支援 / 事件 三段横向条）
└ 区块 D：最近 N 局走势（折线图 = 天梯分；每点颜色标胜负）+ 胜负序列条
```

### 2.2 各区块规格

**总览卡**：数据来自 `computeGcgSummary(...)` 的 `totalGames` / `winGames` / `winRate`。三列等宽。

**区块 A（出场 Top 10）**
- 数据：`charList` 按 `useCount` 降序取前 10
- 每行：`名称`（左，定宽省略）+ 横条（按 `useCount / maxUseCount` 比例）+ `出场数`（右）
- 条形图用 **Compose 布局实现**（`Box` + `fillMaxWidth(fraction)`），不需要 Canvas

**区块 B（胜率 Top 10）**
- 🔴 **必须设最小出场数门槛**（建议 `minGames = 20`，常量可调），否则「出场 1 次赢 1 次 = 100%」会霸榜，误导用户
- 门槛以下不参与排序；若过滤后不足 10 条，就显示多少条
- 每行：`名称` + 横条（按 `winRate / 100` 比例）+ `胜率%` + `(出场数)` 小字

**区块 C（行动牌类型占比）**
- 数据：`GcgSummary.modifyUse / assistUse / eventUse` 及对应 percent（**已有**）
- 一条堆叠横条（装备/支援/事件三段不同色）+ 下方图例

**区块 D（最近 N 局走势）**
- 数据：`game_records`（≤10 条），按 `timestamp` **升序**排列
- 折线图：X 轴 = 局序（1..N），Y 轴 = `ladderScore.score`
  - 用 **Compose `Canvas` + `DrawScope`** 自绘（这是唯一需要 Canvas 的地方）
  - 数据点按 `result` 着色：Win 用语义色 win，Lose 用 lose（`LocalSemanticColors`）
  - Y 轴范围 = [min, max] 并留 10% 余量；点数 < 2 时不画线只画点
- 下方：胜负序列条（N 个小方块，Win/Lose 配色）
- 🔴 **必须标注口径**：如「仅含服务端返回的最近 N 局，不代表完整历史」

### 2.3 空态与边界
- `charList` 为空 ⇒ 整页 EmptyState
- 某区块无数据（如区块 D 无对局记录）⇒ 该区块显示"暂无数据"占位，**不隐藏整页**
- 加载中 ⇒ LoadingView；失败 ⇒ ErrorState（复用 `ui/components/StateViews.kt`）

---

## 3. 功能 2：卡牌使用详情长图

### 3.1 列结构（照高清参考图逐列对齐）

**角色牌表（单栏，6 列）**

| 列 | 数据来源 | 对齐 |
| --- | --- | --- |
| `#` | 序号（1 起） | 居中 |
| `名称` | `card.name` | 左 |
| `出场数` | `card.useCount` | 右 |
| `出场率%` | `useCount / charTotalUse × 100`，3 位小数 | 右 |
| `胜率%` | `proficiency / useCount × 100`，3 位小数 | 右 |
| `胜局数` | `card.proficiency` | 右 |

**行动牌表（5 列；行数多时自动分双栏并排）**

| 列 | 数据来源 | 对齐 |
| --- | --- | --- |
| `#` | 序号（1 起，全局连续） | 居中 |
| `类别` | `cardType` 映射：`CardTypeModify`→装备牌 / `CardTypeAssist`→支援牌 / `CardTypeEvent`→事件牌 | 左 |
| `名称` | `card.name` | 左 |
| `使用次数` | `card.useCount` | 右 |
| `使用率%` | `useCount / actionTotalUse × 100`，3 位小数 | 右 |

🔴 「数量」列**不做**（数据源无该字段）。
🔴 百分比**保留 3 位小数**（对齐参考图 `9.419` / `52.888` 的精度）。

### 3.2 页面头部（长图顶部）
- 标题：`角色牌数据` / `行动牌数据`（金色标题栏，配色对齐参考图观感）
- 副标题行：`{nickname} - {uid}`、`牌手等级 {level}`
- 统计徽章行：`角色牌 {avatarCardNum}/147`、`行动牌 {actionCardNum}/941`、`共进行 {totalGames} 场游戏`、`胜率 {winRate}`
  - ⚠️ 分母 147 / 941 是**图鉴总数**，若拿不到就退化为只显示分子（不显示 `/147`）
- 🔴 **不加米游社水印**；可加一行小字 `数据来源：GIGI`

### 3.3 渲染方案（性能是重点，用户明确要求考虑）

**用 `android.graphics.Canvas` + `Paint` 直接画到 `Bitmap`**，**不要**用 Compose 的 `GraphicsLayer.toImageBitmap()`。

理由：`GraphicsLayer` 必须把整张表放进一个可组合并完成布局，**超长内容会撞 GPU 纹理上限（常见 4096/8192）而失败**；而纯文字表格正是原生 Canvas 的强项，尺寸与内存完全可控。

**规格**
- 宽度固定 **1600px**（不随屏幕密度变化，保证分享出去的图规格统一）
  - ⚠️ 原设计写 1080px，**真机实测装不下**（行动牌双栏每栏仅 540px，连表头都放不下），已上调
- 行高、字号按 density 换算后固定（行高 64px、正文 28px、表头 26px、标题 38px、
  单元格左右内边距 20px、列最小宽 40px）
  - ⚠️ 原设计写 56/30/32，同样因实测放不下而调整
- 高度 = 头部 + 表头 + 行数 × 行高 + 底部留白
- `Bitmap.Config`：默认 `ARGB_8888`；若估算内存 > 64MB 则降 `RGB_565`（省一半）
- 🔴 **渲染前预算门**：降级后仍 > 100MB 时直接抛可读 `IOException`，**不等** `Bitmap.createBitmap` 抛 OOM。
  （行动牌 941 行双栏实测高 30428px：ARGB 约 186MB ⇒ 降级 RGB_565 约 93MB，低端机仍有风险）
- **不限行数**（用户明确要求）；`Canvas` 本身支持任意尺寸，只要 Bitmap 能分配

**分块绘制**：按行分批 `canvas.drawText`（每批如 200 行），避免一次性构建大量 `StaticLayout` 对象；表头不重复绘制。

**🔴 可测试性要求**：把**布局计算**抽成纯函数（`computeTableLayout`），它只做算术、不碰 `android.graphics`，可在 JVM 单测里覆盖。实际绘制函数不写 JVM 单测（`Bitmap` 在 JVM 单测不可用，工程没有 Robolectric）。

### 3.4 保存
扩展 `ui/dialogs/cardcover/CardImageSaver.kt`，**新增** `suspend fun saveBitmap(bitmap: Bitmap, baseName: String)`：
- 复用现有两条落盘路径：Q+ 走 `MediaStore`（`RELATIVE_PATH=Pictures/GIGI`、`IS_PENDING`），API 24–28 走公共目录 + `MediaScanner`
- 复用 `coverFileName` 的非法字符清洗逻辑
- 输出 PNG

### 3.5 双栏策略（行动牌）
- 行动牌 941 张单栏会非常长 ⇒ 行数 > `TWO_COLUMN_THRESHOLD`（建议 60）时**分双栏并排**（左栏 1..mid，右栏 mid+1..n），对齐参考图
- 两栏各自带一份表头（对齐参考图）
- 角色牌表**保持单栏**（对齐参考图）

---

## 4. 固定接口签名（各棒必须严格遵守，便于并行开发）

> 以下签名是**跨棒契约**。任何一棒需要改动，必须先改本文档并通知其它棒。

### 4.1 `domain/StatsAggregate.kt`（V8A 新增）

```kotlin
package com.gigi.tcg.domain

/** 单个角色牌的聚合结果 */
data class CharAggregate(
    val name: String,
    val useCount: Int,
    val wins: Int,
    /** 胜率（0–100） */
    val winRate: Double,
)

/** 行动牌类型占比 */
data class ActionTypeShare(
    val modify: Int, val assist: Int, val event: Int,
) {
    val total: Int get() = modify + assist + event
}

/** 按出场数降序取前 n（不足则全取） */
fun topCharsByUse(cards: List<GcgCard>, n: Int): List<CharAggregate>

/**
 * 按胜率降序取前 n，**只统计 useCount >= minGames 的卡**（防止小样本噪声霸榜）。
 * 同胜率时回退出场数降序，保证顺序稳定。
 */
fun topCharsByWinRate(cards: List<GcgCard>, minGames: Int, n: Int): List<CharAggregate>

/** 行动牌按类型汇总使用次数 */
fun actionTypeShare(cards: List<GcgCard>): ActionTypeShare
```

### 4.2 `domain/RecentForm.kt`（V8C 新增）

```kotlin
package com.gigi.tcg.domain

import com.gigi.tcg.data.model.GameRecord

/** 单局走势点（按时间升序） */
data class FormPoint(
    /** 1 起的局序（1 = 最早那局） */
    val seq: Int,
    val timestampSec: Long,
    val isWin: Boolean,
    /** 该局结束后的天梯分（可能为 null：字段缺失） */
    val ladderScore: Int?,
    val scoreChange: Int?,
)

/** 解析秒级时间戳字符串；非法/空返回 null */
fun parseTimestampSec(raw: String?): Long?

/** 取最近 limit 局，按时间升序返回；时间戳缺失的排在最前 */
fun buildRecentForm(records: List<GameRecord>, limit: Int): List<FormPoint>
```

### 4.3 `ui/export/TableImageRenderer.kt`（V8B 新增）

```kotlin
package com.gigi.tcg.ui.export

import android.graphics.Bitmap

/** 列定义 */
data class TableColumn(
    val header: String,
    /** 相对权重（名称列给大值，数值列给小值）；用于按比例分配宽度 */
    val weight: Float,
    val alignEnd: Boolean,
)

/** 一张表的完整内容 */
data class TableSpec(
    val title: String,
    /** 顶部信息行（如 "Clin - 110526730"），可空 */
    val subtitle: String?,
    /** 徽章行（如 "共进行 3493 场游戏"），可空 */
    val badges: List<String>,
    val columns: List<TableColumn>,
    /** 每行的单元格文本，行数 = rows.size，每行长度必须 == columns.size */
    val rows: List<List<String>>,
)

/** 纯计算的布局结果（不依赖 android.graphics，可 JVM 单测） */
data class TableLayout(
    val widthPx: Int,
    val heightPx: Int,
    /** 每列左边缘 x */
    val columnX: List<Int>,
    /** 每列宽度 */
    val columnWidth: List<Int>,
    val headerHeightPx: Int,
    val rowHeightPx: Int,
    val titleHeightPx: Int,
    val columnsPerBand: Int,
    /** 每栏行数（单栏 = rows.size；双栏 = ceil(rows.size/2)） */
    val rowsPerBand: Int,
)

/**
 * 纯函数：算布局。**不得引用任何 android.graphics 类型**。
 * @param widthPx 目标总宽（固定 1600）
 * @param twoColumnThreshold 行数超过它才分双栏
 */
fun computeTableLayout(
    spec: TableSpec,
    widthPx: Int,
    twoColumnThreshold: Int,
): TableLayout

/** 真正绘制（依赖 android.graphics，不写 JVM 单测） */
fun renderTableBitmap(spec: TableSpec, layout: TableLayout): Bitmap
```

### 4.4 `CardImageSaver` 新增方法（V8E）

```kotlin
suspend fun saveBitmap(bitmap: Bitmap, baseName: String)
```

---

## 5. 文件清单与独占划分

| 棒 | 新增 | 修改（独占） |
| --- | --- | --- |
| **V8A** | `domain/StatsAggregate.kt`、`test/domain/StatsAggregateTest.kt` | — |
| **V8B** | `ui/export/TableImageRenderer.kt`、`test/ui/export/TableLayoutTest.kt` | — |
| **V8C** | `domain/RecentForm.kt`、`test/domain/RecentFormTest.kt` | — |
| **V8D** | `ui/screens/stats/StatsRoute.kt`、`StatsViewModel.kt`、`charts/BarRow.kt`、`charts/ScoreLineChart.kt` | — |
| **V8E** | — | `ui/dialogs/cardcover/CardImageSaver.kt`、`ui/screens/cardstats/CardStatsRoute.kt`、`ui/navigation/GigiNavHost.kt` |

**批次**
- **批 1（可并行）**：V8A、V8B、V8C —— 三个新文件互不冲突
- **批 2（可并行）**：V8D（依赖 A/C 的签名，签名已固定）、V8E（依赖 B 的签名 + 改三个共享文件，与 V8D 零重叠）

---

## 6. 硬约束与红线

1. **`minSdk = 24` 且工程未开 core library desugaring** ⇒ 🔴 **禁止使用 `java.time`**（`LocalDate`/`Instant`/`DateTimeFormatter` 在 API 24–25 上会崩）。日期处理必须用 `java.text.SimpleDateFormat` + `java.util.Date`（工程先例：`domain/Format.kt`）。
2. **不得改动** `domain/Summary.kt` 的既有算法（`totalGames`/`winRate` 口径已与米游社核对一致）。
3. **不得引入新依赖**（不引图表库、不引 Room）。
4. 纯文字表格，**不画卡牌图标**。
5. 百分比保留 **3 位小数**。
6. 长图**不加米游社水印**。
7. `RankRoute.kt` 的 indicator 段与 `settledPage → selectTab` 语义**不得触碰**（V7 波成果）。
8. 构建：`JAVA_HOME="C:/Users/oscur/.jdks/ms-21.0.12.1" ./gradlew ... --rerun-tasks`（PATH 里的 java 是 1.8，会失败）。
9. 用例数基线：**178 用例 / 29 类**（V7 波收工）。各棒新增测试后应 > 基线且 `failures=0 errors=0`。

---

## 7. 测试策略

- **纯函数优先**：`StatsAggregate` / `RecentForm` / `computeTableLayout` 全部是可 JVM 单测的纯逻辑，**必须**有测试
- 边界用例：空列表、全零、单元素、`minGames` 门槛过滤后为空、时间戳非法/缺失、双栏临界行数
- **不写** JVM 单测的部分：`renderTableBitmap`（`android.graphics` 在 JVM 不可用）、Compose UI
- 真机验证项：长图能否成功落相册、超长表格是否 OOM、折线图观感

---

## 8. 待实测/待确认（实现期遇到再问）

- 图鉴总数 147（角色牌）/ 941（行动牌）能否从 Wiki 接口拿到；拿不到就只显示分子
- `get_game_records` 实际返回条数（真机 dump 可见约 6 条，总数未确认）
