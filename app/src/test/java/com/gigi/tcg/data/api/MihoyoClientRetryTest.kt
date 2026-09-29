// ①b 覆盖：MihoyoClient.get 的退避重试（V36/1 起为「最多 MAX_RETRY_ATTEMPTS 次重试」，
// 间隔 = 700ms × 2^(n-1) + 0-300ms 抖动）+ network/解析失败的归因。
// 无 MockWebServer：OkHttp 拦截器直接回 canned 响应；runTest 虚拟时钟断言退避下界/上界。

package com.gigi.tcg.data.api

import com.gigi.tcg.data.model.MyHomePageData
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
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

private val okBody = """{"retcode":0,"message":"OK","data":{"page_info":{"nickname":"Oscuro"}}}"""

/**
 * 计数口径：`calls` = 真实 HTTP 请求数。MihoyoClient 构造期做 `http.newBuilder().addInterceptor(Cookie)`，
 * newBuilder 复制的是**入参 client 的 interceptor 列表**，Cookie 追加在其后 ⇒ 外部只装一份 script，
 * 不存在「同一拦截器跑两遍」。此前观察到 1 次请求 2 遍，根因是退避循环误用 `repeat` +
 * `return@repeat`（只跳过本次 lambda、不跳出循环），把 4 发全打完——已被 MihoyoClient 的 while+break 修掉。
 */
