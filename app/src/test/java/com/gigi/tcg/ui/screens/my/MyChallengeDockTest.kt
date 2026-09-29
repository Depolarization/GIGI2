// V37-E 胜冠之试 dock：翻页语义 + 标题跟随 + M3 高度换算 + 结构性回归锁。
//
// 钉死的实测事实（真机 Redmi Note 7，.task/v36r-ui-challenge.xml）：`schedule_list` **倒序**下发，
// 自上而下是 `26年9月下` → `26年9月上` → `26年8月下`。于是「上一旬」（更早）在列表里是
// **下一项**（index+1）—— 这是本功能最容易反向的一处，用下面的用例锁死。
//
// 源码锁一律先剥注释再匹配（codeOnly()）：页面头部注释里写着"不调 expand()/不用 ModalBottomSheet"，
// 拿原文匹配会自己把自己判失败。
package com.gigi.tcg.ui.screens.my

import com.gigi.tcg.data.model.GcgSchedule
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyChallengeDockTest {

    /** 实测形态：倒序，index 越大时间越早 */
    private val schedules = listOf(
        GcgSchedule(id = 301, name = "26年9月下"),
        GcgSchedule(id = 300, name = "26年9月上"),
        GcgSchedule(id = 299, name = "26年8月下"),
    )

    // ─────────── 选中下标钳制 ───────────

    @Test
    fun `null selection defaults to the newest period`() {
        // 默认选中第一项 = 最新旬
        assertEquals(0, clampScheduleIndex(null, 9))
    }

    @Test
    fun `empty schedule list selects nothing`() {
        assertNull(clampScheduleIndex(null, 0))
        assertNull(clampScheduleIndex(0, 0))
        assertNull(clampScheduleIndex(3, -1))
    }

    @Test
    fun `dirty index is pulled back into range instead of crashing`() {
        // 列表重新装载后旬数变少（9 → 3）：越界下标收回最后一项，不能 IndexOutOfBoundsException
        assertEquals(2, clampScheduleIndex(8, 3))
        assertEquals(0, clampScheduleIndex(-5, 3))
        assertEquals(1, clampScheduleIndex(1, 3))
    }

    // ─────────── 翻页边界（🔴 倒序方向锁） ───────────

    @Test
    fun `newest period can go previous but not next`() {
        // index=0 是最新一旬：没有"更新的" ⇒ 下一旬禁用；更早的还在列表下面 ⇒ 上一旬可用
        assertTrue(canGoPreviousSchedule(index = 0, count = 9))
        assertFalse(canGoNextSchedule(index = 0, count = 9))
    }

    @Test
    fun `oldest period can go next but not previous`() {
        assertFalse(canGoPreviousSchedule(index = 8, count = 9))
        assertTrue(canGoNextSchedule(index = 8, count = 9))
    }

    @Test
    fun `middle period can go both ways`() {
        assertTrue(canGoPreviousSchedule(index = 4, count = 9))
        assertTrue(canGoNextSchedule(index = 4, count = 9))
    }

    @Test
    fun `single period list disables both arrows`() {
        assertFalse(canGoPreviousSchedule(index = 0, count = 1))
        assertFalse(canGoNextSchedule(index = 0, count = 1))
        assertNull(previousScheduleIndex(0, 1))
        assertNull(nextScheduleIndex(0, 1))
    }

    @Test
    fun `empty list disables both arrows and yields no index`() {
        assertFalse(canGoPreviousSchedule(index = 0, count = 0))
        assertFalse(canGoNextSchedule(index = 0, count = 0))
        assertNull(previousScheduleIndex(0, 0))
        assertNull(nextScheduleIndex(0, 0))
    }

    @Test
    fun `previous means older so it steps forward in the reversed list`() {
        // 🔴 方向锁：从 `26年9月下` 点「上一旬」得到 `26年9月上`（更早），不是列表上方的那一项
        val fromNewest = previousScheduleIndex(index = 0, count = schedules.size)
        assertEquals(1, fromNewest)
        assertEquals("26年9月上", scheduleTitleAt(fromNewest!!, schedules, fallback = "胜冠之试"))

        // 点「下一旬」= 更新的一旬 = 往列表上方走（index−1）
        assertEquals(0, nextScheduleIndex(index = 1, count = schedules.size))
        assertEquals("26年9月下", scheduleTitleAt(0, schedules, fallback = "胜冠之试"))
    }

    @Test
    fun `arrows stop at both ends of the reversed list`() {
        assertNull(previousScheduleIndex(index = 2, count = schedules.size))
        assertNull(nextScheduleIndex(index = 0, count = schedules.size))
    }

    @Test
    fun `walking previous to the end covers the whole list in chronological order`() {
        // 从最新一直点「上一旬」：访问顺序必须等于时间倒序（下标递增），中途不回头
        val visited = mutableListOf<String>()
        var index: Int? = 0
        while (index != null) {
            visited += scheduleTitleAt(index, schedules, fallback = "胜冠之试")
            index = previousScheduleIndex(index, schedules.size)
        }
        assertEquals(listOf("26年9月下", "26年9月上", "26年8月下"), visited)
    }

    // ─────────── 标题跟随选中项 ───────────

    @Test
    fun `title follows the selected index`() {
        val titles = schedules.indices.map { scheduleTitleAt(it, schedules, fallback = "胜冠之试") }
        assertEquals(listOf("26年9月下", "26年9月上", "26年8月下"), titles)
        assertEquals(3, titles.distinct().size)
    }

    @Test
    fun `blank or missing name falls back instead of drawing an empty title`() {
        // 实测服务端 name 可能缺失/空串（deck.name 就有空串形态），标题不能画成空白
        val withHoles = listOf(GcgSchedule(id = 1, name = null), GcgSchedule(id = 2, name = "   "))
        assertEquals("胜冠之试", scheduleTitleAt(0, withHoles, fallback = "胜冠之试"))
        assertEquals("胜冠之试", scheduleTitleAt(1, withHoles, fallback = "胜冠之试"))
        assertEquals("胜冠之试", scheduleTitleAt(99, schedules, fallback = "胜冠之试"))
    }

    // ─────────── M3 高度换算 ───────────

    @Test
    fun `peek height is handle plus title bar`() {
        // 48dp 手柄（22+4+22，material3 1.3.2 常量）+ 48dp 标题栏（M3 IconButton 最小触控目标）
        assertEquals(96f, DOCK_HANDLE_HEIGHT_DP + DOCK_TITLE_BAR_HEIGHT_DP, 0.001f)
        assertEquals(96f, dockPeekHeightDp().value, 0.001f)
    }

    @Test
    fun `expanded height is sixty percent of the container minus the handle`() {
        // 可用高 800dp ⇒ 整块 dock = 480dp（手柄 48 + 内容列 432）
        assertEquals(432f, dockExpandedContentHeightDp(800f).value, 0.001f)
        assertEquals(0.6f, (432f + DOCK_HANDLE_HEIGHT_DP) / 800f, 0.001f)
        // 换算的是容器高，不是写死的 dp：更高的容器里同一档位按比例长高
        assertTrue(dockExpandedContentHeightDp(1000f) > dockExpandedContentHeightDp(800f))
    }

    @Test
    fun `expanded height never collapses below the title bar`() {
        // 极矮容器（分屏/悬浮窗）：0.6×100 − 48 是负数，必须收回标题栏高度而不是 0
        assertEquals(48f, dockExpandedContentHeightDp(100f).value, 0.001f)
        assertEquals(48f, dockExpandedContentHeightDp(0f).value, 0.001f)
        assertTrue(dockExpandedContentHeightDp(60f).value >= DOCK_TITLE_BAR_HEIGHT_DP)
    }

    // ─────────── 切旬请求合并 ───────────

    @Test
    fun `only an in-flight previous fetch delays the next one`() {
        // 连点翻页：上一旬还在途 ⇒ 等 300ms 合并；首次装载 ⇒ 零额外延迟
        assertEquals(300L, challengeRecordFetchDelayMs(previousFetchInFlight = true))
        assertEquals(0L, challengeRecordFetchDelayMs(previousFetchInFlight = false))
        assertEquals(300L, CHALLENGE_RECORD_COALESCE_MS)
    }

    // ─────────── 源码回归锁 ───────────

    private fun pageSource(): String = codeOnly(
        File("src/main/java/com/gigi/tcg/ui/screens/my/MyChallengePage.kt").readText()
    )

    @Test
    fun `page uses a dock scaffold not a modal sheet`() {
        val src = pageSource()
        assertTrue("必须用 BottomSheetScaffold（常驻 dock）", src.contains("BottomSheetScaffold("))
        assertFalse("不得用 ModalBottomSheet（模态：scrim 压住主体 + 返回手势先关抽屉）", src.contains("ModalBottomSheet("))
        assertTrue("peek 显式按 M3 常量算，不用库默认 56dp", src.contains("sheetPeekHeight = dockPeekHeightDp()"))
        assertTrue("dock 背景不叠第二层底色（主壳已给安全区）", src.contains("containerColor = Color.Transparent"))
    }

    @Test
    fun `switching periods never touches the sheet state`() {
        // 🔴 用户拍板：切旬保持当前展开/收起档位，档位由用户手势独占
        val src = pageSource()
        val mutation = Regex("""\.(expand|partialExpand|animateTo|snapTo|settle|hide|show)\s*\(""")
        assertFalse("页面不得改变 sheet 档位", src.contains(mutation))
        assertTrue("dock 状态只在 scaffold 处建立一次", src.contains("rememberBottomSheetScaffoldState("))
        assertTrue("常驻 dock：默认 skipHiddenState=true，拖不没", src.contains("rememberStandardBottomSheetState("))
        // 选中态只写 selectedIndex，不与 sheet 状态发生任何耦合
        assertTrue(src.contains("onSelect = { selectedIndex = it }"))
        assertFalse(src.contains("dockState."))
    }

    @Test
    fun `body frame is fixed and resets scroll on period switch`() {
        val src = pageSource()
        assertTrue("主体外框由 dock peek 预留，尺寸恒定", src.contains("padding(bodyPadding)"))
        assertTrue("切旬必须把主体滚动复位到顶部", src.contains("scrollState.scrollTo(0)"))
        assertTrue("复位按旬 id 触发", src.contains("LaunchedEffect(scheduleId)"))
        assertTrue("主体内部自滚", src.contains("verticalScroll(scrollState)"))
    }

    @Test
    fun `medal icon next to win count is gone`() {
        // 🔴 用户明确要求移除「胜场 X」右侧图标
        val body = pageSource().substringAfter("private fun ChallengeBody").substringBefore("private fun ScheduleRow")
        assertFalse("主体不得再画奖牌 AppImage", body.contains("AppImage"))
        assertFalse("主体不得再引用 medal 字段", body.contains("medal"))
        assertTrue("胜场文本仍在", body.contains("my_challenge_win_count"))
    }

    @Test
    fun `dock title comes from the list item not the record`() {
        // 标题取 record 的 basic.schedule.name 要等回包 ⇒ 加载前后会闪一下
        val src = pageSource()
        val dock = src.substringAfter("private fun ChallengeDock").substringBefore("private fun DockTitleBar")
        assertTrue(dock.contains("scheduleTitleAt(selectedIndex, schedules, entryTitle)"))
        assertFalse("标题不得依赖 record", dock.contains("record"))
    }

    @Test
    fun `dock list is lazy and rows are keyed`() {
        val dock = pageSource().substringAfter("private fun ChallengeDock").substringBefore("private fun DockTitleBar")
        assertTrue("dock 列表用 LazyColumn（不用 Column+verticalScroll 嵌套）", dock.contains("LazyColumn("))
        assertTrue("Lazy key 必须复合唯一", dock.contains("stableItemKey(item.id, index)"))
    }

    @Test
    fun `arrows are labelled for accessibility and disabled at the edges`() {
        val src = pageSource()
        assertTrue("上一旬有无障碍描述", src.contains("R.string.my_challenge_prev_period"))
        assertTrue("下一旬有无障碍描述", src.contains("R.string.my_challenge_next_period"))
        assertTrue("不可翻页时按钮 disabled", src.contains("enabled = canGoPrevious"))
        assertTrue("不可翻页时按钮 disabled", src.contains("enabled = canGoNext"))
        assertTrue("标题居中", src.contains("textAlign = TextAlign.Center"))
    }

    @Test
    fun `viewmodel coalesces period switches before hitting the api`() {
        val src = codeOnly(File("src/main/java/com/gigi/tcg/ui/screens/my/MyViewModel.kt").readText())
        val body = src.substringAfter("fun loadChallengeRecord(").substringBefore("companion object")
        assertTrue("读在途标记", body.contains("jobs[_challengeRecord]?.isActive == true"))
        assertTrue("在途时才延迟取数", body.contains("if (fetchDelayMs > 0) delay(fetchDelayMs)"))
        assertTrue("换旬仍必须先清 null", body.contains("_challengeRecord.value = null"))
        assertFalse("不得改成并发预取 9 旬", body.contains("forEach"))
    }

    @Test
    fun `avatar segment of the viewmodel stays untouched`() {
        // V37-E 只碰 challenge 段：V36-2b 的头像回填链必须原样在位（探针里另贴 diff 证明）
        val src = File("src/main/java/com/gigi/tcg/ui/screens/my/MyViewModel.kt").readText()
        assertTrue(src.contains("{ backfillAllAvatars() }"))
        assertTrue(src.contains("avatarJob?.cancel()"))
        assertTrue(src.contains("if (!account.avatar.isNullOrBlank()) return"))
    }

    /** 剥掉行注释与块注释（含 KDoc），源码锁只匹配真正的代码 */
    private fun codeOnly(src: String): String = src
        .replace(Regex("""(?s)/\*.*?\*/"""), " ")
        .replace(Regex("""(?m)//[^\n]*"""), " ")
}
