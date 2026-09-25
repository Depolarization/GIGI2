// GigiRepository：对齐 Web 版 api/mihoyo.ts（接口方法）+ api/pageCache.ts（TTL 缓存策略），
// 落地设计文档 §2.4 双层缓存：
// - 内存层：domain.TtlCache 实例（卡统 5min / 资料卡+最近对局 45s / 排行榜 3min），
//   私有数据只存内存；键一律 gigi:private:<server>:<uid>:<suffix>，换服绝不误读。
// - 磁盘层：仅公开图鉴列表走 WikiDiskCache（先读盘再请求并回写）。
// - 卡面详情：android.util.LruCache(200)（对齐 CardCoverDialog DETAIL_CACHE_MAX），
//   1 秒节流由 MihoyoClient tag=TAG_DETAIL 承担，本层只在调用点打 tag。
// 传输经 [GigiApiTransport] 最小接口注入（真身 MihoyoClientEnvelopeTransport），便于本地 stub 测试。
// 鉴权续命：[get] 是全部接口的唯一出口——retcode -100/-101（isAuthFailureError 集中判据）→
// [SessionRefresher] 单飞续命一次 → 原请求原样重放；续命失败/二次失败抛错上抛，绝不递归循环。

package com.gigi.tcg.data.repo

import android.util.LruCache
import androidx.annotation.VisibleForTesting
import com.gigi.tcg.data.ServerId
import com.gigi.tcg.data.api.API_ERROR_KIND_NETWORK
import com.gigi.tcg.data.api.API_ERROR_KIND_RETCODE
import com.gigi.tcg.data.api.ApiError
import com.gigi.tcg.data.api.CARD_INFO_URL
import com.gigi.tcg.data.api.MihoyoClient
import com.gigi.tcg.data.api.RETRYABLE_RETCODES
import com.gigi.tcg.data.api.cardDetailUrl
import com.gigi.tcg.data.api.cardListUrl
import com.gigi.tcg.data.api.competitionRankUrl
import com.gigi.tcg.data.api.gameRecordsUrl
import com.gigi.tcg.data.api.isAuthFailureError
import com.gigi.tcg.data.api.myHomePageUrl
import com.gigi.tcg.data.api.otherHomePageUrl
import com.gigi.tcg.data.api.peakRankUrl
import com.gigi.tcg.data.api.userInfoUrl
import com.gigi.tcg.data.model.EntryPageData
import com.gigi.tcg.data.model.GameRecordsData
import com.gigi.tcg.data.model.GcgCardListData
import com.gigi.tcg.data.model.LoginInfoData
import com.gigi.tcg.data.model.MyHomePageData
import com.gigi.tcg.data.model.OtherHomePageData
import com.gigi.tcg.data.model.RankData
import com.gigi.tcg.data.model.WikiChannelNode
import com.gigi.tcg.domain.TtlCache
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** 未解析的接口信封：data 保留原始 JSON，由 Repository 统一解码（KSerializer 泛型穿透用） */
data class RawEnvelope(val retcode: Int?, val message: String?, val raw: String?)

/** 传输层最小接口：真身 = MihoyoClient（经 MihoyoClientEnvelopeTransport 适配信封） */
interface GigiApiTransport {
    suspend fun fetchEnvelope(url: String, tag: String = ""): RawEnvelope
}

/** 图鉴磁盘层最小接口：真身 = WikiDiskCache（直接实现，测试可用假实现替换） */
interface WikiDiskStore {
    suspend fun get(serverId: String): String?
    suspend fun put(serverId: String, rawJson: String)
}

/** MihoyoClient 适配器：Cookie 注入 / 节流 / 网络归因都在 client 内部完成。
 *  data 以 JsonElement 形态取出再转原始 JSON 串，避免在 client 层提前解码丢失泛型形态。 */
class MihoyoClientEnvelopeTransport(private val client: MihoyoClient) : GigiApiTransport {
    override suspend fun fetchEnvelope(url: String, tag: String): RawEnvelope {
        val envelope = client.requestEnvelope(url, JsonElement.serializer())
        return RawEnvelope(
            retcode = envelope.first,
            message = envelope.second,
            raw = envelope.third?.toString(),
        )
    }
}

