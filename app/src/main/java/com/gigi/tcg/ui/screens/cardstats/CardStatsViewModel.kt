// 卡牌使用详情状态：移植 web/src/pages/CardStatsPage.tsx（§5.6）。
// - load 取 fetchGcgCardListCached（统计主体，5min 缓存），头像随 summary 一起取 fetchMyHomePageCached
//   （与主页共用 45s 缓存，通常直接命中；失败不影响统计主流程）；
// - retcode 业务失败 → "卡牌信息获取失败: {message}"（对齐原 game.lua，不跳登录），
//   其余归因 → "请检查网络重试"（可重试）；归因只经 ApiError.kind，页面不判 retcode 值。

package com.gigi.tcg.ui.screens.cardstats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gigi.tcg.GigiApp
import com.gigi.tcg.data.api.API_ERROR_KIND_RETCODE
import com.gigi.tcg.data.api.ApiError
import com.gigi.tcg.data.model.GcgCard as ApiGcgCard
import com.gigi.tcg.data.model.GcgStats as ApiGcgStats
import com.gigi.tcg.di.AppContainer
import com.gigi.tcg.domain.CARD_TYPE_ASSIST
import com.gigi.tcg.domain.CARD_TYPE_EVENT
import com.gigi.tcg.domain.CARD_TYPE_MODIFY
import com.gigi.tcg.domain.GcgCard
import com.gigi.tcg.domain.GcgSummary
import com.gigi.tcg.domain.calcPercent
import com.gigi.tcg.domain.computeGcgSummary
import com.gigi.tcg.domain.percentSortKey
import com.gigi.tcg.domain.prepareCardLists
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 角色牌排序维度（对齐 CHAR_SORT_OPTIONS）：出场次数 / 胜率 / 胜场 */
enum class CharSortKey(val label: String) {
    Use("出场次数"),
    WinRate("胜率"),
    Wins("胜场"),
}

/** 行动牌类型筛选（对齐 ACTION_TYPES）；typeValue 为 null 表示"全部" */
enum class ActionTypeFilter(val label: String, val typeValue: String?) {
    All("全部", null),
    Modify("装备牌", CARD_TYPE_MODIFY),
    Assist("支援牌", CARD_TYPE_ASSIST),
    Event("事件牌", CARD_TYPE_EVENT),
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
) {
    val isEmpty: Boolean
        get() = !loading && error == null && summary == null

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
        _uiState.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            val uid = container.sessionUid.value
            val server = container.currentServer.value
            if (uid.isNullOrBlank()) {
                _refreshing.value = false
                _uiState.update { it.copy(loading = false, error = null, summary = null, charList = emptyList(), actionList = emptyList()) }
                return@launch
            }
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
                    _refreshing.value = false
                    _uiState.update {
                        it.copy(
                            loading = false,
                            error = null,
                            summary = computeGcgSummary(data.stats.toDomainStats(), lists),
                            charList = lists.charCards,
                            actionList = lists.actionCards,
                        )
                    }
                }
                // 头像随统计主体一起就绪（首屏 PlayerInfoCard 即可显示），
                // 失败不影响统计主流程（对齐 web 的 .catch(() => undefined)）
                fetchAvatar()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                val isRetcode = e is ApiError && e.kind == API_ERROR_KIND_RETCODE
                _refreshing.value = false
                _uiState.update {
                    it.copy(
                        loading = false,
                        error = if (isRetcode) "卡牌信息获取失败: ${e.message}" else "请检查网络重试",
                        errorCanRetry = !isRetcode,
                    )
                }
            }
        }
    }

    private fun fetchAvatar() {
        val uid = container.sessionUid.value ?: return
        val server = container.currentServer.value
        viewModelScope.launch {
            try {
                val home = container.repository.fetchMyHomePageCached(uid, server)
                val url = home.pageInfo?.avatarUrl
                if (!url.isNullOrBlank()) _uiState.update { it.copy(avatarUrl = url) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 头像缺失只影响详情面板展示，静默忽略
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
