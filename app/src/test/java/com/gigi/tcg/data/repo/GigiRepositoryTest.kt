// GigiRepository 缓存层测试：内存 TTL / 磁盘双层 / 键含服务器 / LruCache / 失效。
// 无 MockWebServer 依赖：用本地 stub（GigiApiTransport / WikiDiskStore 假实现）+ 可注入时钟。

package com.gigi.tcg.data.repo

import com.gigi.tcg.data.ServerId
import com.gigi.tcg.data.api.MihoyoClient
import com.gigi.tcg.data.model.EntryPageData
import com.gigi.tcg.domain.TtlCache
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

/** V37-I：记录「按 uid 取 cookie」链路的桩——url / tag / 归属 uid 三件套 */
private class RecordingUidTransport : GigiApiTransport {
    val calls = mutableListOf<Triple<String, String, String?>>()

    var envelopes: List<RawEnvelope> = listOf(RawEnvelope(0, "OK", "{}"))

    override suspend fun fetchEnvelope(url: String, tag: String): RawEnvelope =
        fetchEnvelope(url, tag, null)

    override suspend fun fetchEnvelope(url: String, tag: String, uid: String?): RawEnvelope {
        val envelope = envelopes[minOf(calls.size, envelopes.lastIndex)]
        calls += Triple(url, tag, uid)
        return envelope
    }
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

