// V36/1 任务 A 纯函数单测：退避时长与「是否值得再打一次」的唯一判据。
// V36/1b 任务 A 追加：重试耗尽后的「折算」判据——-1（参数错误）不得贴上「请求过于频繁」的标签。
// 顶层 internal + 折算判据部分无协程依赖 ⇒ JVM 直跑；真实耗尽路径用 canned 响应拦截器 + runTest。

package com.gigi.tcg.data.api

import com.gigi.tcg.data.model.MyHomePageData
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    // ---- V36/1b 任务 A：耗尽后的「折算」判据（-1 是参数错误，不是限流） ----

    @Test
    fun `可折算 throttled 的码集合等于 RETRYABLE 减参数码`() {
        // 集合差：-1 实测语义是入参非法（param role_id error / param limit error），不该冒充限流
        assertEquals(RETRYABLE_RETCODES - PARAM_ERROR_RETCODE, THROTTLED_FALLBACK_RETCODES)
        assertEquals(setOf(-500004, -110), THROTTLED_FALLBACK_RETCODES)
        assertTrue("-1 仍该被重试一次（可重试语义未变）", RETRYABLE_RETCODES.contains(PARAM_ERROR_RETCODE))
        assertFalse(THROTTLED_FALLBACK_RETCODES.contains(PARAM_ERROR_RETCODE))
        assertTrue(isRetryableError(retcode(PARAM_ERROR_RETCODE)))
    }

    @Test
    fun `折算文案保留服务端原文且不硬编码请求过于频繁`() {
        val msg = throttledFallbackMessage("操作频繁，请稍后再试", -500004, 3)
        assertTrue("msg=$msg", msg.contains("操作频繁，请稍后再试"))
        assertTrue("msg=$msg", msg.contains("已重试 3 次仍失败"))
        assertFalse("不再硬编码限流文案：msg=$msg", msg.contains("请求过于频繁"))
        // 服务端 message 空串 / 缺失 → 兜底到 retcode 描述，不抛 NPE
        assertEquals("接口返回 retcode=-500004（已重试 3 次仍失败）", throttledFallbackMessage("", -500004, 3))
        assertEquals("接口返回 retcode=-110（已重试 1 次仍失败）", throttledFallbackMessage(null, -110, 1))
    }

    @Test
    fun `-500004 耗尽后仍折算 throttled 且保留 retcode 与服务端原文`() = runTest {
        val (client, script) = foldClientWith(
            """{"retcode":-500004,"message":"操作频繁，请稍后再试","data":null}"""
        )
        try {
            client.get<MyHomePageData>("https://example.invalid/api/test")
            fail("expected ApiError")
        } catch (e: ApiError) {
            assertEquals(API_ERROR_KIND_THROTTLED, e.kind)
            assertEquals(-500004, e.retcode)
            assertTrue("message=${e.message}", e.message?.contains("操作频繁，请稍后再试") == true)
            assertFalse("message=${e.message}", e.message?.contains("请求过于频繁") == true)
        }
        assertEquals(MihoyoClient.MAX_RETRY_ATTEMPTS + 1, script.calls)
    }

    @Test
    fun `-110 耗尽后仍折算 throttled`() = runTest {
        val (client, script) = foldClientWith(
            """{"retcode":-110,"message":"request params error","data":null}"""
        )
        try {
            client.get<MyHomePageData>("https://example.invalid/api/test")
            fail("expected ApiError")
        } catch (e: ApiError) {
            assertEquals(API_ERROR_KIND_THROTTLED, e.kind)
            assertEquals(-110, e.retcode)
        }
        assertEquals(MihoyoClient.MAX_RETRY_ATTEMPTS + 1, script.calls)
    }

    @Test
    fun `-1 参数错误耗尽后不折算 throttled 按原 retcode 与原文上抛`() = runTest {
        // 实测样本：role_id / limit 传非法值时服务端回 -1 + 具体参数说明
        val (client, script) = foldClientWith(
            """{"retcode":-1,"message":"param role_id error: value must be greater than 0","data":null}"""
        )
        try {
            client.get<MyHomePageData>("https://example.invalid/api/test")
            fail("expected ApiError")
        } catch (e: ApiError) {
            assertEquals("-1 不该被改写成限流", API_ERROR_KIND_RETCODE, e.kind)
            assertEquals(-1, e.retcode)
            assertEquals("param role_id error: value must be greater than 0", e.message)
            assertFalse(e.message!!.contains("请求过于频繁"))
            assertFalse(e.message!!.contains("已重试"))
        }
        // 折算判据变了，重试预算不该跟着变：-1 仍打满 首发 + MAX 次重试
        assertEquals(MihoyoClient.MAX_RETRY_ATTEMPTS + 1, script.calls)
    }
}

// ---- canned 信封拦截器（与 MihoyoClientRetryTest 同法；那边是 file-private，不能复用同名类） ----

private val foldJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = false
}

private class FoldEnvelopeInterceptor(private val bodies: List<String>) : Interceptor {
    var calls = 0

    override fun intercept(chain: Interceptor.Chain): Response {
        val index = calls
        calls += 1
        return Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(
                bodies[minOf(index, bodies.lastIndex)]
                    .toResponseBody("application/json; charset=utf-8".toMediaType())
            )
            .build()
    }
}

private val foldNoCredentials = object : CredentialSource {
    override fun cookieHeader(): String? = null
}

private fun foldClientWith(vararg bodies: String): Pair<MihoyoClient, FoldEnvelopeInterceptor> {
    val script = FoldEnvelopeInterceptor(bodies.toList())
    return MihoyoClient(
        OkHttpClient.Builder().addInterceptor(script).build(),
        foldJson,
        foldNoCredentials,
    ) to script
}