/** 图鉴列表响应体（fetchCardWikiList 的 data 形态：{ list?: WikiChannelNode[] }） */
@Serializable
data class WikiListData(
    @SerialName("list") val list: List<WikiChannelNode>? = null,
)

/** 排行榜 tab：对齐 pageCache.ts fetchRankCached 的 'peak' | 'competition' */
enum class RankTab(val key: String) {
    Peak("peak"),
    Competition("competition"),
}

/** 卡面详情内存缓存抽象：生产实现为 android.util.LruCache(200)（JVM 单测下 android.util 不可用，测试注入纯 Map 实现） */
interface DetailCacheStore {
    fun get(key: String): EntryPageData?
    fun put(key: String, value: EntryPageData)
}

private class LruDetailCache(maxSize: Int) : DetailCacheStore {
    private val cache = LruCache<String, EntryPageData>(maxSize)

    override fun get(key: String): EntryPageData? = cache.get(key)

    override fun put(key: String, value: EntryPageData) {
        cache.put(key, value)
    }
}

/** 静默续命端口：真身接线 = CredentialStore 取当前账户 → AuthManager.refreshStoredSession；
 *  返回 true 表示凭据已更新（调用方据以原样重放请求）；false = 未更新。
 *  续命网络往返不经本 Repository，天然无递归重试风险。 */
interface SessionRefresher {
    suspend fun refreshActive(): Boolean
}

