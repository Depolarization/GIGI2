// GigiRepository：对齐 Web 版 api/mihoyo.ts（接口方法）+ api/pageCache.ts（TTL 缓存策略），
// 落地设计文档 §2.4 双层缓存：
// - 内存层：domain.TtlCache 实例（卡统 5min / 资料卡+最近对局 45s / 排行榜 3min / 社区资料 24h），
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
import com.gigi.tcg.R
import com.gigi.tcg.data.ServerId
import com.gigi.tcg.data.api.API_ERROR_KIND_NETWORK
import com.gigi.tcg.data.api.API_ERROR_KIND_RETCODE
import com.gigi.tcg.data.api.ApiError
import com.gigi.tcg.data.api.CARD_INFO_URL
import com.gigi.tcg.data.api.MihoyoClient
import com.gigi.tcg.data.api.RETRYABLE_RETCODES
import com.gigi.tcg.data.api.cardDetailUrl
import com.gigi.tcg.data.api.cardListUrl
import com.gigi.tcg.data.api.gcgBasicInfoUrl
import com.gigi.tcg.data.api.gcgCardBackListUrl
import com.gigi.tcg.data.api.gcgChallengeRecordUrl
import com.gigi.tcg.data.api.gcgChallengeScheduleUrl
import com.gigi.tcg.data.api.gcgDeckListUrl
import com.gigi.tcg.data.api.gcgMatchListUrl
import com.gigi.tcg.i18n.LocaleStrings
import com.gigi.tcg.i18n.apiLangParam
import com.gigi.tcg.data.api.competitionRankUrl
import com.gigi.tcg.data.api.gameRecordsUrl
import com.gigi.tcg.data.api.isAuthFailureError
import com.gigi.tcg.data.api.myHomePageUrl
import com.gigi.tcg.data.api.otherHomePageUrl
import com.gigi.tcg.data.api.peakRankUrl
import com.gigi.tcg.data.api.userInfoUrl
import com.gigi.tcg.data.model.EntryPageData
import com.gigi.tcg.data.model.GameRecordsData
import com.gigi.tcg.data.model.GcgBasicInfoData
import com.gigi.tcg.data.model.GcgCardBackListData
import com.gigi.tcg.data.model.GcgCardListData
import com.gigi.tcg.data.model.GcgChallengeRecordData
import com.gigi.tcg.data.model.GcgChallengeScheduleData
import com.gigi.tcg.data.model.GcgDeckListData
import com.gigi.tcg.data.model.GcgMatchListData
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
    // lang 一律现取 LocaleStrings.currentLanguage()（跟随系统界面语言；未 attach 时简中，与旧行为一致）

    /** 登录态检测：成功时通过 data.game_uid 取得当前用户 UID */
    suspend fun fetchLoginInfo(server: ServerId): LoginInfoData =
        get(userInfoUrl(server, LocaleStrings.currentLanguage()), LoginInfoData.serializer(), "")

    /** 最近对局记录（服务端最多返回最近 10 条） */
    suspend fun fetchGameRecords(uid: String, server: ServerId): GameRecordsData =
        get(gameRecordsUrl(uid, server, LocaleStrings.currentLanguage()), GameRecordsData.serializer(), "")

    /** 我的主页（资料卡数据） */
    suspend fun fetchMyHomePage(uid: String, server: ServerId): MyHomePageData =
        get(myHomePageUrl(uid, server, LocaleStrings.currentLanguage()), MyHomePageData.serializer(), "")

    /** 他人主页（玩家详情弹窗数据；code 由 generateCode 生成） */
    suspend fun fetchOtherHomePage(code: String, myUid: String, server: ServerId): OtherHomePageData =
        get(otherHomePageUrl(code, myUid, server, LocaleStrings.currentLanguage()), OtherHomePageData.serializer(), "")

    /** 巅峰积分排行榜 */
    suspend fun fetchPeakRank(uid: String, server: ServerId): RankData =
        get(peakRankUrl(uid, server, LocaleStrings.currentLanguage()), RankData.serializer(), "")

    /** 赛事积分排行榜 */
    suspend fun fetchCompetitionRank(uid: String, server: ServerId): RankData =
        get(competitionRankUrl(uid, server, LocaleStrings.currentLanguage()), RankData.serializer(), "")

    /** 个人卡牌使用统计（cardListUrl 无 lang 参数，服务端不支持本地化） */
    suspend fun fetchGcgCardList(uid: String, server: ServerId): GcgCardListData =
        get(cardListUrl(uid, server), GcgCardListData.serializer(), "")

    // 「我的」页 4 组 record 域端点：与 cardList 同主机同鉴权口径（Cookie only，无 DS），
    // 参数口径见各 URL 构造器 KDoc（deckList/cardBackList/matchList 只带 server+role_id；
    // challenge/record 另带 schedule_id）。tag 传空串 = 不节流，与 fetchGcgBasicInfo 同款。

    /** 我的卡组 */
    suspend fun fetchGcgDeckList(uid: String, server: ServerId): GcgDeckListData =
        get(gcgDeckListUrl(uid, server), GcgDeckListData.serializer(), "")

    /** 卡背收集（含未收集项） */
    suspend fun fetchGcgCardBackList(uid: String, server: ServerId): GcgCardBackListData =
        get(gcgCardBackListUrl(uid, server), GcgCardBackListData.serializer(), "")

    /** 最近对局 + 收藏对局 */
    suspend fun fetchGcgMatchList(uid: String, server: ServerId): GcgMatchListData =
        get(gcgMatchListUrl(uid, server), GcgMatchListData.serializer(), "")

    /** 胜冠之试旬列表 */
    suspend fun fetchGcgChallengeSchedule(uid: String, server: ServerId): GcgChallengeScheduleData =
        get(gcgChallengeScheduleUrl(uid, server), GcgChallengeScheduleData.serializer(), "")

    /** 单旬战绩（scheduleId 取自旬列表的 id） */
    suspend fun fetchGcgChallengeRecord(uid: String, server: ServerId, scheduleId: Int): GcgChallengeRecordData =
        get(gcgChallengeRecordUrl(uid, server, scheduleId), GcgChallengeRecordData.serializer(), "")

    /** 卡面详情（公开接口）：LRU 200 命中即复用（键含 lang，切语言不串缓存），未命中打 TAG_DETAIL 交 client 节流 */
    suspend fun fetchCardDetail(entryPageId: Int): EntryPageData {
        val lang = LocaleStrings.currentLanguage()
        val key = "${lang.apiLangParam()}:$entryPageId"
        detailCache.get(key)?.let { return it }
        val fresh = get(cardDetailUrl(entryPageId, lang), EntryPageData.serializer(), MihoyoClient.TAG_DETAIL)
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

    /**
     * 官方总手牌数（gcg/basicInfo）：导出图分母的唯一可信来源（详见 [gcgBasicInfoUrl] /
     * model.GcgBasicInfoData 的口径说明）。与 cardList 同主机同鉴权口径，Cookie 由 MihoyoClient 注入。
     *
     * 缓存 5 分钟：与 card-stats 同一 TTL——卡池总数只随版本更新变化（月级），本不需要 5min 这么勤，
     * 但同一次统计页加载里两者是同一份「手牌规模」快照，节奏对齐可避免胶囊分子/分母来自相隔很久的
     * 两次采集（例如新卡池开放瞬间显示 148/147）。私有数据，只进内存、不落盘，键含服务器+UID。
     *
     * 🔴 失败（未登录 retcode=10001 / 网络 / 解析）一律吞掉返回 null，且**不写缓存**：
     * 分母只是统计页的一层兜底来源，绝不能因为它取不到而让统计/导出进入错误态。
     */
    suspend fun fetchGcgBasicInfo(uid: String, server: ServerId, force: Boolean = false): GcgBasicInfoData? {
        val key = privateKey(uid, server, BASIC_INFO_SUFFIX)
        if (!force) {
            (memoryCache.cacheGet(key) as? GcgBasicInfoData)?.let { return it }
        }
        val fresh = try {
            get(gcgBasicInfoUrl(uid, server), GcgBasicInfoData.serializer(), "")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        memoryCache.cacheSet(key, fresh, BASIC_INFO_CACHE_TTL_MS)
        return fresh
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

    // ---- 「我的」页（卡组/卡背/收藏对局/胜冠之试）：私有数据，只进内存，TTL 对齐 card-stats ----

    /** 我的卡组（5 分钟） */
    suspend fun fetchGcgDeckListCached(uid: String, server: ServerId, force: Boolean = false): GcgDeckListData =
        cachedPrivate(uid, server, MY_DECK_SUFFIX, MY_PAGE_CACHE_TTL_MS, force) {
            fetchGcgDeckList(uid, server)
        }

    /** 卡背收集（5 分钟） */
    suspend fun fetchGcgCardBackListCached(uid: String, server: ServerId, force: Boolean = false): GcgCardBackListData =
        cachedPrivate(uid, server, MY_CARDBACK_SUFFIX, MY_PAGE_CACHE_TTL_MS, force) {
            fetchGcgCardBackList(uid, server)
        }

    /** 最近对局 + 收藏对局（5 分钟） */
    suspend fun fetchGcgMatchListCached(uid: String, server: ServerId, force: Boolean = false): GcgMatchListData =
        cachedPrivate(uid, server, MY_FAVORITES_SUFFIX, MY_PAGE_CACHE_TTL_MS, force) {
            fetchGcgMatchList(uid, server)
        }

    /** 胜冠之试旬列表（5 分钟） */
    suspend fun fetchGcgChallengeScheduleCached(
        uid: String,
        server: ServerId,
        force: Boolean = false,
    ): GcgChallengeScheduleData =
        cachedPrivate(uid, server, MY_CHALLENGE_SUFFIX, MY_PAGE_CACHE_TTL_MS, force) {
            fetchGcgChallengeSchedule(uid, server)
        }

    /**
     * 单旬战绩（5 分钟）：🔴 键必须含 scheduleId——不同旬是不同数据，共用一键会串旬。
     * 先例见 [fetchRankCached] 的 `rank:${tab.key}:v1`。键含变量 ⇒ 无法在 [invalidateMyPageCache]
     * 里逐键枚举（登出走 clearPrivateCache 前缀清理，不受影响）。
     */
    suspend fun fetchGcgChallengeRecordCached(
        uid: String,
        server: ServerId,
        scheduleId: Int,
        force: Boolean = false,
    ): GcgChallengeRecordData =
        cachedPrivate(uid, server, "$MY_CHALLENGE_RECORD_PREFIX$scheduleId:v1", MY_PAGE_CACHE_TTL_MS, force) {
            fetchGcgChallengeRecord(uid, server, scheduleId)
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

    /** 「我的」页 4 个固定键；单旬战绩键含 scheduleId，不在此枚举（见 fetchGcgChallengeRecordCached） */
    fun invalidateMyPageCache(uid: String, server: ServerId) {
        memoryCache.cacheDelete(privateKey(uid, server, MY_DECK_SUFFIX))
        memoryCache.cacheDelete(privateKey(uid, server, MY_CARDBACK_SUFFIX))
        memoryCache.cacheDelete(privateKey(uid, server, MY_FAVORITES_SUFFIX))
        memoryCache.cacheDelete(privateKey(uid, server, MY_CHALLENGE_SUFFIX))
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
                message?.takeIf { it.isNotEmpty() } ?: run {
                    val code = retcode ?: 0
                    if (LocaleStrings.resolved) {
                        LocaleStrings.get(R.string.error_api_retcode, code)
                    } else {
                        "接口返回 retcode=$code"
                    }
                },
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

        /** 官方总手牌数缓存键后缀（TTL 与 card-stats 对齐，见 fetchGcgBasicInfo） */
        const val BASIC_INFO_SUFFIX: String = "gcg-basic-info:v1"
        const val BASIC_INFO_CACHE_TTL_MS: Long = CARD_STATS_CACHE_TTL_MS

        // 「我的」页缓存键后缀（卡组 / 卡背 / 收藏对局 / 旬列表）。
        // 单旬战绩是 `my-challenge-record:<scheduleId>:v1`（含变量，见 fetchGcgChallengeRecordCached），
        // 故只登记前缀常量。clearPrivateCache()（TtlCache）按 gigi:private: 前缀遍历清理 ⇒
        // 这些新键登出时自动被清，无需逐键登记。
        const val MY_DECK_SUFFIX: String = "my-deck:v1"
        const val MY_CARDBACK_SUFFIX: String = "my-cardback:v1"
        const val MY_FAVORITES_SUFFIX: String = "my-favorites:v1"
        const val MY_CHALLENGE_SUFFIX: String = "my-challenge:v1"
        const val MY_CHALLENGE_RECORD_PREFIX: String = "my-challenge-record:"

        /** 「我的」页 TTL：与 basicInfo / card-stats 同一节奏（5 分钟） */
        const val MY_PAGE_CACHE_TTL_MS: Long = CARD_STATS_CACHE_TTL_MS

        /** 对齐 CardCoverDialog 的 DETAIL_CACHE_MAX */
        const val DETAIL_CACHE_MAX: Int = 200
    }
}
