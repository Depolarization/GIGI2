// 卡牌使用详情状态：移植 web/src/pages/CardStatsPage.tsx（§5.6）。
// - load 取 fetchGcgCardListCached（统计主体，5min 缓存），头像随 summary 一起取 fetchMyHomePageCached
//   （与主页共用 45s 缓存，通常直接命中；失败不影响统计主流程）；
// - V37-5：装载入口不再无条件打 Loading —— 已有内容时静默替换（见 shouldShowBlockingLoading），
//   缓存命中也闪一帧菊花就是用户报的「切页必闪 progressbar」；口径照抄 HomeViewModel「有内容即静默」，
//   内容归属的 uid 变了才清空（同 V36 MyViewModel.AccountScopeGuard 的语义）。
// - 分母（角色牌/行动牌总手牌数）首选 gcg/basicInfo 官方总数（需登录 Cookie，失败静默降级），
//   取不到才退回公开图鉴计数——图鉴会去重手牌（行动牌只数出 568 / 真实 941），单用会把未收集画成全收集；
//   服务端 cardList 的 *_card_num_total 恒缺失不可依赖；
// - retcode 业务失败 → "卡牌信息获取失败: {message}"（对齐原 game.lua，不跳登录），
//   其余归因 → "请检查网络重试"（可重试）；归因只经 ApiError.kind，页面不判 retcode 值。

package com.gigi.tcg.ui.screens.cardstats

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gigi.tcg.GigiApp
import com.gigi.tcg.R
import com.gigi.tcg.data.ServerId
import com.gigi.tcg.data.api.API_ERROR_KIND_RETCODE
import com.gigi.tcg.data.api.ApiError
import com.gigi.tcg.data.model.GcgCard as ApiGcgCard
import com.gigi.tcg.data.model.GcgStats as ApiGcgStats
import com.gigi.tcg.di.AppContainer
import com.gigi.tcg.i18n.LocaleStrings
import com.gigi.tcg.domain.CARD_TYPE_ASSIST
import com.gigi.tcg.domain.CARD_TYPE_EVENT
import com.gigi.tcg.domain.CARD_TYPE_MODIFY
import com.gigi.tcg.domain.GcgCard
import com.gigi.tcg.domain.GcgSummary
import com.gigi.tcg.domain.WikiCardTotals
import com.gigi.tcg.domain.calcPercent
import com.gigi.tcg.domain.computeGcgSummary
import com.gigi.tcg.domain.formatTier
import com.gigi.tcg.domain.getTierStars
import com.gigi.tcg.domain.officialCardTotals
import com.gigi.tcg.domain.percentSortKey
import com.gigi.tcg.domain.prepareCardLists
import com.gigi.tcg.domain.wikiCardTotals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 角色牌排序维度（对齐 CHAR_SORT_OPTIONS）：出场次数 / 胜率 / 胜场。
 * labelRes 存 @StringRes id 而非成品文案：枚举在 VM 层（非组合作用域），
 * 展示由 CardStatsRoute 用 stringResource 解析，语言切换才会即时重绘。
 */
enum class CharSortKey(@StringRes val labelRes: Int) {
    Use(R.string.stat_use_count),
    WinRate(R.string.stat_win_rate),
    Wins(R.string.stat_wins),
}

/** 行动牌类型筛选（对齐 ACTION_TYPES）；typeValue 为 null 表示"全部" */
enum class ActionTypeFilter(@StringRes val labelRes: Int, val typeValue: String?) {
    All(R.string.common_all, null),
    Modify(R.string.card_type_modify, CARD_TYPE_MODIFY),
    Assist(R.string.card_type_assist, CARD_TYPE_ASSIST),
    Event(R.string.card_type_event, CARD_TYPE_EVENT),
}

