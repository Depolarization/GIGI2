// 「最近对局」导出表内容组装的纯 JVM 单测（V24）：列结构、胜负映射、积分带变化量、
// 对手 UID 解析与退化、空列表、副标题退化。不碰 android.graphics —— 渲染层复用
// TableImageRenderer，已由 CardStatsExportTest / TableLayoutTest 覆盖。

package com.gigi.tcg.ui.screens.home

import com.gigi.tcg.data.model.GameRecord
import com.gigi.tcg.data.model.ScoreChange
import com.gigi.tcg.ui.export.computeTableLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SELF_UID = "261958214"
private const val OTHER_UID = "146178207"

class RecentRecordsExportTest {

    private fun record(
        nickname: String? = "行云不与",
        result: String? = "Win",
        transNo: String? = OTHER_UID + "_" + SELF_UID,
        timestamp: String? = "1786515594",
        ladder: ScoreChange? = ScoreChange(score = 2760, scoreChange = 10),
        peak: ScoreChange? = ScoreChange(score = 2310, scoreChange = -6),
    ) = GameRecord(
        nickname = nickname,
        result = result,
        transNo = transNo,
        timestamp = timestamp,
        ladderScore = ladder,
        peakScore = peak,
    )

    @Test
    fun `列结构与表头六列，标题为最近对局`() {
        val spec = buildRecordsTableSpec(listOf(record()), SELF_UID, "Oscuro")
        assertEquals(6, spec.columns.size)
        assertEquals(
            listOf("对手", "UID", "时间", "天梯", "巅峰", "胜负"),
            spec.columns.map { it.header },
        )
        assertEquals(listOf(false, false, false, true, true, true), spec.columns.map { it.alignEnd })
        assertEquals("最近对局", spec.title)
        // 局部列表（服务端最多 10 条）不带徽章行
        assertTrue(spec.badges.isEmpty())
        assertEquals("Oscuro - $SELF_UID", spec.subtitle)
    }

    @Test
    fun `对手 UID 取 trans_no 中不等于自己的一段，胜负映射为胜负空`() {
        val spec = buildRecordsTableSpec(
            listOf(
                record(result = "Win", transNo = "146178207_$SELF_UID"),
                record(result = "Lose", transNo = "${SELF_UID}_340438735"),
                record(result = null, transNo = "${SELF_UID}_185290180"),
            ),
            SELF_UID,
            "Oscuro",
        )
        assertEquals("146178207", spec.rows[0][1])
        assertEquals("340438735", spec.rows[1][1])
        assertEquals("185290180", spec.rows[2][1])
        assertEquals(listOf("胜", "负", "空"), spec.rows.map { it[5] })
    }

    @Test
    fun `积分单元格带变化量，全零或缺失时退化为占位符`() {
        val spec = buildRecordsTableSpec(
            listOf(
                record(ladder = ScoreChange(2760, 10), peak = ScoreChange(2310, -6)),
                record(ladder = ScoreChange(2750, -6), peak = ScoreChange(0, 0)),
                record(ladder = null, peak = null),
            ),
            SELF_UID,
            "Oscuro",
        )
        assertEquals("2760 (10)", spec.rows[0][3])
        assertEquals("2310 (-6)", spec.rows[0][4])
        assertEquals("2750 (-6)", spec.rows[1][3])
        assertEquals("-", spec.rows[1][4])
        assertEquals("-", spec.rows[2][3])
        assertEquals("-", spec.rows[2][4])
    }

    @Test
    fun `对手 UID 解析失败时用占位符而不是 unknown 字样`() {
        val spec = buildRecordsTableSpec(
            listOf(record(transNo = null), record(transNo = "只一段没有下划线")),
            SELF_UID,
            "Oscuro",
        )
        assertEquals("-", spec.rows[0][1])
        assertEquals("-", spec.rows[1][1])
    }

    @Test
    fun `昵称缺失回落未知，uid 缺失时副标题退化为空`() {
        val spec = buildRecordsTableSpec(listOf(record(nickname = null)), "", null)
        assertEquals("未知", spec.rows[0][0])
        assertNull(spec.subtitle)
    }

    @Test
    fun `空列表产出空行集且能被布局函数接受（不触发双栏）`() {
        val spec = buildRecordsTableSpec(emptyList(), SELF_UID, "Oscuro")
        assertTrue(spec.rows.isEmpty())
        // 渲染前必须过布局计算：列权重和 > 0、每行列数 == 列数
        val layout = computeTableLayout(spec, 1600, Int.MAX_VALUE)
        assertEquals(1, layout.columnsPerBand)
        assertEquals(0, layout.rowsPerBand)
    }
}
