// ①b 覆盖：MihoyoClient.get 对 RETRYABLE 重试一次且间隔 = 700ms + 0-300ms 抖动（仍仅一次）。
// 无 MockWebServer：OkHttp 拦截器直接回 canned 响应；runTest 虚拟时钟断言抖动上界。

package com.gigi.tcg.data.api

import com.gigi.tcg.data.model.MyHomePageData
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
    encodeDefaults = false
}

/** 依脚本顺序返回 canned JSON 的拦截器（超出后复用最后一帧） */
private class ScriptedInterceptor(private val bodies: List<String>) : Interceptor {
    var calls = 0

    override fun intercept(chain: Interceptor.Chain): Response {
        val body = bodies[minOf(calls, bodies.lastIndex)]
        calls += 1
        return Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }
}

private val noCredentials = object : CredentialSource {
    override fun cookieHeader(): String? = null
}

private fun clientWith(script: ScriptedInterceptor) =
    MihoyoClient(OkHttpClient.Builder().addInterceptor(script).build(), testJson, noCredentials)

class MihoyoClientRetryTest {
    @Test
    fun `rate-limited first envelope retries once after 700ms plus 0-300ms jitter`() = runTest {
        val script = ScriptedInterceptor(
            listOf(
                """{"retcode":-500004,"message":"操作频繁，请稍后再试","data":null}""",
                """{"retcode":0,"message":"OK","data":{"page_info":{"nickname":"Oscuro"}}}""",
            ),
        )
        val start = currentTime
        val data: MyHomePageData =
            clientWith(script).get("https://example.invalid/api/test", MyHomePageData.serializer())
        val elapsed = currentTime - start

        assertEquals("Oscuro", data.pageInfo?.nickname)
        assertEquals(2, script.calls)
        assertTrue("elapsed=$elapsed 应落在 [700,1000]", elapsed in 700..1000)
    }

    @Test
    fun `retry happens exactly once even if second envelope still rate-limited`() = runTest {
        val script = ScriptedInterceptor(
            listOf(
                """{"retcode":-500004,"message":"操作频繁，请稍后再试","data":null}""",
                """{"retcode":-500004,"message":"操作频繁，请稍后再试","data":null}""",
            ),
        )
        try {
            clientWith(script).get<MyHomePageData>("https://example.invalid/api/test")
            fail("expected ApiError")
        } catch (e: ApiError) {
            assertEquals(API_ERROR_KIND_RETCODE, e.kind)
            assertEquals(-500004, e.retcode)
        }
        assertEquals(2, script.calls)
    }

    @Test
    fun `auth failure fails fast without retry or jitter delay`() = runTest {
        val script = ScriptedInterceptor(
            listOf("""{"retcode":-100,"message":"please login","data":null}"""),
        )
        val start = currentTime
        try {
            clientWith(script).get<MyHomePageData>("https://example.invalid/api/test")
            fail("expected ApiError")
        } catch (e: ApiError) {
            assertEquals(-100, e.retcode)
        }
        assertEquals(1, script.calls)
        assertEquals(0, currentTime - start)
    }

    @Test
    fun `success on first envelope makes no retry`() = runTest {
        val script = ScriptedInterceptor(
            listOf("""{"retcode":0,"message":"OK","data":{"page_info":{}}}"""),
        )
        clientWith(script).get<MyHomePageData>("https://example.invalid/api/test")
        assertEquals(1, script.calls)
    }
}
