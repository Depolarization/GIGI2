// 「最近对局」导出卡片图内容组装的纯 JVM 单测（V26：导出图照抄首页卡片版式后，
// 表结构断言改为卡片字段断言）。口径不变：胜负映射、对手 UID 解析与退化、
// 积分带变化量与全零退化、昵称回落、条数封顶。渲染层（RecentRecordsCardRenderer）
// 依赖 android.graphics，工程无 Robolectric，不在此覆盖。

package com.gigi.tcg.ui.screens.home

import com.gigi.tcg.R
import com.gigi.tcg.data.model.GameRecord
import com.gigi.tcg.data.model.ScoreChange
import com.gigi.tcg.domain.formatRecordTime
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
    fun `卡片字段与首页条目口径一致，无 resolver 时文案走中文默认值`() {
        val cards = buildRecentRecordsCards(listOf(record()), SELF_UID)
        assertEquals(1, cards.size)
        val card = cards[0]
        assertEquals("行云不与", card.nickname)
        assertEquals(OTHER_UID, card.opponentUid)
        assertEquals(formatRecordTime("1786515594"), card.timeText)
        assertEquals("胜", card.resultDisplay)
        assertEquals(ResultColorType.Win, card.resultColor)
        assertEquals("天梯 2760 (10)", scoreLineText(card.ladder!!))
        assertEquals("巅峰 2310 (-6)", scoreLineText(card.peak!!))
    }

    @Test
    fun `胜负映射为胜负空并驱动语义色枚举`() {
        val cards = buildRecentRecordsCards(
            listOf(
                record(result = "Win"),
                record(result = "Lose"),
                record(result = null),
            ),
            SELF_UID,
        )
        assertEquals(listOf("胜", "负", "空"), cards.map { it.resultDisplay })
        assertEquals(
            listOf(ResultColorType.Win, ResultColorType.Lose, ResultColorType.Neutral),
            cards.map { it.resultColor },
        )
    }

    @Test
    fun `对手 UID 取 trans_no 中不等于自己的一段`() {
        val cards = buildRecentRecordsCards(
            listOf(
                record(transNo = "146178207_$SELF_UID"),
                record(transNo = "${SELF_UID}_340438735"),
                record(transNo = "${SELF_UID}_185290180"),
            ),
            SELF_UID,
        )
        assertEquals(listOf("146178207", "340438735", "185290180"), cards.map { it.opponentUid })
    }

    @Test
    fun `对手 UID 解析失败为 null，渲染层据此画占位而不是 unknown 字样`() {
        val cards = buildRecentRecordsCards(
            listOf(record(transNo = null), record(transNo = "只一段没有下划线")),
            SELF_UID,
        )
        assertNull(cards[0].opponentUid)
        assertNull(cards[1].opponentUid)
    }

    @Test
    fun `积分全零或缺失退化为 null，另一侧不受影响`() {
        val cards = buildRecentRecordsCards(
            listOf(
                record(ladder = ScoreChange(2760, 10), peak = ScoreChange(0, 0)),
                record(ladder = null, peak = null),
            ),
            SELF_UID,
        )
        assertEquals(2760, cards[0].ladder!!.score)
        assertEquals(10, cards[0].ladder!!.change)
        assertNull(cards[0].peak)
        assertNull(cards[1].ladder)
        assertNull(cards[1].peak)
    }

    @Test
    fun `昵称缺失回落未知`() {
        val cards = buildRecentRecordsCards(listOf(record(nickname = null)), SELF_UID)
        assertEquals("未知", cards[0].nickname)
    }

    @Test
    fun `条数封顶 10 且空列表得空结果`() {
        val cards = buildRecentRecordsCards(List(13) { record() }, SELF_UID)
        assertEquals(RECENT_RECORDS_MAX_CARDS, cards.size)
        assertEquals(RECENT_RECORDS_MAX_CARDS, 10)
        assertTrue(buildRecentRecordsCards(emptyList(), SELF_UID).isEmpty())
    }

    /** 与渲染器 drawScoreLine 相同的拼接口径：前缀 + 空格 + 分数 + 空格 + (变化量) */
    private fun scoreLineText(line: ScoreLine): String {
        val prefix = if (line.prefixId == R.string.home_peak_prefix) "巅峰" else "天梯"
        return "$prefix ${line.score} ${scoreChangeText(line.change)}"
    }
}
