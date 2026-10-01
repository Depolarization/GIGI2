// ②解析覆盖：get_game_records 响应 canned JSON 经 GigiRepository.fetchGameRecords 全链路解码。
// 覆盖正常（10 条满额）/空列表/game_records 缺字段/单条缺可选字段/data 为 null，
// 钉死与 web types/api.ts 的字段语义一致（全可空、timestamp 字符串时间戳）。

package com.gigi.tcg.data.repo

import com.gigi.tcg.data.ServerId
import com.gigi.tcg.data.api.ApiError
import com.gigi.tcg.data.api.API_ERROR_KIND_RETCODE
import com.gigi.tcg.data.api.MihoyoClient
import com.gigi.tcg.domain.TtlCache
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private val testJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = false
}

private class RecordsTransport(private val responses: List<Pair<Int?, String?>>) : GigiApiTransport {
    var index = 0
    val callCount: Int get() = index

    override suspend fun fetchEnvelope(url: String, tag: String): RawEnvelope {
        assertTrue(url.contains("get_game_records"))
        assertTrue(url.contains("badge_uid=261958214"))
        val (retcode, raw) = responses[minOf(index, responses.lastIndex)]
        index += 1
        return RawEnvelope(retcode, if (retcode == 0) "OK" else "操作频繁，请稍后再试", raw)
    }
}

private class NoopDisk : WikiDiskStore {
    override suspend fun get(serverId: String): String? = null

    override suspend fun put(serverId: String, rawJson: String) = Unit
}

/**
 * 基准退避延时用**生产默认值**（[MihoyoClient.RETRY_BASE_DELAY_MS] = 700ms），与 GigiRepository
 * 自己的默认值同口径：只有 jitter 才是用例可调的那一半。
 * 🔴 别把它写成 0 —— 抖动用例断言 `elapsed in 700..1000`（= 基准 700 + jitter 0~300），
 * 基准归 0 后实测 elapsed 落在 0~300，用例必然红（2026-10-01 修的就是这处破窗）。
 */
private fun repo(transport: GigiApiTransport, jitterMs: Long = 0) =
    GigiRepository(
        transport,
        NoopDisk(),
        testJson,
        TtlCache(),
        retryDelayMs = MihoyoClient.RETRY_BASE_DELAY_MS,
        retryJitterMs = jitterMs,
    )

class GameRecordsParseTest {
    @Test
    fun `full payload decodes ten records with all fields`() = runTest {
        val recordsJson = (1..10).joinToString(",") { i ->
            """{"nickname":"对手$i","avatar_url":"https://img/$i.png","result":"Win",
                "trans_no":"261958214,253990148,20260$i","timestamp":"178651559$i",
                "ladder_score":{"score":27${i}0,"score_change":$i},
                "peak_score":{"score":100,"score_change":-$i}}""".replace("\n", "").replace(" ", "")
        }
        val transport = RecordsTransport(listOf(0 to """{"game_records":[$recordsJson]}"""))
        val data = repo(transport).fetchGameRecords("261958214", ServerId.Official)

        val records = data.gameRecords.orEmpty()
        assertEquals(10, records.size)
        val first = records.first()
        assertEquals("对手1", first.nickname)
        assertEquals("https://img/1.png", first.avatarUrl)
        assertEquals("Win", first.result)
        assertEquals("261958214,253990148,202601", first.transNo)
        assertEquals("1786515591", first.timestamp)
        assertEquals(2710, first.ladderScore?.score)
        assertEquals(1, first.ladderScore?.scoreChange)
        assertEquals(100, first.peakScore?.score)
        assertEquals(-1, first.peakScore?.scoreChange)
        assertEquals(1, transport.callCount)
    }

    @Test
    fun `empty list stays empty and missing game_records decodes to null`() = runTest {
        val empty = repo(RecordsTransport(listOf(0 to """{"game_records":[]}""")))
            .fetchGameRecords("261958214", ServerId.Official)
        assertEquals(0, empty.gameRecords.orEmpty().size)

        // 字段整体缺失：全可空模型 → null 而非崩溃（消费端 orEmpty 兜底）
        val missing = repo(RecordsTransport(listOf(0 to """{}""")))
            .fetchGameRecords("261958214", ServerId.Official)
        assertNull(missing.gameRecords)
    }

    @Test
    fun `record with only required-ish fields decodes with null optionals`() = runTest {
        val transport = RecordsTransport(
            listOf(0 to """{"game_records":[{"trans_no":"a,b,1"}]}"""),
        )
        val record = repo(transport).fetchGameRecords("261958214", ServerId.Official)
            .gameRecords.orEmpty().single()
        assertEquals("a,b,1", record.transNo)
        assertNull(record.nickname)
        assertNull(record.avatarUrl)
        assertNull(record.result)
        assertNull(record.timestamp)
        assertNull(record.ladderScore)
        assertNull(record.peakScore)
    }

    @Test
    fun `retcode zero with null data throws retcode error like web unwrap`() = runTest {
        val transport = RecordsTransport(listOf(0 to null))
        try {
            repo(transport).fetchGameRecords("261958214", ServerId.Official)
            fail("expected ApiError")
        } catch (e: ApiError) {
            assertEquals(API_ERROR_KIND_RETCODE, e.kind)
            assertEquals(0, e.retcode)
        }
    }

    @Test
    fun `retryable failure retries once with jittered delay within 700-1000ms`() = runTest {
        val transport = RecordsTransport(
            listOf(
                -500004 to null,
                0 to """{"game_records":[]}""",
            ),
        )
        val start = currentTime
        repo(transport, jitterMs = 300).fetchGameRecords("261958214", ServerId.Official)
        val elapsed = currentTime - start
        assertEquals(2, transport.callCount)
        assertTrue("elapsed=$elapsed", elapsed in 700..1000)
    }
}
