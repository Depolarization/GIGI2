// V27 卡牌统计页回归锁（纯 JVM，工程无 Robolectric）：
// 1) tab 文案资源与页数一致（stats_tab_char / stats_tab_action，两页）；
// 2) 页码 ↔ 导出表类型映射：第 0 页 = 角色牌（charTable=true）、第 1 页 = 行动牌；
// 3) 表类型 → 子目录/标题资源映射（角色牌 → EXPORT_DIR_CHAR / export_char_title）；
// 4) 源文件级：indicator 实时跟随写法（currentPage 插值 + wrapContentSize 解约束）
//    照抄自 RankRoute，与 RankTabIndicatorTest 同构；旧的两枚页内导出按钮必须已删除。
// V28-C 追加：多选导出对话框（默认全选、positive=保存、跑完显式关窗、全不勾有提示）。
// V29 反转 V28-C 的滚动结构（修「滑动白屏」）：
// 5) 结构回退成 RankRoute 的「固定头 + 页内滚动」：根 Column(fillMaxSize) → 头部/TabRow 固定，
//    PullToRefreshBox 里 HorizontalPager(fillMaxSize)，每页各自 LazyColumn 滚动；
//    全页不得再出现 verticalScroll（pager 在无限高约束下算不出页高 ⇒ 滑动时新页塌成 0 高白屏）；
// 6) TabRow 恢复左右满宽（不再 weight(1f) 让位给右缘按钮）；
// 7) 导出入口进详情面板末尾，样式是 filled Button + 下载图标 + fillMaxWidth（旧 IconButton 不得复活）；
// 8) 导出反馈一次点击只回**一条**消息（合并两张结果 + 带落盘 Uri 供宿主「查看」），
//    聚合口径由 exportFeedbackText 直接做纯 JVM 断言；
// 9) 对话框条目文案带真正导出的条数（stats_export_option_char / _action 占位符资源），
//    条数谓词必须与 CardStatsExport 的 useCount > 0 过滤同口径；
// 10) 新文案三语齐全（values / values-en / values-zh-rTW），杜绝切语言后缺字。
package com.gigi.tcg.ui.screens.cardstats

