// V36/1 任务 A 纯函数单测：退避时长与「是否值得再打一次」的唯一判据。
// 顶层 internal + 无协程依赖 ⇒ JVM 直跑，不需要 runTest / 拦截器。

package com.gigi.tcg.data.api

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryBackoffTest {

    // ---- retryDelayMsFor：基准 700ms 起指数翻倍，jitter 叠加 ----

    @Test
    fun `退避基准按 attempt 指数翻倍`() {
        assertEquals(700L, retryDelayMsFor(1, 0L))
        assertEquals(1400L, retryDelayMsFor(2, 0L))
        assertEquals(2800L, retryDelayMsFor(3, 0L))
    }

    @Test
    fun `jitter 原样叠加在退避基准之上`() {
        assertEquals(700L + 300L, retryDelayMsFor(1, 300L))
        assertEquals(1400L + 123L, retryDelayMsFor(2, 123L))
        // 最坏总等待 = 三次全带满抖动：(700+300)+(1400+300)+(2800+300) ≈ 5.8s
        assertEquals(5800L, retryDelayMsFor(1, 300L) + retryDelayMsFor(2, 300L) + retryDelayMsFor(3, 300L))
    }

    @Test
    fun `attempt 边界 0 与负数与极大值均不崩不溢出`() {
        // attempt<=1 一律夹到 2^0
        assertEquals(700L, retryDelayMsFor(0, 0L))
        assertEquals(700L, retryDelayMsFor(-5, 0L))
        // 极大值被 coerceIn(0,16) 夹住：700 * 2^16，不会左移出 Long
        val huge = retryDelayMsFor(Int.MAX_VALUE, 0L)
        assertEquals(700L shl 16, huge)
        assertTrue(huge > 0L)
        assertEquals(700L shl 16, retryDelayMsFor(17, 0L))
    }

    // ---- isRetryableError：语义逐条钉死 ----

    private fun networkError() = ApiError(API_ERROR_KIND_NETWORK, "网络请求失败")
    private fun retcode(retcode: Int) = ApiError(API_ERROR_KIND_RETCODE, "服务端消息", retcode)

    @Test
    fun `network 与 throttled 属瞬态失败可重试`() {
        assertTrue(isRetryableError(networkError()))
        assertTrue(isRetryableError(ApiError(API_ERROR_KIND_THROTTLED, "请求过于频繁，请稍后重试")))
        // 带 IOException 因果链（OkHttp 连接失败）同样可重试，与 kind 无关
        assertTrue(isRetryableError(ApiError("unknown", "包装层", cause = IOException("boom"))))
    }

    @Test
    fun `鉴权码与 CAPTCHA 一律不重试`() {
        assertFalse("-100 应走续命而非重发", isRetryableError(retcode(-100)))
        assertFalse("-101 应走续命而非重发", isRetryableError(retcode(-101)))
        assertFalse("1034 重试无意义且加重风控", isRetryableError(retcode(1034)))
    }

    @Test
    fun `限流繁忙码沿用 RETRYABLE 集合其余业务码不重试`() {
        for (code in RETRYABLE_RETCODES) assertTrue("$code 应可重试", isRetryableError(retcode(code)))
        assertFalse(isRetryableError(retcode(10001)))
        assertFalse(isRetryableError(retcode(0)))
        assertFalse(isRetryableError(retcode(-500003)))
    }
}