    // V36/1 任务 D（只读复查结论）：私有键由 privateKey() 生成 = `gigi:private:{server}:{uid}:{suffix}`，
    // uid 恒在键内 ⇒ 账号切换天然读不到旧 uid 的 basicInfo，无需把 gcg-basic-info:v1 补进
    // invalidateMyPageCache（那 4 个键服务的是「同一 uid 主动下拉刷新」）。此测试钉死该不变式。
    @Test
    fun `private keys embed uid so account switch never reads another accounts basic info`() = runTest {
        val transport = RecordingTransport()
        val memory = TtlCache()
        val repo = GigiRepository(transport, FakeDisk(), testJson, memory, retryDelayMs = 0)
        transport.raw = """{"avatar_card_num_total":147,"action_card_num_total":941}"""

        val first = repo.fetchGcgBasicInfo("261958214", ServerId.Official)
        assertNotNull(first)
        assertNotNull(memory.cacheGet("gigi:private:cn_gf01:261958214:" + GigiRepository.BASIC_INFO_SUFFIX))

        transport.raw = """{"avatar_card_num_total":200,"action_card_num_total":300}"""
        val second = repo.fetchGcgBasicInfo("999888777", ServerId.Official)
        assertEquals(147, first?.avatarCardNumTotal)
        assertEquals(200, second?.avatarCardNumTotal)
        assertEquals(2, transport.callsTo("gcg/basicInfo"))
        // 旧账号仍命中自己的键（未被新账号覆盖）
        assertNotNull(memory.cacheGet("gigi:private:cn_gf01:261958214:" + GigiRepository.BASIC_INFO_SUFFIX))
        assertNotNull(memory.cacheGet("gigi:private:cn_gf01:999888777:" + GigiRepository.BASIC_INFO_SUFFIX))

        // 登出走前缀清理：私有键一律作废，公开图鉴键保留
        repo.clearPrivateCache()
        assertNull(memory.cacheGet("gigi:private:cn_gf01:261958214:" + GigiRepository.BASIC_INFO_SUFFIX))
        assertNull(memory.cacheGet("gigi:private:cn_gf01:999888777:" + GigiRepository.BASIC_INFO_SUFFIX))

        // 同一 uid 再次拉取：走网络重取（TTL 缓存不跨账号串味）
        repo.fetchGcgBasicInfo("261958214", ServerId.Official)
        assertEquals(3, transport.callsTo("gcg/basicInfo"))
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

    // ===== V37-I 任务 A：私有数据请求必须带「目标账户自己的」凭据 =====
    // 真机根因：URL 的 uid 是墨邪、Cookie 却是激活账户 Oscuro 的 ⇒ 服务端按 Cookie 判身份，
    // 非激活账户永远拿不到资料。repo 层的功能锁 = 私有端点请求归属 uid 与 URL 一致。

    @Test
    fun `every private endpoint requests with the target uid`() = runTest {
        val transport = RecordingUidTransport()
        val repo = GigiRepository(transport, FakeDisk(), testJson, TtlCache(), retryDelayMs = 0)
        val other = "157777921"

        repo.fetchMyHomePage(other, ServerId.Official)
        repo.fetchGameRecords(other, ServerId.Official)
        repo.fetchGcgCardList(other, ServerId.Official)
        repo.fetchGcgDeckList(other, ServerId.Official)
        repo.fetchGcgCardBackList(other, ServerId.Official)
        repo.fetchGcgMatchList(other, ServerId.Official)
        repo.fetchGcgChallengeSchedule(other, ServerId.Official)
        repo.fetchGcgChallengeRecord(other, ServerId.Official, 7)
        repo.fetchGcgBasicInfo(other, ServerId.Official)

        assertEquals(9, transport.calls.size)
        assertTrue(
            "请求归属 uid 必须逐个等于目标 uid（不得回落激活账户）",
            transport.calls.all { it.third == other },
        )
        assertTrue(
            "URL 里的 role_id 与 Cookie 归属必须同源（否则就是本次 bug 的形态）",
            transport.calls.all { it.first.contains(other) },
        )
    }

    @Test
    fun `public and login endpoints keep the active-account path`() = runTest {
        val transport = RecordingUidTransport().apply {
            envelopes = listOf(RawEnvelope(0, "OK", "{}"))
        }
        val repo = GigiRepository(
            transport,
            FakeDisk(),
            testJson,
            TtlCache(),
            retryDelayMs = 0,
            detailCache = MapDetailCache(),
        )

        repo.fetchLoginInfo(ServerId.Official)
        repo.fetchCardWikiListCached()
        repo.fetchCardDetail(1001)
        repo.fetchOtherHomePage("SomeCode", "261958214", ServerId.Official)

        assertTrue(
            "公开/登录态端点不传 uid ⇒ 走旧语义（激活账户），行为与 V37-I 之前完全一致",
            transport.calls.all { it.third == null },
        )
    }

    @Test
    fun `auth refresh replay still carries the same uid`() = runTest {
        val transport = RecordingUidTransport().apply {
            envelopes = listOf(
                RawEnvelope(-100, "please login", null),
                RawEnvelope(0, "OK", """{"page_info":{"nickname":"墨邪"}}"""),
            )
        }
        var refreshes = 0
        val repo = GigiRepository(
            transport,
            FakeDisk(),
            testJson,
            TtlCache(),
            retryDelayMs = 0,
            sessionRefresher = object : SessionRefresher {
                override suspend fun refreshActive(): Boolean {
                    refreshes += 1
                    return true
                }
            },
        )

        val data = repo.fetchMyHomePage("157777921", ServerId.Official)

        assertEquals("墨邪", data.pageInfo?.nickname)
        assertEquals(1, refreshes)
        assertEquals(
            "鉴权失败→续命→原样重放：重放必须带**同一个** uid（既有静默续命语义不动）",
            listOf<String?>("157777921", "157777921"),
            transport.calls.map { it.third },
        )
    }

    /** 源码闸门（剥注释）：uid 参数是**可选**的（默认 null = 旧行为），且真的流到了 transport */
    @Test
    fun `source gate - uid is an optional parameter threaded to the transport`() {
        val src = codeOnly(File("src/main/java/com/gigi/tcg/data/repo/GigiRepository.kt").readText())

        assertTrue(
            "私有 get 的 uid 参数必须带默认值（public 老调用点语义不动）",
            Regex("""fun <T> get\([\s\S]{0,160}uid: String\? = null""").containsMatchIn(src),
        )
        assertTrue("fetchAndDecode 必须把 uid 交给 transport", src.contains("transport.fetchEnvelope(url, tag, uid)"))
        assertTrue(
            "transport 接口的 uid 重载必须以回落两参版为默认（既有 stub 不感知）",
            Regex("""fetchEnvelope\(url: String, tag: String, uid: String\?\): RawEnvelope =\s*\n?\s*fetchEnvelope\(url, tag\)""").containsMatchIn(src),
        )
        assertTrue(
            "真身 transport 必须把 uid 递给 client",
            src.contains("client.requestEnvelope(url, JsonElement.serializer(), uid)"),
        )
    }

    private fun codeOnly(src: String): String = src
        .replace(Regex("""(?s)/\*.*?\*/"""), " ")
        .replace(Regex("""(?m)//[^\n]*"""), " ")
}
