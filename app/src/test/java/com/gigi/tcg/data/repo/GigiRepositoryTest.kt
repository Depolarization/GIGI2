// GigiRepository 缓存层测试：内存 TTL / 磁盘双层 / 键含服务器 / LruCache / 失效。
// 无 MockWebServer 依赖：用本地 stub（GigiApiTransport / WikiDiskStore 假实现）+ 可注入时钟。

package com.gigi.tcg.data.repo

import com.gigi.tcg.data.ServerId
import com.gigi.tcg.data.api.MihoyoClient
import com.gigi.tcg.data.model.EntryPageData
import com.gigi.tcg.domain.TtlCache
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

private val testJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = false
}

private class RecordingTransport : GigiApiTransport {
    val calls = mutableListOf<Pair<String, String>>()

    var retcode: Int? = 0
    var message: String? = "OK"
    var raw: String? = "{}"

    override suspend fun fetchEnvelope(url: String, tag: String): RawEnvelope {
        calls += url to tag
        return RawEnvelope(retcode, message, raw)
    }

    fun callsTo(urlFragment: String): Int = calls.count { it.first.contains(urlFragment) }
}

private class FakeDisk : WikiDiskStore {
    val entries = mutableMapOf<String, String>()
    var puts = 0

    override suspend fun get(serverId: String): String? = entries[serverId]

    override suspend fun put(serverId: String, rawJson: String) {
        entries[serverId] = rawJson
        puts += 1
    }
}

/** 纯 JVM 的 DetailCacheStore（android.util.LruCache 在单测里是 not-mocked 桩） */
private class MapDetailCache : DetailCacheStore {
    val entries = mutableMapOf<String, EntryPageData>()

    override fun get(key: String): EntryPageData? = entries[key]

    override fun put(key: String, value: EntryPageData) {
        entries[key] = value
    }
}

class GigiRepositoryTest {
    @Test
    fun `home page hits memory cache without refetching and re-fetches after ttl`() = runTest {
        var nowMs = 1_000_000L
        val transport = RecordingTransport().apply {
            raw = """{"page_info":{"nickname":"Oscuro"}}"""
        }
        val repo = GigiRepository(
            transport,
            FakeDisk(),
            testJson,
            TtlCache { nowMs },
            retryDelayMs = 0,
            now = { nowMs },
        )

        val first = repo.fetchMyHomePageCached("261958214", ServerId.Official)
        assertEquals("Oscuro", first.pageInfo?.nickname)
        val second = repo.fetchMyHomePageCached("261958214", ServerId.Official)
        assertSame(first, second)
        assertEquals(1, transport.callsTo("my_home_page"))

        // 45 秒 TTL：30s 内仍命中，越过 45s 过期重取
        nowMs += GigiRepository.HOME_CACHE_TTL_MS - 15_000
        repo.fetchMyHomePageCached("261958214", ServerId.Official)
        assertEquals(1, transport.callsTo("my_home_page"))
        nowMs += 15_001
        repo.fetchMyHomePageCached("261958214", ServerId.Official)
        assertEquals(2, transport.callsTo("my_home_page"))
    }

    @Test
    fun `private keys embed server uid so other server never reads stale data`() = runTest {
        val transport = RecordingTransport().apply {
            raw = """{"page_info":{"nickname":"Oscuro"}}"""
        }
        val repo = GigiRepository(transport, FakeDisk(), testJson, TtlCache(), retryDelayMs = 0)

        repo.fetchMyHomePageCached("261958214", ServerId.Official)
        repo.fetchMyHomePageCached("261958214", ServerId.Channel)
        assertEquals(2, transport.callsTo("my_home_page"))
        assertEquals(1, transport.calls.count { it.first.contains("badge_region=cn_qd01") })

        // force 绕过缓存
        repo.fetchMyHomePageCached("261958214", ServerId.Official, force = true)
        assertEquals(3, transport.callsTo("my_home_page"))
    }