import com.gigi.tcg.R
import com.gigi.tcg.domain.GcgCard
import com.gigi.tcg.i18n.LocaleStrings
import com.gigi.tcg.ui.dialogs.cardcover.EXPORT_DIR_ACTION
import com.gigi.tcg.ui.dialogs.cardcover.EXPORT_DIR_CHAR
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardStatsTabsTest {

    @Test
    fun tabCountMatchesLabelTable() {
        assertEquals("tab 页数与文案表必须一致", STATS_TAB_COUNT, STATS_TAB_LABEL_RES.size)
        assertEquals(2, STATS_TAB_COUNT)
    }

    @Test
    fun tabLabelsUseDedicatedStatsStrings() {
        assertEquals(R.string.stats_tab_char, STATS_TAB_LABEL_RES[0])
        assertEquals(R.string.stats_tab_action, STATS_TAB_LABEL_RES[1])
    }

    @Test
    fun pageMapsToCharTable() {
        assertTrue("第 0 页 = 角色牌表", isCharTable(0))
        assertFalse("第 1 页 = 行动牌表", isCharTable(1))
        // 越界兜底按第 0 页语义，不给未定义页导出错表的机会
        assertTrue(isCharTable(-1))
        assertTrue(isCharTable(STATS_TAB_COUNT))
    }

    @Test
    fun charTableMapsToCharDirAndTitle() {
        assertEquals(EXPORT_DIR_CHAR, exportDirRes(charTable = true))
        assertEquals(EXPORT_DIR_ACTION, exportDirRes(charTable = false))
        assertEquals(R.string.export_char_title, exportTitleRes(charTable = true))
        assertEquals(R.string.export_action_title, exportTitleRes(charTable = false))
    }

    @Test
    fun selectionIsEmptyOnlyWhenNothingChecked() {
        assertFalse("只勾角色牌不为空", StatsExportSelection(charTable = true, actionTable = false).isEmpty)
        assertFalse("只勾行动牌不为空", StatsExportSelection(charTable = false, actionTable = true).isEmpty)
        assertFalse("两项全选不为空", StatsExportSelection(charTable = true, actionTable = true).isEmpty)
        assertTrue("全不勾才是空（点保存应给提示，不静默失败）", StatsExportSelection(charTable = false, actionTable = false).isEmpty)
    }

    // ---- 源文件级不变量（与 RankTabIndicatorTest 同构，File 读取源码文本） ----

    private val routeSrc: String by lazy { srcOf("CardStatsRoute.kt") }
    private val exportActionSrc: String by lazy { srcOf("CardStatsExportAction.kt") }

    /**
     * 只留代码行（丢掉 // 开头的整行注释）：文件头/函数 KDoc 里会点名被废弃的旧写法
     * （verticalScroll、stats_export_current、TabRow 的 weight 让位…），拿原文做「不得复活」断言会自己打自己。
     */
    private val routeCode: String by lazy {
        routeSrc.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
    }

    private fun srcOf(fileName: String): String {
        val file = File("src/main/java/com/gigi/tcg/ui/screens/cardstats/$fileName")
        assertTrue("源码文件不存在: ${file.absolutePath}", file.exists())
        return file.readText()
    }

    @Test
    fun indicatorInterpolatesWithCurrentPageNotSettled() {
        assertTrue(
            "indicator 必须用 currentPage + currentPageOffsetFraction 实时插值（跟手）",
            routeCode.contains("pagerState.currentPage + pagerState.currentPageOffsetFraction"),
        )
        assertFalse(
            "统计页 indicator 不应引入 settledPage（那是 RankRoute 数据加载的语义，与跟随无关）",
            routeCode.contains("settledPage"),
        )
    }

    @Test
    fun indicatorUnwrapsParentConstraints() {
        // 缺 wrapContentSize 时 TabRow 的固定宽度约束会把 width() 夹成整行宽（V7A 回归）
        val fill = routeCode.indexOf(".fillMaxWidth()")
        val wrap = routeCode.indexOf("wrapContentSize(Alignment.BottomStart)")
        val width = routeCode.indexOf(".width(rightDp - leftDp)")
        assertTrue("indicator 链必须有 fillMaxWidth()", fill >= 0)
        assertTrue("indicator 链必须有 wrapContentSize(Alignment.BottomStart)", wrap >= 0)
        assertTrue("wrapContentSize 必须在显式 width 之前", wrap in 0 until width)
    }

    @Test
    fun pagerBackedTabSwitching() {
        assertTrue("必须用 HorizontalPager 承载左右滑动", routeCode.contains("HorizontalPager(state = pagerState"))
        assertTrue("pager 页数取 STATS_TAB_COUNT", routeCode.contains("pageCount = { STATS_TAB_COUNT }"))
    }

    @Test
    fun oldDualExportButtonsRemoved() {
        assertFalse("旧的「导出角色牌」页内按钮必须删除", routeCode.contains("stats_export_char"))
        assertFalse("旧的「导出行动牌」页内按钮必须删除", routeCode.contains("stats_export_action"))
        assertFalse(
            "旧的 tab 行 IconButton contentDescription（「当前图表」口径已废）不得复活",
            routeCode.contains("stats_export_current"),
        )
    }

    // ---- V29：结构回退到 RankRoute 的「固定头 + 页内滚动」（修滑动白屏） ----

    @Test
    fun noVerticalScrollAnywhereInPage() {
        // 白屏根因：pager 落在 verticalScroll 里 ⇒ 无限高约束 ⇒ 翻页首帧页高塌成 0。
        // 现在代码里不允许出现任何纵向 verticalScroll（横向筛选条的 horizontalScroll 不在此列）。
        assertEquals(
            "全页不得有 verticalScroll（改回 RankRoute 的固定头 + 页内 LazyColumn）",
            0,
            Regex("verticalScroll\\(").findAll(routeCode).count(),
        )
        // V29 需求 8：行动牌四个筛选 chip 改 ToggleGroup 后已无横向滚动条
        // （四项等分一整行放得下，不需要滚）。角色牌排序 ToggleGroup 同理。
        assertEquals(
            "筛选条改 ToggleGroup 后不应再有 horizontalScroll",
            0,
            Regex("horizontalScroll\\(").findAll(routeCode).count(),
        )
    }

    @Test
    fun pagerFillsBoundedViewportHeight() {
        assertTrue(
            "HorizontalPager 必须在有界高里 fillMaxSize（RankRoute:171 同写法）",
            routeCode.contains("HorizontalPager(state = pagerState, modifier = ComposeModifier.fillMaxSize())"),
        )
    }

    @Test
    fun headerAndTabsAreFixedAboveTheScrollingArea() {
        val rootColumn = routeCode.indexOf("Column(modifier = modifier.fillMaxSize())")
        val infoCard = routeCode.indexOf("PlayerInfoCard(")
        val tabRow = routeCode.indexOf("StatsTabRow(pagerState = pagerState)")
        val pull = routeCode.indexOf("PullToRefreshBox(")
        val pager = routeCode.indexOf("HorizontalPager(state = pagerState")
        assertTrue("根必须是 fillMaxSize 的 Column（不是滚动容器）", rootColumn >= 0)
        assertTrue("信息卡必须在根 Column 内、滚动区之前（固定头）", infoCard in rootColumn until pull)
        assertTrue("tab 行必须在滚动区之前（固定头）", tabRow in rootColumn until pull)
        assertTrue("必须有 PullToRefreshBox（下拉刷新仍可用）", pull >= 0)
        assertTrue("pager 必须在 PullToRefreshBox 的 content 内（RankRoute 同层级）", pager > pull)
    }

    @Test
    fun eachPageScrollsInternallyWithLazyColumn() {
        // 照抄榜一（RankRoute:214-215）：LazyColumn(fillMaxSize) + 带稳定 key 的 itemsIndexed
        val lazyCount = Regex("LazyColumn\\(").findAll(routeCode).count()
        assertEquals("两页各自一个 LazyColumn 承载页内滚动", 2, lazyCount)
        assertTrue("LazyColumn 必须 fillMaxSize 吃满页高", routeCode.contains("LazyColumn(\n        ComposeModifier.fillMaxSize(),"))
        assertTrue("列表项必须用带 key 的 itemsIndexed", routeCode.contains("itemsIndexed("))
        assertTrue("角色牌 key 带下标（牌名可能重复）", routeCode.contains("-> \"char-\$index"))
        assertTrue("行动牌 key 带下标", routeCode.contains("-> \"action-\$index"))
    }

    // ---- V29：TabRow 恢复满宽 ----

    @Test
    fun tabRowFillsFullWidthAgain() {
        // 锚点必须带换行+缩进：裸 "TabRow(" 会先命中调用点 StatsTabRow(pagerState …)
        val tabRowStart = routeCode.indexOf("TabRow(\n        modifier")
        assertTrue("必须有独立成块的 TabRow", tabRowStart >= 0)
        val tabRowHead = routeCode.substring(tabRowStart, minOf(tabRowStart + 160, routeCode.length))
        assertTrue("TabRow 必须 fillMaxWidth（恢复原本左右撑满）", tabRowHead.contains("ComposeModifier.fillMaxWidth()"))
        assertFalse("TabRow 不再让位给右缘按钮（weight(1f) 必须移除）", tabRowHead.contains("weight(1f)"))
        assertFalse("tab 行右侧的 IconButton 必须删除", routeCode.contains("IconButton("))
    }

    // ---- V29：导出入口移到详情面板末尾 ----

    @Test
    fun exportButtonIsLastItemOfDetailPanel() {
        val animated = routeCode.indexOf("AnimatedVisibility(")
        val footprint = routeCode.indexOf("R.string.stats_footprint")
        // 从「足迹」之后找 Button( ⇒ 避开它上面那个 FilledTonalButton（展开/收起）
        val button = routeCode.indexOf("Button(", footprint)
        assertTrue("详情面板用 AnimatedVisibility", animated >= 0)
        assertTrue("导出按钮必须在「足迹」组之后（面板最后一项）", button > footprint)
        assertTrue("按钮点击即打开多选对话框", routeCode.contains("onClick = onExportClick"))
        val buttonBlock = routeCode.substring(button, minOf(button + 420, routeCode.length))
        assertTrue("按钮样式 = filled Button + 满宽", buttonBlock.contains("fillMaxWidth()"))
        assertTrue(
            "按钮图标语义 = 导出（IosShare 向上箭头，不是 Download 下箭头）",
            buttonBlock.contains("Icons.Outlined.IosShare"),
        )
        assertTrue("按钮文案走 stats_export_button", buttonBlock.contains("exportLabel"))
    }

    // ---- V29：一条导出反馈 + 落盘 Uri ----

    @Test
    fun exportFeedbackCarriesMergedMessageAndUris() {
        assertTrue("Route 必须定义宿主接线契约（StatsExportFeedback）", routeCode.contains("typealias StatsExportFeedback = (message: String, uris: List<Uri>) -> Unit"))
        assertTrue("Route 必须把回调透传给导出动作", routeCode.contains("onResult = onShowExportResult"))
        assertTrue("exportOneTable 必须回传落盘 Uri（Q+ 非空）", exportActionSrc.contains("TableExportOutcome(succeeded = true, uri = uri)"))
        assertTrue("saveBitmap 的返回值必须接住", exportActionSrc.contains("val uri = CardImageSaver(appContext).saveBitmap("))
        // 一次点击只回一条：onResult 在 run 里只出现一次（合并消息），旧的两张各回一条必须消失
        assertEquals("整次导出只回调一次 onResult（两张表合并成一条消息）", 1, Regex("onResult\\(").findAll(exportActionSrc).count())
        assertFalse("不再回相册路径 Toast 文案（合并消息不显示具体路径）", exportActionSrc.contains("toast_saved_to_album_path"))
        assertTrue("导出按钮文案走 stats_export_button（旧 stats_export_current 已弃用）", routeCode.contains("R.string.stats_export_button"))
        assertTrue("失败兜底仍走 error_export_failed", exportActionSrc.contains("R.string.error_export_failed"))
    }

    // ---- V29：对话框条目带条数（口径 = 真正导出的条数） ----

    @Test
    fun exportRowCountMatchesSpecFilterPredicate() {
        // 与 CardStatsExport 的 buildCharTableSpec / buildActionTableSpec 同谓词，防两处漂移
        assertTrue(
            "导出表过滤谓词（CardStatsExport）必须仍是 useCount > 0",
            srcOf("CardStatsExport.kt").contains("filter { (it.useCount ?: 0) > 0 }"),
        )
        assertEquals("出场次数为 0 或 null 的牌不计数", 2, exportRowCount(listOf(GcgCard(name = "a", useCount = 3), GcgCard(name = "b", useCount = 0), GcgCard(name = "c", useCount = null), GcgCard(name = "d", useCount = 1))))
    }

    @Test
    fun exportDialogOptionsCarryRowCountWithPlaceholder() {
        assertTrue("角色牌条目 = 带占位符成品文案", routeCode.contains("R.string.stats_export_option_char, charCount"))
        assertTrue("行动牌条目 = 带占位符成品文案", routeCode.contains("R.string.stats_export_option_action, actionCount"))
        assertFalse("条目文案不再直接复用 tab 名（要带条数）", routeCode.contains("R.string.stats_tab_char, charChecked"))
        assertTrue("条数取全量列表口径（导出不跟筛选）", routeCode.contains("exportRowCount(state.charList)"))
        assertTrue("行动牌同样取全量列表（不能用 filteredActionList）", routeCode.contains("exportRowCount(state.actionList)"))
    }

    @Test
    fun exportDialogIsMultiSelectWithSave() {
        assertTrue("必须用 M3 AlertDialog", routeCode.contains("AlertDialog("))
        assertTrue("多选项用 Checkbox 承载", routeCode.contains("Checkbox(checked ="))
        assertTrue("对话框标题走资源", routeCode.contains("R.string.stats_export_dialog_title"))
        assertTrue("positive = 保存", routeCode.contains("R.string.action_save"))
        assertTrue("negative = 取消", routeCode.contains("R.string.action_cancel"))
    }

    @Test
    fun exportDialogDefaultsToAllChecked() {
        // 两个勾选态都必须初始为 true（产品确认：默认两张都导）
        val charDefault = routeCode.contains("var exportCharChecked by remember { mutableStateOf(true) }")
        val actionDefault = routeCode.contains("var exportActionChecked by remember { mutableStateOf(true) }")
        assertTrue("角色牌默认勾选", charDefault)
        assertTrue("行动牌默认勾选", actionDefault)
    }

    @Test
    fun exportDialogClosesExplicitlyAndHintsOnEmptySelection() {
        assertTrue(
            "M3 confirmButton 不自动关窗 ⇒ 保存后必须显式置 false",
            routeCode.contains("exportDialogOpen = false"),
        )
        assertTrue("全不勾时给提示（不静默失败）", routeCode.contains("R.string.stats_export_pick_at_least_one"))
        assertTrue("按勾选项导出（走 StatsExportSelection）", routeCode.contains("exportAction.run(selection)"))
    }

    // ---- 聚合消息口径（注入 LocaleStrings 解析器，无需 Android 环境） ----

    private val feedbackTemplates = mapOf(
        R.string.stats_export_saved_one to "one:%1\$d",
        R.string.stats_export_saved_two to "two:%1\$d",
        R.string.stats_export_partial to "partial:%1\$d/%2\$d",
        R.string.stats_export_failed to "failed",
        R.string.stats_export_failed_detail to "failed:%1\$s",
    )

    @After
    fun detachLocaleStrings() = LocaleStrings.installResolverForTest(null)

    private fun withFeedbackTemplates(block: () -> Unit) {
        LocaleStrings.installResolverForTest { id -> feedbackTemplates[id] }
        block()
    }

    @Test
    fun feedbackMergesTwoSuccessesIntoOneCountMessageWithoutPath() {
        withFeedbackTemplates {
            val text = exportFeedbackText(
                listOf(TableExportOutcome(succeeded = true, uri = null), TableExportOutcome(succeeded = true, uri = null)),
            )
            assertEquals("两张都成功 → saved_two（只报张数，不报路径）", "two:2", text)
        }
    }

    @Test
    fun feedbackSingleSuccessUsesSavedOne() {
        withFeedbackTemplates {
            assertEquals("one:1", exportFeedbackText(listOf(TableExportOutcome(succeeded = true, uri = null))))
        }
    }

    @Test
    fun feedbackAllFailuresStillReportsOneMessage() {
        withFeedbackTemplates {
            val text = exportFeedbackText(
                listOf(
                    TableExportOutcome(succeeded = false, uri = null, error = "boom"),
                    TableExportOutcome(succeeded = false, uri = null, error = "bang"),
                ),
            )
            assertEquals("全失败也要一条消息（带第一张的错误原因，不吞掉）", "failed:boom", text)
            assertEquals(
                "错误原因为空时回落纯失败文案",
                "failed",
                exportFeedbackText(listOf(TableExportOutcome(succeeded = false, uri = null))),
            )
        }
    }

    @Test
    fun feedbackMixedSuccessAndFailureReportsBothCounts() {
        withFeedbackTemplates {
            assertEquals(
                "一成一败 → 两个数字都说清",
                "partial:1/1",
                exportFeedbackText(
                    listOf(
                        TableExportOutcome(succeeded = true, uri = null),
                        TableExportOutcome(succeeded = false, uri = null, error = "boom"),
                    ),
                ),
            )
        }
    }

    // ---- V29：新文案三语齐全 ----

    @Test
    fun newExportDialogStringsExistInAllLocales() {
        val keys = listOf(
            "stats_export_dialog_title",
            "stats_export_pick_at_least_one",
            "action_save",
            "stats_export_button",
            "stats_export_option_char",
            "stats_export_option_action",
            "stats_export_saved_one",
            "stats_export_saved_two",
            "stats_export_partial",
            "stats_export_failed",
            "stats_export_failed_detail",
            "action_view",
        )
        listOf("values", "values-en", "values-zh-rTW").forEach { dir ->
            val file = File("src/main/res/$dir/strings.xml")
            assertTrue("资源文件不存在: ${file.absolutePath}", file.exists())
            val text = file.readText()
            keys.forEach { key ->
                assertTrue("$dir 缺少 string: $key", text.contains("name=\"$key\""))
            }
        }
    }
}
