package com.gigi.tcg.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** 逐字移植 web/src/utils/__tests__/tier.test.ts（14 用例：1 + it.each×12 + 1） */
class TierTest {

    @Test
    fun `score 小于 1 → 空 0`() {
        assertEquals(TierStars("", 0), getTierStars(0))
    }

    // it.each([score, tier, stars]) 展开，含恰为阈值边界
    @Test
    fun `score 1 → 黄铜 1`() {
        assertEquals(TierStars("黄铜", 1), getTierStars(1))
    }

    @Test
    fun `score 1199 → 黄铜 1`() {
        assertEquals(TierStars("黄铜", 1), getTierStars(1199))
    }

    @Test
    fun `score 1200 → 黄铜 2（恰为阈值边界）`() {
        assertEquals(TierStars("黄铜", 2), getTierStars(1200))
    }

    @Test
    fun `score 1399 → 黄铜 2`() {
        assertEquals(TierStars("黄铜", 2), getTierStars(1399))
    }

    @Test
    fun `score 1550 → 黄铜 3`() {
        assertEquals(TierStars("黄铜", 3), getTierStars(1550))
    }

    @Test
    fun `score 1999 → 黄铜 5`() {
        assertEquals(TierStars("黄铜", 5), getTierStars(1999))
    }

    @Test
    fun `score 2000 → 星银 1（恰为阈值边界）`() {
        assertEquals(TierStars("星银", 1), getTierStars(2000))
    }

    @Test
    fun `score 2499 → 星银 5`() {
        assertEquals(TierStars("星银", 5), getTierStars(2499))
    }

    @Test
    fun `score 2500 → 赤金 1（恰为阈值边界）`() {
        assertEquals(TierStars("赤金", 1), getTierStars(2500))
    }

    @Test
    fun `score 2999 → 赤金 5`() {
        assertEquals(TierStars("赤金", 5), getTierStars(2999))
    }

    @Test
    fun `score 3000 → 影幻 0（兜底）`() {
        assertEquals(TierStars("影幻", 0), getTierStars(3000))
    }

    @Test
    fun `score 3089 → 影幻 0`() {
        assertEquals(TierStars("影幻", 0), getTierStars(3089))
    }

    @Test
    fun `formatTier 星数为 0 时不显示星号`() {
        assertEquals("黄铜★★★", formatTier(getTierStars(1550)))
        assertEquals("", formatTier(getTierStars(0)))
        assertEquals("影幻", formatTier(getTierStars(3089)))
    }
}
