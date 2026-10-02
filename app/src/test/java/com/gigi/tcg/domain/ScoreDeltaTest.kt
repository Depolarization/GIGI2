// V42：积分变化的符号三态（纯函数，无 Compose 依赖）。
// 为什么要有这一层：记录卡的变化值染色此前是**恒定**的（天梯恒 win、巅峰恒 gold），
// 颜色不携带符号信息，"(-7)" 被染成绿色。这层把"符号 → 语义"抽出来单独锁死，
// UI 层只负责按 [ScoreDelta] 选色，不再各写一遍 if (change > 0)。

package com.gigi.tcg.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreDeltaTest {

    @Test
    fun `正数归涨`() {
        assertEquals(ScoreDelta.Up, scoreDelta(1))
        assertEquals(ScoreDelta.Up, scoreDelta(45))
        assertEquals(ScoreDelta.Up, scoreDelta(150))
    }

    @Test
    fun `负数归跌`() {
        assertEquals(ScoreDelta.Down, scoreDelta(-1))
        assertEquals(ScoreDelta.Down, scoreDelta(-7))
        assertEquals(ScoreDelta.Down, scoreDelta(-150))
    }

    @Test
    fun `零单独归平不与涨跌混同`() {
        // 0 染成红或绿都是撒谎：持平就是持平，UI 层据此降强调为中性灰。
        assertEquals(ScoreDelta.Flat, scoreDelta(0))
    }

    @Test
    fun `极值不溢出`() {
        assertEquals(ScoreDelta.Up, scoreDelta(Int.MAX_VALUE))
        assertEquals(ScoreDelta.Down, scoreDelta(Int.MIN_VALUE))
    }

    @Test
    fun `与展示文案口径一致减分必带负号`() {
        // 颜色不是唯一信道：负数文案自带 "-"，正数不带 ⇒ 不依赖颜色也能分辨涨跌
        // （WCAG 1.4.1 Use of Color）。这条锁住"别把正号去掉到看不出涨跌"。
        assertEquals("(-7)", formatScoreChange(-7))
        assertEquals("(0)", formatScoreChange(0))
        assertEquals("(45)", formatScoreChange(45))
    }
}
