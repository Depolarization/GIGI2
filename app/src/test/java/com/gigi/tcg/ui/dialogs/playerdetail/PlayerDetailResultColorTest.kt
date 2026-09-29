// V36 任务 I.2：PlayerDetailDialog 赛事结果胜负判定的纯函数单测。
// 原 resultColor 只认中文字符 + equals("win"/"lose")，英文结果（Champion/Lost/1st…）判不出色；
// 抽成 contestOutcome 纯函数并扩词后，这里锁定三档语义：胜、负、判不出（null=不染色）。
package com.gigi.tcg.ui.dialogs.playerdetail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerDetailResultColorTest {

    @Test
    fun `chinese win lose keywords`() {
        assertEquals(ContestOutcome.Win, contestOutcome("冠军"))
        assertEquals(ContestOutcome.Win, contestOutcome("优胜"))
        assertEquals(ContestOutcome.Win, contestOutcome("第一名"))
        assertEquals(ContestOutcome.Lose, contestOutcome("负"))
        assertEquals(ContestOutcome.Lose, contestOutcome("止步小组赛，败于对手"))
        assertEquals(ContestOutcome.Lose, contestOutcome("最后一名"))
    }

    @Test
    fun `english results are recognized case-insensitively`() {
        assertEquals(ContestOutcome.Win, contestOutcome("Champion"))
        assertEquals(ContestOutcome.Win, contestOutcome("WIN"))
        assertEquals(ContestOutcome.Win, contestOutcome("1st Place"))
        assertEquals(ContestOutcome.Lose, contestOutcome("Lose"))
        assertEquals(ContestOutcome.Lose, contestOutcome("lost in semifinals"))
        assertEquals(ContestOutcome.Lose, contestOutcome("Last Place"))
    }

    @Test
    fun `unknown or null settles to no color`() {
        assertNull(contestOutcome(null))
        assertNull(contestOutcome(""))
        assertNull(contestOutcome("亚军"))
        assertNull(contestOutcome("Qualifies"))
    }
}