private class ScriptedInterceptor(
    private val bodies: List<String>,
    private val ioFailures: Int = 0,
) : Interceptor {
    var calls = 0

    override fun intercept(chain: Interceptor.Chain): Response {
        val index = calls
        calls += 1
        if (index < ioFailures) throw IOException("connection reset")
        val body = bodies[minOf(index, bodies.lastIndex)]
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

private fun clientWith(vararg bodies: String, ioFailures: Int = 0): Pair<MihoyoClient, ScriptedInterceptor> {
    val script = ScriptedInterceptor(bodies.toList(), ioFailures)
    return MihoyoClient(
        OkHttpClient.Builder().addInterceptor(script).build(),
        testJson,
        noCredentials,
    ) to script
}

class MihoyoClientRetryTest {
    @Test
    fun `rate-limited first envelope retries after 700ms plus 0-300ms jitter and returns data`() = runTest {
        val (client, script) = clientWith(
            """{"retcode":-500004,"message":"操作频繁，请稍后再试","data":null}""",
            okBody,
        )
        val start = currentTime
        val data: MyHomePageData =
            client.get("https://example.invalid/api/test", MyHomePageData.serializer())
        val elapsed = currentTime - start

        assertEquals("Oscuro", data.pageInfo?.nickname)
        assertEquals(2, script.calls)
        assertTrue("elapsed=$elapsed 应落在 [700,1000]", elapsed in 700..1000)
    }

    @Test
    fun `rate-limit code exhausts MAX_RETRY_ATTEMPTS then throws throttled keeping the code`() = runTest {
        val (client, script) = clientWith("""{"retcode":-500004,"message":"操作频繁，请稍后再试","data":null}""")
        val start = currentTime
        try {
            client.get<MyHomePageData>("https://example.invalid/api/test")
            fail("expected ApiError")
        } catch (e: ApiError) {
            assertEquals(API_ERROR_KIND_THROTTLED, e.kind)
            assertEquals(-500004, e.retcode)
        }
        val elapsed = currentTime - start
        // 首发 + MAX_RETRY_ATTEMPTS 次重试；退避 (700,1400,2800) 各叠 0-300ms 抖动
        assertEquals(MihoyoClient.MAX_RETRY_ATTEMPTS + 1, script.calls)
        val minWait = (1..MihoyoClient.MAX_RETRY_ATTEMPTS).sumOf { retryDelayMsFor(it, 0L) }
        val maxWait =
            (1..MihoyoClient.MAX_RETRY_ATTEMPTS).sumOf { retryDelayMsFor(it, MihoyoClient.RETRY_JITTER_SPAN_MS) }
        assertTrue("elapsed=$elapsed 应落在 [$minWait,$maxWait]", elapsed in minWait..maxWait)
    }

    @Test
    fun `auth failure fails fast without retry or jitter delay`() = runTest {
        val (client, script) = clientWith("""{"retcode":-100,"message":"please login","data":null}""")
        val start = currentTime
        try {
            client.get<MyHomePageData>("https://example.invalid/api/test")
            fail("expected ApiError")
        } catch (e: ApiError) {
            assertTrue(isAuthFailureError(e))
            assertEquals(API_ERROR_KIND_RETCODE, e.kind)
            assertEquals(-100, e.retcode)
        }
        assertEquals(1, script.calls)
        assertEquals(0, currentTime - start)
    }

    @Test
    fun `captcha 1034 fails fast with actionable text and no second request`() = runTest {
        val (client, script) = clientWith("""{"retcode":1034,"message":"","data":null}""")
        val start = currentTime
        try {
            client.get<MyHomePageData>("https://example.invalid/api/test")
            fail("expected ApiError")
        } catch (e: ApiError) {
            assertEquals(API_ERROR_KIND_RETCODE, e.kind)
            assertEquals(1034, e.retcode)
            assertTrue("message=${e.message}", e.message?.contains("人机验证") == true)
        }
        assertEquals("1034 重试不可能成功，反而加重风控", 1, script.calls)
        assertEquals(0, currentTime - start)
    }

    @Test
    fun `success on first envelope makes no retry`() = runTest {
        val (client, script) = clientWith("""{"retcode":0,"message":"OK","data":{"page_info":{}}}""")
        client.get<MyHomePageData>("https://example.invalid/api/test")
        assertEquals(1, script.calls)
    }

    // ---- V36/1 新增：network 也纳入退避重试（此前完全无重试） ----

    @Test
    fun `connection failure retries with backoff and succeeds`() = runTest {
        val (client, script) = clientWith(okBody, ioFailures = 1)
        val start = currentTime
        val data: MyHomePageData =
            client.get("https://example.invalid/api/test", MyHomePageData.serializer())
        val elapsed = currentTime - start

        assertEquals("Oscuro", data.pageInfo?.nickname)
        assertEquals(2, script.calls)
        assertTrue("elapsed=$elapsed 应落在 [700,1000]", elapsed in 700..1000)
    }

    @Test
    fun `persistent connection failure ends as network error after bounded retries`() = runTest {
        val (client, script) = clientWith(okBody, ioFailures = MihoyoClient.MAX_RETRY_ATTEMPTS + 1)
        try {
            client.get<MyHomePageData>("https://example.invalid/api/test")
            fail("expected ApiError")
        } catch (e: ApiError) {
            assertEquals(API_ERROR_KIND_NETWORK, e.kind)
        }
        assertEquals(MihoyoClient.MAX_RETRY_ATTEMPTS + 1, script.calls)
    }

    // ---- V36/1 任务 B：解析失败归因 network（不再记 null 让上层按 retcode 误报「服务出错了」） ----

    @Test
    fun `non-json body is attributed to network not retcode`() = runTest {
        val (client, script) = clientWith("<html>502 Bad Gateway</html>")
        try {
            client.get<MyHomePageData>("https://example.invalid/api/test")
            fail("expected ApiError")
        } catch (e: ApiError) {
            assertEquals(API_ERROR_KIND_NETWORK, e.kind)
            assertEquals("响应不是有效的 JSON", e.message)
        }
        assertEquals(MihoyoClient.MAX_RETRY_ATTEMPTS + 1, script.calls)
    }

    @Test
    fun `data shape mismatch is attributed to network not a retcode fallback`() = runTest {
        val (client, script) = clientWith("""{"retcode":0,"message":"OK","data":"unexpected-string"}""")
        try {
            client.get<MyHomePageData>("https://example.invalid/api/test")
            fail("expected ApiError")
        } catch (e: ApiError) {
            assertEquals(API_ERROR_KIND_NETWORK, e.kind)
            assertEquals("响应数据解析失败", e.message)
            assertTrue(!e.message!!.contains("retcode"))
        }
        // SerializationException 派生自 IOException ⇒ 归入 network 可重试路径，一并受退避驱动
        assertEquals(MihoyoClient.MAX_RETRY_ATTEMPTS + 1, script.calls)
    }

    @Test
    fun `业务码缺 data 仍按 retcode 出且不重试`() = runTest {
        val (client, script) = clientWith("""{"retcode":10001,"message":"","data":null}""")
        try {
            client.get<MyHomePageData>("https://example.invalid/api/test")
            fail("expected ApiError")
        } catch (e: ApiError) {
            assertEquals(API_ERROR_KIND_RETCODE, e.kind)
            assertEquals(10001, e.retcode)
        }
        assertEquals(1, script.calls)
    }

    // ===== V37-I 任务 A：Cookie 注入拦截器按请求归属 uid 取凭据（多账户头像根因修复） =====

    @Test
    fun `cookie interceptor takes the tagged account's credentials`() {
        val credentials = RecordingCredentials()
        val client = MihoyoClient(OkHttpClient.Builder().build(), testJson, credentials)
        val tagged = Request.Builder()
            .url("https://example.invalid/api/test")
            .tag(CookieUidTag::class.java, CookieUidTag("157777921"))
            .build()
        val chain = FakeChain(tagged)

        try {
            client.cookieInterceptor().intercept(chain)
            fail("FakeChain 在 proceed 处截停，不应走通")
        } catch (_: IOException) {
        }

        assertEquals(listOf<String?>("157777921"), credentials.requestedUids)
        assertEquals("cookie-of-157777921", chain.sent?.header("Cookie"))
    }

    @Test
    fun `untagged requests keep falling back to the active account credentials`() {
        val credentials = RecordingCredentials()
        val client = MihoyoClient(OkHttpClient.Builder().build(), testJson, credentials)
        val plain = Request.Builder().url("https://example.invalid/api/test").build()
        val chain = FakeChain(plain)

        try {
            client.cookieInterceptor().intercept(chain)
            fail("FakeChain 在 proceed 处截停，不应走通")
        } catch (_: IOException) {
        }

        // 无 tag = 旧行为：走**无参** cookieHeader()（激活账户，含旧版单槽回退）
        assertEquals(listOf<String?>(null), credentials.requestedUids)
        assertEquals("active-cookie", chain.sent?.header("Cookie"))
    }

    /** 记录「网络层向凭据区要了谁的 cookie」：requestedUids 即请求归属审计链 */
    private class RecordingCredentials : CredentialSource {
        val requestedUids = mutableListOf<String?>()

        override fun cookieHeader(): String? {
            requestedUids += null
            return "active-cookie"
        }

        override fun cookieHeader(uid: String?): String? {
            if (uid.isNullOrEmpty()) return cookieHeader()
            requestedUids += uid
            return "cookie-of-$uid"
        }
    }

    /** 假链：截停在 proceed 之前（不触网），只回看拦截器改写后的请求 */
    private class FakeChain(private val request: Request) : Interceptor.Chain {
        var sent: Request? = null

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            sent = request
            throw IOException("FakeChain stop")
        }

        override fun connection(): Connection? = null

        override fun call(): Call = throw UnsupportedOperationException()

        override fun connectTimeoutMillis(): Int = 0

        override fun readTimeoutMillis(): Int = 0

        override fun writeTimeoutMillis(): Int = 0

        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }
}
