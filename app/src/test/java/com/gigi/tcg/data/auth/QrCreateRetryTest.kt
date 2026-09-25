// U4A 覆盖：AuthManager.createQrLogin 的瞬时失败自动重试（≤QR_CREATE_MAX_ATTEMPTS 次）。
// 无 MockWebServer：OkHttp 应用拦截器按脚本回 IOException / canned JSON（结构照抄 MihoyoClientRetryTest）；
// runTest 虚拟时钟断言退避真实生效（防"假重试"）。

package com.gigi.tcg.data.auth

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private val testJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

private const val OK_FRAME =
    """{"retcode":0,"message":"OK","data":{"url":"https://example.invalid/qr","ticket":"ticket-1"}}"""

/** 脚本帧：null = 本次调用抛 IOException（模拟冷启动 DNS/连接超时），字符串 = 200 + canned JSON */
private class ScriptedInterceptor(private val frames: List<String?>) : Interceptor {
    var calls = 0
    val deviceIds = mutableListOf<String?>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val frame = frames[minOf(calls, frames.lastIndex)]
        calls += 1
        deviceIds += chain.request().header("x-rpc-device_id")
        if (frame == null) throw IOException("timeout connecting to host (simulated)")
        return Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(frame.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }
}

/**
 * CredentialStore 是 final class 且构造需 Context（JVM 单测里 android.jar 桩直接抛 "Stub!"），
 * 而 createQrLogin 全程不触碰凭据层 → 绕过构造器分配一个纯类型占位实例。
 */
private fun unusedCredentialStore(): CredentialStore {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
    val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
    return allocate.invoke(theUnsafe.get(null), CredentialStore::class.java) as CredentialStore
}

private fun managerWith(frames: List<String?>): Pair<AuthManager, ScriptedInterceptor> {
    val script = ScriptedInterceptor(frames)
    val http = OkHttpClient.Builder().addInterceptor(script).build()
    return AuthManager(http, unusedCredentialStore(), testJson) to script
}

@OptIn(ExperimentalCoroutinesApi::class)
class QrCreateRetryTest {

    @Test
    fun `transient IOException then success returns created session`() = runTest {
        val (manager, script) = managerWith(listOf(null, OK_FRAME))
        val created = manager.createQrLogin()
        assertEquals("https://example.invalid/qr", created.url)
        assertEquals("ticket-1", created.ticket)
        assertEquals(2, script.calls)
    }

    @Test
    fun `rate-limited retcode retries and succeeds`() = runTest {
        val (manager, script) = managerWith(
            listOf("""{"retcode":-500004,"message":"操作频繁，请稍后再试","data":null}""", OK_FRAME),
        )
        val created = manager.createQrLogin()
        assertEquals("ticket-1", created.ticket)
        assertEquals(2, script.calls)
    }

    @Test
    fun `third attempt succeeds and deviceId stays the same across retries`() = runTest {
        val (manager, script) = managerWith(listOf(null, null, OK_FRAME))
        val created = manager.createQrLogin()
        assertEquals(3, script.calls)
        assertEquals(listOf(created.deviceId), script.deviceIds.distinct())
    }

    @Test
    fun `exhausted IOExceptions throw network-class QrCreateException`() = runTest {
        val (manager, script) = managerWith(listOf(null))
        try {
            manager.createQrLogin()
            fail("expected QrCreateException")
        } catch (e: QrCreateException) {
            assertTrue("网络类失败 isNetwork 应为 true", e.isNetwork)
            assertTrue("末次异常原文要透出", e.message!!.contains("simulated"))
            assertTrue(e.cause is IOException)
        }
        assertEquals(3, script.calls)
    }

    @Test
    fun `non-retryable retcode fails fast with mihoyo message`() = runTest {
        val (manager, script) = managerWith(
            listOf("""{"retcode":-100,"message":"扫码登录已失效，请重新登录","data":null}"""),
        )
        try {
            manager.createQrLogin()
            fail("expected QrCreateException")
        } catch (e: QrCreateException) {
            assertEquals(false, e.isNetwork)
            assertTrue("业务类要展示米哈游原始 message，实际=${e.message}",
                e.message!!.contains("扫码登录已失效"))
        }
        assertEquals("不可重试的业务码不得消耗重试额度", 1, script.calls)
    }

    @Test
    fun `three attempts really wait two backoff intervals`() = runTest {
        val (manager, _) = managerWith(listOf(null))
        val start = currentTime
        try {
            manager.createQrLogin()
            fail("expected QrCreateException")
        } catch (e: QrCreateException) {
            assertTrue(e.isNetwork)
        }
        val elapsed = currentTime - start
        val minBackoff = 2 * AuthManager.QR_CREATE_RETRY_DELAY_MS
        val maxBackoff = 2 * (AuthManager.QR_CREATE_RETRY_DELAY_MS + AuthManager.QR_CREATE_RETRY_JITTER_SPAN_MS)
        assertTrue("elapsed=$elapsed 应 ≥ 2×800ms 真退避（防假重试）", elapsed >= minBackoff)
        assertTrue("elapsed=$elapsed 应 ≤ 2×(800+300)ms", elapsed in minBackoff..maxBackoff)
    }
}
