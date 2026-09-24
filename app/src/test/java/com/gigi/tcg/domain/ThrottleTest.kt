package com.gigi.tcg.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 逐字移植 web/src/utils/__tests__/throttle.test.ts（5 用例）。
 * TS 用 vi.useFakeTimers + advanceTimersByTime；Kotlin 侧改为注入 fake clock（now: () -> Long）。
 * 后 4 个用例覆盖 format.ts（TS 中即在本测试文件内），对应 Format.kt。
 */
class ThrottleTest {

    @Test
    fun `createThrottle 首次放行 间隔内的后续调用被忽略`() {
        // vi.useFakeTimers 下 Date.now() 仍为真实时间，取固定大值起步，避免与 last=0 初始态碰撞
        var now = 1_700_000_000_000L
        val t = Throttle(delayMs = 1000, now = { now })
        assertTrue(t())
        now += 999
        assertFalse(t())
        now += 1
        assertTrue(t())
    }

    // ---- formatRecordTime ----

    @Test
    fun `formatRecordTime 秒级时间戳 → MM-DD HH mm（本地时区）`() {
        // 2026-04-10 23:59 本地时间构造，避免时区歧义（TS: new Date(2026, 3, 10, 23, 59)）
        val epochSec = LocalDateTime.of(2026, 4, 10, 23, 59)
            .atZone(ZoneId.systemDefault()).toEpochSecond()
        assertEquals("04-10 23:59", formatRecordTime(epochSec))
    }

    @Test
    fun `formatRecordTime 接受字符串时间戳（服务端实测为字符串）`() {
        val epochSec = LocalDateTime.of(2026, 1, 2, 8, 5)
            .atZone(ZoneId.systemDefault()).toEpochSecond()
        assertEquals("01-02 08:05", formatRecordTime(epochSec.toString()))
    }

    @Test
    fun `formatRecordTime 非法输入 → 空串`() {
        assertEquals("", formatRecordTime(Double.NaN))
        assertEquals("", formatRecordTime("not-a-number"))
    }

    // ---- formatScoreChange ----

    @Test
    fun `formatScoreChange 对齐原版 d 格式化 正数无前缀 负数带负号`() {
        assertEquals("(10)", formatScoreChange(10))
        assertEquals("(-7)", formatScoreChange(-7))
        assertEquals("(0)", formatScoreChange(0))
    }
}