data class StatsUiState(
    val loading: Boolean = true,
    /** null = 无错误；非空时 UI 出 ErrorState */
    val error: String? = null,
    /** false = retcode 业务失败（缺权限类），重试无意义 */
    val errorCanRetry: Boolean = true,
    val charSort: CharSortKey = CharSortKey.Use,
    val actionType: ActionTypeFilter = ActionTypeFilter.All,
    /** 玩家信息详情面板：默认折叠 */
    val detailOpen: Boolean = false,
    val summary: GcgSummary? = null,
    val charList: List<GcgCard> = emptyList(),
    val actionList: List<GcgCard> = emptyList(),
    val avatarUrl: String? = null,
    /**
     * 段位文本（天梯积分换算，形如「圣手 3★」；V29 随头像一起从 myHomePage 带出）。
     * 空串 = 没取到（未登录 / 接口失败 / 积分不足）⇒ 信息卡不显示段位那一段，而不是显示占位。
     */
    val tier: String = "",
) {
    val isEmpty: Boolean
        get() = !loading && error == null && summary == null

    /**
     * 有没有「既有的可展示内容」——[shouldShowBlockingLoading] 的输入，决定这次装载是静默替换还是打 Loading。
     * 🔴 不能只看 `summary != null`：接口字段全可空（红线 1），stats 缺失而 cardList 有牌是合法组合，
     * 只看 summary 会把这种「有内容」的重入误判成首屏 ⇒ 白闪一次菊花。
     */
    val hasContent: Boolean
        get() = summary != null || charList.isNotEmpty() || actionList.isNotEmpty()

    /** 出场率分母 = 全部角色牌使用次数之和（红线 6：不是游玩场次数） */
    val charTotalUse: Int
        get() = charList.sumOf { it.useCount ?: 0 }

    /** 当前排序下的角色牌（胜率/胜场同值时回退出场次数降序，保证顺序稳定） */
    val sortedCharList: List<GcgCard>
        get() {
            val byUse = compareByDescending<GcgCard> { it.useCount ?: 0 }
            return when (charSort) {
                CharSortKey.Use -> charList.sortedWith(byUse)
                CharSortKey.Wins ->
                    charList.sortedWith(compareByDescending<GcgCard> { it.proficiency ?: 0 }.then(byUse))
                CharSortKey.WinRate ->
                    charList.sortedWith(
                        compareByDescending<GcgCard> {
                            percentSortKey(
                                calcPercent((it.proficiency ?: 0).toDouble(), (it.useCount ?: 0).toDouble()),
                            )
                        }.then(byUse),
                    )
            }
        }

    /** 当前类型筛选下的行动牌 */
    val filteredActionList: List<GcgCard>
        get() = if (actionType.typeValue == null) {
            actionList
        } else {
            actionList.filter { it.cardType == actionType.typeValue }
        }
}

/**
 * 阻塞式 Loading 判据（纯函数，无 Compose/Android 依赖，JVM 单测直接钉死）。
 *
 * 「阻塞式」= 整屏 LoadingView 把内容换掉。口径与 HomeViewModel.loadProfile 的「有内容即静默」一致：
 * 状态里已经有内容，重新装载就只静默替换，**不再把 loading 打回 true** —— 内存缓存命中时协程几乎
 * 立刻返回，但入口那一次 `loading = true` 已经足够让 UI 先画一帧菊花，这正是用户报的
 * 「每次切换页面都会出现 progressbar + 正在加载」（V37-5 实测根因，与 force 无关、与缓存失效无关）。
 *
 * 判据读的是 **StateFlow 里的状态**，不是 `LaunchedEffect(key)` 的 key：组合离开会被 cancel、
 * 重入会重启（手册 §8.6 红线 6），而每次 load() 都重新求值 ⇒ 天然免疫重入漏判。
 *
 * @param hasContent 既有内容（[StatsUiState.hasContent]）
 * @param isError 当前可见的是错误页：本页错误分支**不清内容**（只置 error），
 *        光靠 hasContent 会把「错误页上的重试」误判成可静默替换 —— 错误页上没有内容可盖，
 *        重试必须回到阻塞 Loading。
 */
internal fun shouldShowBlockingLoading(hasContent: Boolean, isError: Boolean): Boolean =
    isError || !hasContent

class CardStatsViewModel(app: Application) : AndroidViewModel(app) {

    private val container: AppContainer = (app as GigiApp).container

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    // 下拉刷新指示器独立于 loading（形状对齐 RankViewModel）：
    // loading 默认 true，若直接喂给 PullToRefreshBox 会与首屏 LoadingView 同屏出现两个圈。
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private var generation = 0
    private var loadJob: Job? = null

    /** uiState 里的内容归属哪个会话（null = 还没装过）：变了才清空，见 [load] 的内容归属闸门 */
    private var contentUid: String? = null

    init {
        load(force = false)
    }

