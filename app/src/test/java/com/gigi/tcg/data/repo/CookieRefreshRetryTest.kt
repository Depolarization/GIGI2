// U3A 静默续命下沉请求层：鉴权失败（-100/-101）→ 单飞续命一次 → 原请求原样重放；
// 续命失败抛原错误；非鉴权错误不触发；并发失败共享同一次续命结果（含失败），不重复打接口。

package com.gigi.tcg.data.repo

import com.gigi.tcg.data.ServerId
import com.gigi.tcg.data.api.ApiError
import com.gigi.tcg.data.api.isAuthFailureError
import com.gigi.tcg.domain.TtlCache
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private val testJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = false
}

private const val OK_RAW = """{"game_uid":"261958214","nickname":"Oscuro"}"""

private fun authFail() = RawEnvelope(-100, "not logged in", null)

/** 按第 n 次调用（全局计数）脚本化响应；calls 记录每次 URL，供"原样重放"断言 */
private class ScriptedTransport(
    private val respond: (attempt: Int) -> RawEnvelope,
) : GigiApiTransport {
    val calls = mutableListOf<String>()

    override suspend fun fetchEnvelope(url: String, tag: String): RawEnvelope {
        calls += url
        return respond(calls.size)
    }
}

private class CountingRefresher(
    private val gate: CompletableDeferred<Unit>? = null,
    private val result: Boolean = true,
    private val failWith: Throwable? = null,
) : SessionRefresher {
    var calls = 0

    override suspend fun refreshActive(): Boolean {
        calls += 1
        gate?.await()
        failWith?.let { throw it }
        return result
    }
}

private class EmptyDisk : WikiDiskStore {
    override suspend fun get(serverId: String): String? = null

    override suspend fun put(serverId: String, rawJson: String) = Unit
}

private fun repo(transport: GigiApiTransport, refresher: SessionRefresher?) = GigiRepository(
    transport,
    EmptyDisk(),
    testJson,
    TtlCache(),
    retryDelayMs = 0,
    sessionRefresher = refresher,
)

class CookieRefreshRetryTest {

    private suspend fun assertAuthFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("expected ApiError(auth failure) to be thrown")
        } catch (e: ApiError) {
            assertTrue(isAuthFailureError(e))
        }
    }

    private suspend fun assertNotAuthFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("expected ApiError to be thrown")
        } catch (e: ApiError) {
            assertFalse(isAuthFailureError(e))
        }
    }

    @Test
    fun `auth failure refreshes once then replays the same request`() = runTest {
        val transport = ScriptedTransport { attempt ->
            if (attempt == 1) authFail() else RawEnvelope(0, "OK", OK_RAW)
        }
        val refresher = CountingRefresher()

        val info = repo(transport, refresher).fetchLoginInfo(ServerId.Official)

        assertEquals("261958214", info.gameUid)
        assertEquals(1, refresher.calls)
        assertEquals(2, transport.calls.size)
        assertEquals(transport.calls[0], transport.calls[1])
    }

    @Test
    fun `refresh failure rethrows original error and never retries`() = runTest {
        // 续命返回 false：不重放、只续一次、抛原错误
        val refuseTransport = ScriptedTransport { authFail() }
        val falseRefresher = CountingRefresher(result = false)
        assertAuthFailure { repo(refuseTransport, falseRefresher).fetchLoginInfo(ServerId.Official) }
        assertEquals(1, falseRefresher.calls)
        assertEquals(1, refuseTransport.calls.size)

        // 续命自身抛异常：同样只续一次、抛原错误
        val throwTransport = ScriptedTransport { authFail() }
        val throwingRefresher = CountingRefresher(failWith = IOException("exchange failed"))
        assertAuthFailure { repo(throwTransport, throwingRefresher).fetchLoginInfo(ServerId.Official) }
        assertEquals(1, throwingRefresher.calls)
        assertEquals(1, throwTransport.calls.size)
    }

    @Test
    fun `auth failure without wired refresher keeps legacy behavior`() = runTest {
        val transport = ScriptedTransport { authFail() }
        assertAuthFailure { repo(transport, null).fetchLoginInfo(ServerId.Official) }
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun `concurrent auth failures share one refresh and every request replays`() = runTest {
        val n = 6
        val wave = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val transport = ScriptedTransport { attempt ->
            if (attempt <= n) {
                if (attempt == n) wave.complete(Unit)
                authFail()
            } else {
                RawEnvelope(0, "OK", OK_RAW)
            }
        }
        val refresher = CountingRefresher(gate = gate)
        val repository = repo(transport, refresher)

        val inFlight = List(n) { async { repository.fetchLoginInfo(ServerId.Official) } }
        wave.await()
        assertEquals(1, refresher.calls)

        gate.complete(Unit)
        inFlight.awaitAll().forEach { assertEquals("261958214", it.gameUid) }
        assertEquals(1, refresher.calls)
        assertEquals(n * 2, transport.calls.size)
    }

    @Test
    fun `concurrent failures with failing refresh do not retry storm`() = runTest {
        val n = 4
        val wave = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val transport = ScriptedTransport { attempt ->
            if (attempt == n) wave.complete(Unit)
            authFail()
        }
        val refresher = CountingRefresher(gate = gate, result = false)
        val repository = repo(transport, refresher)

        val inFlight = List(n) { async { runCatching { repository.fetchLoginInfo(ServerId.Official) } } }
        wave.await()
        gate.complete(Unit)

        val errors = inFlight.awaitAll().map { it.exceptionOrNull() }
        errors.forEach { error -> assertTrue(isAuthFailureError(error!!)) }
        assertEquals(1, refresher.calls)
        assertEquals(n, transport.calls.size)
    }

    @Test
    fun `non auth failures never trigger refresh`() = runTest {
        // 业务 retcode（非 -100/-101）
        val business = ScriptedTransport { RawEnvelope(12345, "boom", null) }
        val businessRefresher = CountingRefresher()
        assertNotAuthFailure { repo(business, businessRefresher).fetchLoginInfo(ServerId.Official) }
        assertEquals(0, businessRefresher.calls)

        // 响应解析失败 → kind=network
        val malformed = ScriptedTransport { RawEnvelope(0, "OK", "{not-json") }
        val malformedRefresher = CountingRefresher()
        assertNotAuthFailure { repo(malformed, malformedRefresher).fetchLoginInfo(ServerId.Official) }
        assertEquals(0, malformedRefresher.calls)

        // 传输层 IO 异常（网络超时语义）
        val io = ScriptedTransport { throw IOException("timeout") }
        val ioRefresher = CountingRefresher()
        try {
            repo(io, ioRefresher).fetchLoginInfo(ServerId.Official)
            fail("expected IOException")
        } catch (e: IOException) {
        }
        assertEquals(0, ioRefresher.calls)
    }
}
