// 移植 web/src/api/__tests__/retcode.test.ts 中依赖 mihoyo.ts 的用例（"限流自动重试" 3 it）。
// retcode 纯判定/文案用例已由 ApiErrorTest 覆盖；本文件覆盖 unwrap 流程：
// 命中限流码 → 重试一次成功；重试后仍失败 → 抛带限流码的 retcode 错误；-100 不重试。
// 无 MockWebServer：本地 stub GigiApiTransport（retryDelayMs=0 替代 vitest 假定时器）。

package com.gigi.tcg.data.api

import com.gigi.tcg.data.ServerId
import com.gigi.tcg.data.model.MyHomePageData
import com.gigi.tcg.data.repo.GigiApiTransport
import com.gigi.tcg.data.repo.GigiRepository
import com.gigi.tcg.data.repo.RawEnvelope
import com.gigi.tcg.data.repo.WikiDiskStore
import com.gigi.tcg.domain.TtlCache
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val testJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = false
}

private class ScriptedTransport(vararg responses: Pair<Int?, String?>) : GigiApiTransport {
    var index = 0
    val callCount: Int get() = index

    private val responses =
        responses.map { (retcode, raw) ->
            RawEnvelope(retcode, if (retcode == 0) "OK" else "操作频繁，请稍后再试", raw)
        }

    override suspend fun fetchEnvelope(url: String, tag: String): RawEnvelope {
        val response = responses[minOf(index, responses.lastIndex)]
        index += 1
        return response
    }
}

private class UnusedDisk : WikiDiskStore {
    override suspend fun get(serverId: String): String? = null

    override suspend fun put(serverId: String, rawJson: String) = Unit
}

class RetcodeFlowTest {
    private fun repo(transport: GigiApiTransport) =
        GigiRepository(transport, UnusedDisk(), testJson, TtlCache(), retryDelayMs = 0)

    @Test
    fun `first hit of rate-limit code retries once and returns data`() = runTest {
        val transport = ScriptedTransport(
            -500004 to null,
            0 to """{"page_info":{"nickname":"Oscuro"}}""",
        )
        val data = repo(transport).fetchMyHomePage("261958214", ServerId.Official)
        assertEquals("Oscuro", data.pageInfo?.nickname)
        assertEquals(2, transport.callCount)
    }

    @Test
    fun `failure after retry throws retcode error carrying the throttle code`() = runTest {
        val transport = ScriptedTransport(-500004 to null)
        try {
            repo(transport).fetchMyHomePage("261958214", ServerId.Official)
            throw AssertionError("expected ApiError")
        } catch (e: ApiError) {
            assertEquals(API_ERROR_KIND_RETCODE, e.kind)
            assertEquals(-500004, e.retcode)
            assertEquals("操作频繁，请稍后再试", e.message)
        }
        // 只重试一次，不无限重发
        assertEquals(2, transport.callCount)
    }

    @Test
    fun `credential expiry code fails fast without retry`() = runTest {
        val transport = ScriptedTransport(-100 to null)
        try {
            repo(transport).fetchMyHomePage("261958214", ServerId.Official)
            throw AssertionError("expected ApiError")
        } catch (e: ApiError) {
            assertEquals(API_ERROR_KIND_RETCODE, e.kind)
            assertEquals(-100, e.retcode)
        }
        assertEquals(1, transport.callCount)
        assertTrue(-100 !in RETRYABLE_RETCODES)
    }

    @Test
    fun `retcode zero with missing data still throws retcode error`() = runTest {
        val transport = ScriptedTransport(0 to null)
        try {
            repo(transport).fetchMyHomePage("261958214", ServerId.Official)
            throw AssertionError("expected ApiError")
        } catch (e: ApiError) {
            assertEquals(API_ERROR_KIND_RETCODE, e.kind)
            assertEquals(0, e.retcode)
        }
        assertEquals(1, transport.callCount)
    }

    @Test
    fun `decodable model ignores unknown keys`() = runTest {
        val transport = ScriptedTransport(
            0 to """{"unexpected":true,"page_info":{"nickname":"Oscuro","avatar_url":"x"}}""",
        )
        val data: MyHomePageData = repo(transport).fetchMyHomePage("261958214", ServerId.Official)
        assertEquals("Oscuro", data.pageInfo?.nickname)
    }
}