    /** ErrorState 重试按钮：force 绕过 TTL 缓存重新拉取。非下拉入口，不置刷新指示器 */
    fun retry() = load(force = true)

    /** 下拉刷新：force 绕过 TTL 缓存重新拉取，仅由用户下拉手势驱动指示器 */
    fun refresh() {
        _refreshing.value = true
        load(force = true)
    }

    fun setCharSort(key: CharSortKey) {
        _uiState.update { it.copy(charSort = key) }
    }

    fun setActionType(filter: ActionTypeFilter) {
        _uiState.update { it.copy(actionType = filter) }
    }

    /** 展开/收起"玩家信息"详情面板 */
    fun toggleDetail() {
        _uiState.update { it.copy(detailOpen = !it.detailOpen) }
    }

    private fun load(force: Boolean) {
        loadJob?.cancel()
        val gen = ++generation
        val uid = container.sessionUid.value
        val server = container.currentServer.value
        // 内容归属闸门（语义照 V36 MyViewModel.AccountScopeGuard：账号变才清、页面重入不清）：
        // uid 变了（含首装/登出置 null）必须先丢掉上一账号的内容 —— 静默替换只允许替换同一账号的数据，
        // 否则新账号的装载会把旧账号的统计当「既有内容」静默盖着显示。
        if (contentUid != uid) {
            contentUid = uid
            _uiState.update {
                it.copy(
                    summary = null,
                    charList = emptyList(),
                    actionList = emptyList(),
                    avatarUrl = null,
                    tier = "",
                )
            }
        }
        // 仅「无既有内容 / 当前是错误页」才打阻塞 Loading（判据见 shouldShowBlockingLoading）；
        // error 的清理时机保持现状：入口清、各落定分支按结果写。
        _uiState.update {
            it.copy(loading = shouldShowBlockingLoading(it.hasContent, isError = it.error != null), error = null)
        }
        loadJob = viewModelScope.launch {
            if (uid.isNullOrBlank()) {
                _refreshing.value = false
                _uiState.update { it.copy(loading = false, error = null, summary = null, charList = emptyList(), actionList = emptyList()) }
                return@launch
            }
            // 分母的两路来源互不依赖、并发取（都不串在统计主链路后面，第三级 cardList total 随主链路免费拿到）：
            // basicInfo 官方总数（首选）/ 图鉴频道计数（兜底）。是本 job 的子协程，下拉 cancel 时一起收掉。
            // force=false 时两路都命中缓存（basicInfo 内存 5min、图鉴内存 1h→磁盘），不拖慢静默替换路径。
            val officialTotalsDeferred = async { fetchOfficialCardTotals(uid, server, force) }
            val totalsDeferred = async { fetchWikiCardTotals() }
            try {
                val data = container.repository.fetchGcgCardListCached(uid, server, force)
                if (gen != generation) return@launch
                val cardList = data.cardList.orEmpty().map { it.toDomainCard() }
                if (cardList.isEmpty()) {
                    _refreshing.value = false
                    _uiState.update {
                        it.copy(loading = false, error = null, summary = null, charList = emptyList(), actionList = emptyList())
                    }
                } else {
                    val lists = prepareCardLists(cardList)
                    // 挂起函数不能在 update{} 的非挂起 lambda 里调 ⇒ 先 await 再落状态。
                    // 两路分母与统计并发发起，这里等的只是三者中较慢的那个，首屏不多花一趟串行 RTT。
                    val officialTotals = officialTotalsDeferred.await()
                    val totals = totalsDeferred.await()
                    _refreshing.value = false
                    _uiState.update {
                        it.copy(
                            loading = false,
                            error = null,
                            summary = computeGcgSummary(
                                data.stats.toDomainStats(),
                                lists,
                                wikiTotals = totals,
                                officialTotals = officialTotals,
                            ),
                            charList = lists.charCards,
                            actionList = lists.actionCards,
                        )
                    }
                }
                // 头像随统计主体一起就绪（首屏 PlayerInfoCard 即可显示），
                // 失败不影响统计主流程（对齐 web 的 .catch(() => undefined)）
                fetchAvatar(uid, server, gen)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                val isRetcode = e is ApiError && e.kind == API_ERROR_KIND_RETCODE
                _refreshing.value = false
                _uiState.update {
                    it.copy(
                        loading = false,
                        error = if (isRetcode) {
                            LocaleStrings.get(R.string.error_card_fetch_failed, e.message.orEmpty())
                        } else {
                            LocaleStrings.get(R.string.error_check_network)
                        },
                        errorCanRetry = !isRetcode,
                    )
                }
            }
        }
    }