    @Test
    fun `rank cache isolates tab and invalidation drops the entry`() = runTest {
        val transport = RecordingTransport().apply { raw = """{"rank_infos":[]}""" }
        val memory = TtlCache()
        val repo = GigiRepository(transport, FakeDisk(), testJson, memory, retryDelayMs = 0)

        repo.fetchRankCached("261958214", ServerId.Official, RankTab.Peak)
        repo.fetchRankCached("261958214", ServerId.Official, RankTab.Competition)
        assertEquals(1, transport.callsTo("peak_rank"))
        assertEquals(1, transport.callsTo("/rank?"))

        repo.fetchRankCached("261958214", ServerId.Official, RankTab.Peak)
        assertEquals(1, transport.callsTo("peak_rank"))

        repo.invalidateRankCache("261958214", ServerId.Official)
        assertNull(memory.cacheGet("gigi:private:cn_gf01:261958214:rank:peak:v1"))
        repo.fetchRankCached("261958214", ServerId.Official, RankTab.Peak)
        assertEquals(2, transport.callsTo("peak_rank"))

        // 登出清私有缓存，公开键保留
        memory.cacheSet(GigiRepository.WIKI_MEMORY_KEY, WikiListData(), 10_000)
        repo.clearPrivateCache()
        assertNull(memory.cacheGet("gigi:private:cn_gf01:261958214:rank:peak:v1"))
        assertNotNull(memory.cacheGet(GigiRepository.WIKI_MEMORY_KEY))
    }

    @Test
    fun `card stats uses 5 minute ttl memory only`() = runTest {
        var nowMs = 0L
        val transport = RecordingTransport().apply { raw = """{"stats":{},"card_list":[]}""" }
        val repo = GigiRepository(
            transport,
            FakeDisk(),
            testJson,
            TtlCache { nowMs },
            retryDelayMs = 0,
            now = { nowMs },
        )
        repo.fetchGcgCardListCached("261958214", ServerId.Official)
        nowMs = GigiRepository.CARD_STATS_CACHE_TTL_MS - 1
        repo.fetchGcgCardListCached("261958214", ServerId.Official)
        assertEquals(1, transport.callsTo("gcg/cardList"))
        nowMs = GigiRepository.CARD_STATS_CACHE_TTL_MS
        repo.fetchGcgCardListCached("261958214", ServerId.Official)
        assertEquals(2, transport.callsTo("gcg/cardList"))
    }

    @Test
    fun `wiki list reads disk before network and writes back after fetch`() = runTest {
        val disk = FakeDisk()
        val transport = RecordingTransport().apply {
            raw = """{"list":[{"id":1,"name":"角色牌"}]}"""
        }
        val repo = GigiRepository(transport, disk, testJson, TtlCache(), retryDelayMs = 0)

        // 盘上已有 → 不发请求，且回填内存
        disk.entries[ServerId.DEFAULT.id] = """{"list":[{"id":42,"name":"杜林"}]}"""
        val fromDisk = repo.fetchCardWikiListCached()
        assertEquals(42, fromDisk.list?.first()?.id)
        assertEquals(0, transport.calls.size)

        // 请求路径 → 双层回写
        val memory = TtlCache()
        val repo2 = GigiRepository(transport, disk, testJson, memory, retryDelayMs = 0)
        disk.entries.clear()
        val fresh = repo2.fetchCardWikiListCached()
        assertEquals(1, fresh.list?.size)
        assertEquals(1, transport.callsTo("content/list"))
        assertEquals(1, disk.puts)
        assertNotNull(disk.entries[ServerId.DEFAULT.id])
        assertNotNull(memory.cacheGet(GigiRepository.WIKI_MEMORY_KEY))

        // force → 绕双层再请求一次
        repo2.fetchCardWikiListCached(force = true)
        assertEquals(2, transport.callsTo("content/list"))
    }

    @Test
    fun `card detail passes throttle tag to client and reuses lru cache`() = runTest {
        val transport = RecordingTransport().apply {
            raw = """{"page":{"modules":[]}}"""
        }
        val repo = GigiRepository(
            transport,
            FakeDisk(),
            testJson,
            TtlCache(),
            retryDelayMs = 0,
            detailCache = MapDetailCache(),
        )

        repo.fetchCardDetail(1001)
        assertEquals(MihoyoClient.TAG_DETAIL, transport.calls.last().second)
        assertEquals(1, transport.callsTo("entry_page"))

        // 同 id 命中 LruCache：不再请求（tag 也不会再打到 client，由 repo 层拦截）
        repo.fetchCardDetail(1001)
        assertEquals(1, transport.callsTo("entry_page"))
        // 不同 id 不串缓存
        repo.fetchCardDetail(1002)
        assertEquals(2, transport.callsTo("entry_page"))
    }

    @Test
    fun `uncached endpoints always refetch`() = runTest {
        val transport = RecordingTransport().apply {
            raw = """{"game_uid":"261958214"}"""
        }
        val repo = GigiRepository(transport, FakeDisk(), testJson, TtlCache(), retryDelayMs = 0)
        val info = repo.fetchLoginInfo(ServerId.Official)
        repo.fetchLoginInfo(ServerId.Official)
        assertEquals("261958214", info.gameUid)
        assertEquals(2, transport.calls.size)
        assertEquals("", transport.calls.last().second)
    }
}