class GigiRepository(
    private val transport: GigiApiTransport,
    private val wikiDiskCache: WikiDiskStore,
    private val json: Json,
    private val memoryCache: TtlCache = TtlCache(),
    private val retryDelayMs: Long = MihoyoClient.RETRY_DELAY_MS,
    private val retryJitterMs: Long = MihoyoClient.RETRY_JITTER_SPAN_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val detailCache: DetailCacheStore = LruDetailCache(DETAIL_CACHE_MAX),
    private val sessionRefresher: SessionRefresher? = null,
) {

    // ---- mihoyo.ts 原始接口（无缓存） ----

    /** 登录态检测：成功时通过 data.game_uid 取得当前用户 UID */
    suspend fun fetchLoginInfo(server: ServerId): LoginInfoData =
        get(userInfoUrl(server), LoginInfoData.serializer(), "")

    /** 最近对局记录（服务端最多返回最近 10 条） */
    suspend fun fetchGameRecords(uid: String, server: ServerId): GameRecordsData =
        get(gameRecordsUrl(uid, server), GameRecordsData.serializer(), "")

    /** 我的主页（资料卡数据） */
    suspend fun fetchMyHomePage(uid: String, server: ServerId): MyHomePageData =
        get(myHomePageUrl(uid, server), MyHomePageData.serializer(), "")

    /** 他人主页（玩家详情弹窗数据；code 由 generateCode 生成） */
    suspend fun fetchOtherHomePage(code: String, myUid: String, server: ServerId): OtherHomePageData =
        get(otherHomePageUrl(code, myUid, server), OtherHomePageData.serializer(), "")

    /** 巅峰积分排行榜 */
    suspend fun fetchPeakRank(uid: String, server: ServerId): RankData =
        get(peakRankUrl(uid, server), RankData.serializer(), "")

    /** 赛事积分排行榜 */
    suspend fun fetchCompetitionRank(uid: String, server: ServerId): RankData =
        get(competitionRankUrl(uid, server), RankData.serializer(), "")

    /** 个人卡牌使用统计 */
    suspend fun fetchGcgCardList(uid: String, server: ServerId): GcgCardListData =
        get(cardListUrl(uid, server), GcgCardListData.serializer(), "")

    /** 卡面详情（公开接口）：LRU 200 命中即复用，未命中打 TAG_DETAIL 交 client 节流 */
    suspend fun fetchCardDetail(entryPageId: Int): EntryPageData {
        val key = entryPageId.toString()
        detailCache.get(key)?.let { return it }
        val fresh = get(cardDetailUrl(entryPageId), EntryPageData.serializer(), MihoyoClient.TAG_DETAIL)
        detailCache.put(key, fresh)
        return fresh
    }

    // ---- pageCache.ts 缓存策略 ----

    /** 图鉴列表：内存 → 磁盘（§2.4 唯一落盘项）→ 请求并双层回写；force 绕过 */
    suspend fun fetchCardWikiListCached(force: Boolean = false): WikiListData {
        if (!force) {
            (memoryCache.cacheGet(WIKI_MEMORY_KEY) as? WikiListData)?.let { return it }
            val raw = wikiDiskCache.get(wikiServerKey())
            if (raw != null) {
                val diskHit = decode(raw, WikiListData.serializer())
                    ?: throw ApiError(API_ERROR_KIND_NETWORK, "响应不是有效的 JSON")
                memoryCache.cacheSet(WIKI_MEMORY_KEY, diskHit, WIKI_CACHE_TTL_MS)
                return diskHit
            }
        }
        val fresh = fetchCardWikiList()
        memoryCache.cacheSet(WIKI_MEMORY_KEY, fresh, WIKI_CACHE_TTL_MS)
        wikiDiskCache.put(wikiServerKey(), json.encodeToString(WikiListData.serializer(), fresh))
        return fresh
    }

    /** 个人卡牌使用统计（5 分钟，按 服务器+UID 隔离） */
    suspend fun fetchGcgCardListCached(uid: String, server: ServerId, force: Boolean = false): GcgCardListData =
        cachedPrivate(uid, server, "card-stats:v1", CARD_STATS_CACHE_TTL_MS, force) {
            fetchGcgCardList(uid, server)
        }

    /** 对局主页 · 资料卡（45 秒） */
    suspend fun fetchMyHomePageCached(uid: String, server: ServerId, force: Boolean = false): MyHomePageData =
        cachedPrivate(uid, server, "home-page:v1", HOME_CACHE_TTL_MS, force) {
            fetchMyHomePage(uid, server)
        }

    /** 对局主页 · 最近对局（45 秒） */
    suspend fun fetchGameRecordsCached(uid: String, server: ServerId, force: Boolean = false): GameRecordsData =
        cachedPrivate(uid, server, "game-records:v1", HOME_CACHE_TTL_MS, force) {
            fetchGameRecords(uid, server)
        }

    /** 排行榜（3 分钟）：tab 为 Peak / Competition */
    suspend fun fetchRankCached(uid: String, server: ServerId, tab: RankTab, force: Boolean = false): RankData =
        cachedPrivate(uid, server, "rank:${tab.key}:v1", RANK_CACHE_TTL_MS, force) {
            if (tab == RankTab.Peak) fetchPeakRank(uid, server) else fetchCompetitionRank(uid, server)
        }

    // ---- 失效（对齐 pageCache.ts invalidate*；登出时 clearPrivateCache） ----

    fun invalidateWikiCache() {
        memoryCache.cacheDelete(WIKI_MEMORY_KEY)
    }

    fun invalidateCardStatsCache(uid: String, server: ServerId) {
        memoryCache.cacheDelete(privateKey(uid, server, "card-stats:v1"))
    }

    fun invalidateHomeCache(uid: String, server: ServerId) {
        memoryCache.cacheDelete(privateKey(uid, server, "home-page:v1"))
        memoryCache.cacheDelete(privateKey(uid, server, "game-records:v1"))
    }

    fun invalidateRankCache(uid: String, server: ServerId) {
        memoryCache.cacheDelete(privateKey(uid, server, "rank:peak:v1"))
        memoryCache.cacheDelete(privateKey(uid, server, "rank:competition:v1"))
    }

    /** 登出/凭据失效：清全部私有内存缓存（磁盘只有公开图鉴数据，保留） */
    fun clearPrivateCache() = memoryCache.clearPrivateCache()

    /** 接线自检：真身容器注入后应为 true——单测经 AppContainer 真实构造路径断言（防"机制写了没接线"回归） */
    @VisibleForTesting
    internal fun isSessionRefreshWired(): Boolean = sessionRefresher != null

    // ---- 内部 ----

    // 续命单飞态：generation 每次续命尝试（无论成败）自增；失败请求以"发出前的 generation"判定
    // 自己是否属于同波——同波只允许一次真实续命，等待者复用其结果（含失败结果），绝不重复打接口。
    private val refreshMutex = Mutex()

    @Volatile
    private var refreshGeneration = 0L
    private var lastRefreshSucceeded = false

    private suspend fun fetchCardWikiList(): WikiListData =
        get(CARD_INFO_URL, WikiListData.serializer(), "")

    /** 请求唯一出口：鉴权失败（isAuthFailureError，与 AppGate 同一判据）→ 静默续命 →
     *  同一 URL 原样重放一次。续命失败抛原错误；重放在 try 之外，二次失败直接上抛（最多重试一次）。 */
    private suspend fun <T> get(url: String, serializer: KSerializer<T>, tag: String): T {
        val observedGeneration = refreshGeneration
        try {
            return fetchAndDecode(url, serializer, tag)
        } catch (e: ApiError) {
            if (!isAuthFailureError(e) || !refreshSessionOnce(observedGeneration)) throw e
        }
        return fetchAndDecode(url, serializer, tag)
    }

    /** 单飞续命：observedGeneration 已被推进 = 同波已完成一次续命尝试，直接复用其结果；
     *  否则在锁内真正执行一次（并发调用者经 Mutex 串行等待）。续命自身异常折算 false，由调用方抛原错误。 */
    private suspend fun refreshSessionOnce(observedGeneration: Long): Boolean = refreshMutex.withLock {
        if (refreshGeneration == observedGeneration) {
            lastRefreshSucceeded = try {
                sessionRefresher?.refreshActive() == true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                false
            }
            refreshGeneration++
        }
        lastRefreshSucceeded
    }

    /** 对齐 mihoyo.ts unwrap：retcode 判定 + RETRYABLE 等待 700ms + 0-300ms 随机抖动
     *  自动重试一次（抖动避免并发请求同刻重发再次互撞）+ data 解码 */
    private suspend fun <T> fetchAndDecode(url: String, serializer: KSerializer<T>, tag: String): T {
        var envelope = transport.fetchEnvelope(url, tag)
        if (RETRYABLE_RETCODES.contains(envelope.retcode)) {
            delay(retryDelayMs + if (retryJitterMs > 0) Random.nextLong(0L, retryJitterMs + 1) else 0L)
            envelope = transport.fetchEnvelope(url, tag)
        }
        val (retcode, message, raw) = envelope
        if (retcode != 0 || raw == null) {
            throw ApiError(
                API_ERROR_KIND_RETCODE,
                message?.takeIf { it.isNotEmpty() } ?: "接口返回 retcode=${retcode ?: 0}",
                retcode,
            )
        }
        return decode(raw, serializer)
            ?: throw ApiError(API_ERROR_KIND_NETWORK, "响应不是有效的 JSON")
    }

    private fun <T> decode(raw: String, serializer: KSerializer<T>): T? =
        try {
            json.decodeFromString(serializer, raw)
        } catch (e: SerializationException) {
            null
        }

    private suspend fun <T> cachedPrivate(
        uid: String,
        server: ServerId,
        suffix: String,
        ttlMs: Long,
        force: Boolean,
        fetch: suspend () -> T,
    ): T {
        val key = privateKey(uid, server, suffix)
        if (!force) {
            @Suppress("UNCHECKED_CAST")
            (memoryCache.cacheGet(key) as? T)?.let { return it }
        }
        val fresh = fetch()
        memoryCache.cacheSet(key, fresh, ttlMs)
        return fresh
    }

    private fun privateKey(uid: String, server: ServerId, suffix: String): String =
        "$PRIVATE_PREFIX${server.id}:$uid:$suffix"

    /** 图鉴为公开数据、与服务器无关；磁盘键仍带服务器标识（§2.4 键一律含服务器） */
    private fun wikiServerKey(): String = ServerId.DEFAULT.id

    companion object {
        const val PRIVATE_PREFIX: String = "gigi:private:"
        const val WIKI_MEMORY_KEY: String = "gigi:public:wiki-list:v1"

        const val WIKI_CACHE_TTL_MS: Long = 60 * 60 * 1000L
        const val CARD_STATS_CACHE_TTL_MS: Long = 5 * 60 * 1000L
        const val HOME_CACHE_TTL_MS: Long = 45 * 1000L
        const val RANK_CACHE_TTL_MS: Long = 3 * 60 * 1000L

        /** 对齐 CardCoverDialog 的 DETAIL_CACHE_MAX */
        const val DETAIL_CACHE_MAX: Int = 200
    }
}