    /**
     * 导出图胶囊分母（`角色牌 143/147`）的三级降级链，全在 domain.computeGcgSummary 里判优先级：
     * 1. **basicInfo 官方总数**（本方法）：唯一权威口径，实测 147 / 941；
     * 2. **图鉴频道计数**（[fetchWikiCardTotals]）：🔴 图鉴会去重手牌（行动牌只数出 568），
     *    单独用会把「没收集全」画成「全收集」⇒ 只在 basicInfo 取不到时（未登录 retcode=10001、
     *    字段缺失、解析失败）退居兜底，聊胜于无；
     * 3. cardList 的 `*_card_num_total`（实测恒 null）→ 最后兜底已得数（宁可显示全收集也不出 x/0）。
     *
     * 与统计主链路互不依赖、只影响分母 ⇒ 失败一律静默折算成空 totals（让链路自然降级到第 2 级），
     * 绝不冒泡成统计页错误态：导出必须照常能出图。
     * 随下拉 force 刷新：分母要与分子（cardList 被 force 重取）出自同一时刻附近，否则新卡池开放瞬间
     * 可能画出 148/147 这种分子大于分母的怪值。
     */
    private suspend fun fetchOfficialCardTotals(
        uid: String,
        server: ServerId,
        force: Boolean,
    ): WikiCardTotals = try {
        officialCardTotals(container.repository.fetchGcgBasicInfo(uid, server, force))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        WikiCardTotals()
    }

    /**
     * 图鉴频道计数（分母第 2 级兜底，口径不可靠，见 [fetchOfficialCardTotals] 的三级链说明）：
     * 复用图鉴页同一份缓存（内存 1h + 磁盘），不额外发请求。只影响分母，失败/未就绪时退回已得数，
     * 绝不拖垮统计主流程。不随下拉 force 刷新：图鉴按日更新，1h TTL 已够新，换取一次 580KB 重下载不值得。
     */
    private suspend fun fetchWikiCardTotals(): WikiCardTotals = try {
        wikiCardTotals(container.repository.fetchCardWikiListCached().list)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        WikiCardTotals()
    }

    /**
     * 头像 + 段位（与统计同源 myHomePage，45s 缓存）。uid/server/gen 由 [load] 透传而非再读容器：
     * 装载途中换了账号时，本次回包属过期数据（gen 不匹配）直接丢弃，避免把 A 的头像静默盖到 B 的内容上。
     */
    private fun fetchAvatar(uid: String, server: ServerId, gen: Int) {
        viewModelScope.launch {
            try {
                val home = container.repository.fetchMyHomePageCached(uid, server)
                if (gen != generation) return@launch
                val info = home.pageInfo
                // V29：段位（天梯积分 → TierStars）与头像同源，一次请求一起带出来给信息卡用。
                // 段位格式化交给 domain 的 getTierStars/formatTier，UI 层不重算分段规则。
                val tier = info?.ladderScore?.let { formatTier(getTierStars(it)) }.orEmpty()
                _uiState.update {
                    it.copy(
                        avatarUrl = info?.avatarUrl?.takeIf { url -> url.isNotBlank() } ?: it.avatarUrl,
                        tier = tier.ifEmpty { it.tier },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 头像/段位缺失只影响信息卡展示，静默忽略
            }
        }
    }

    private fun ApiGcgCard.toDomainCard(): GcgCard =
        GcgCard(name = name, cardType = cardType, useCount = useCount, proficiency = proficiency)

    private fun ApiGcgStats?.toDomainStats(): com.gigi.tcg.domain.GcgStats? =
        this?.let {
            com.gigi.tcg.domain.GcgStats(
                nickname = it.nickname,
                level = it.level,
                avatarCardNumGained = it.avatarCardNumGained,
                actionCardNumGained = it.actionCardNumGained,
                // 服务端 total 字段实测恒 null，透传只为图鉴也拿不到时多一层兜底
                avatarCardNumTotal = it.avatarCardNumTotal,
                actionCardNumTotal = it.actionCardNumTotal,
            )
        }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.AndroidViewModelFactory(app) {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CardStatsViewModel(app) as T
            }
    }
}
