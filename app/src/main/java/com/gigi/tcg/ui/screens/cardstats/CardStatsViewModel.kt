// 卡牌使用详情状态机：移植 web/src/pages/CardStatsPage.tsx 的 load/排序/筛选语义。
// - 头像与卡牌统计并行获取（对齐 tsx：头像失败不影响统计主流程）；
// - retcode 业务失败 → "卡牌信息获取失败: {message}"（对齐原 game.lua，不跳登录），
//   网络失败 → "请检查网络重试"（retcode 归因仍集中在数据层，本层只做文案分支）。

package com.gigi.tcg.ui.screens.cardstats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gigi.tcg.data.api.API_ERROR_KIND_RETCODE
import com.gigi.tcg.data.api.ApiError
import com.gigi.tcg.data.model.GcgCard as ApiGcgCard
import com.gigi.tcg.data.model.GcgStats as ApiGcgStats
import com.gigi.tcg.di.AppContainer
import com.gigi.tcg.domain.GcgCard
import com.gigi.tcg.domain.GcgStats
import com.gigi.tcg.domain.GcgSummary
import com.gigi.tcg.domain.PreparedCardLists
import com.gigi.tcg.domain.calcPercent
import com.gigi.tcg.domain.computeGcgSummary
import com.gigi.tcg.domain.percentSortKey
import com.gigi.tcg.domain.prepareCardLists
import java.util.concurrent.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 角色牌排序维度：出场次数 / 胜率 / 胜场（对齐 tsx CharSortKey） */
enum class CharSortKey(val label: String) {
    Use("出场次数"),
    WinRate("胜率"),
    Wins("胜场"),
}

/** 行动牌类型筛选（对齐 tsx ACTION_TYPES：全部/装备牌/支援牌/事件牌） */
enum class ActionTypeFilter(val label: String, val cardType: String?) {
    All("全部", null),
    Modify("装备牌", com.gigi.tcg.domain.CARD_TYPE_MODIFY),
    Assist("支援牌", com.gigi.tcg.domain.CARD_TYPE_ASSIST),
    Event("事件牌", com.gigi.tcg.domain.CARD_TYPE_EVENT),
}

data class StatsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val charSort: CharSortKey = CharSortKey.Use,
    val actionType: ActionTypeFilter = ActionTypeFilter.All,
    val detailOpen: Boolean = false,
    val summary: GcgSummary? = null,
    /** 玩家头像：cardList 接口不返回，从 my_home_page 的 page_info.avatar_url 取 */
    val avatarUrl: String? = null,
    /** 已按 charSort 排序的角色牌 */
    val charList: List<GcgCard> = emptyList(),
    /** 已按 actionType 筛选的行动牌 */
    val actionList: List<GcgCard> = emptyList(),
)

class CardStatsViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    private var prepared: PreparedCardLists? = null
    private var loadJob: Job? = null

    /** 首次进入/错误重试/强制刷新：force=true 绕过 repository 的 TTL 缓存 */
    fun load(force: Boolean = false) {
        // 已有数据时静默刷新：不置 loading，避免整屏闪烁（对齐 tsx listsRef 判定）
        val hasContent = prepared != null
        if (!hasContent || force) {
            _uiState.update { it.copy(loading = true, error = null) }
        } else {
            _uiState.update { it.copy(error = null) }
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val uid = container.sessionUid.value
            if (uid == null) {
                _uiState.update { it.copy(loading = false, error = RETRY_HINT) }
                return@launch
            }
            val server = container.currentServer.value
            // 头像并行获取、失败留空：与统计主流程互不影响（对齐 tsx avatarPromise.catch）
            val avatarDeferred = async {
                try {
                    container.repository.fetchMyHomePageCached(uid, server, force).pageInfo?.avatarUrl
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            try {
                val data = container.repository.fetchGcgCardListCached(uid, server, force)
                val cardList = data.cardList.orEmpty().map { it.toDomain() }
                if (cardList.isEmpty()) {
                    prepared = null
                    _uiState.update {
                        it.copy(
                            loading = false,
                            error = null,
                            summary = null,
                            charList = emptyList(),
                            actionList = emptyList(),
                        )
                    }
                } else {
                    val lists = prepareCardLists(cardList)
                    prepared = lists
                    _uiState.update {
                        it.copy(
                            loading = false,
                            error = null,
                            summary = computeGcgSummary(data.stats?.toDomain(), lists),
                        )
                    }
                    applySortAndFilter()
                }
                _uiState.update { it.copy(avatarUrl = avatarDeferred.await() ?: it.avatarUrl) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, error = e.toStatsMessage()) }
            }
        }
    }

    /** 排序键切换：角色牌列表按新键重排（胜率/胜场同值时回退出场次数，保证顺序稳定） */
    fun onCharSortChange(key: CharSortKey) {
        if (_uiState.value.charSort == key) return
        _uiState.update { it.copy(charSort = key) }
        applySortAndFilter()
    }

    /** 行动牌类型切换：按 card_type 过滤 */
    fun onActionTypeChange(filter: ActionTypeFilter) {
        if (_uiState.value.actionType == filter) return
        _uiState.update { it.copy(actionType = filter) }
        applySortAndFilter()
    }

    /** 玩家信息详情面板折叠开关 */
    fun onToggleDetail() {
        _uiState.update { it.copy(detailOpen = !it.detailOpen) }
    }

    private fun applySortAndFilter() {
        val lists = prepared ?: return
        val sort = _uiState.value.charSort
        val type = _uiState.value.actionType
        _uiState.update {
            it.copy(
                charList = sortedChars(lists, sort),
                actionList = lists.actionCards.filter { c -> type.cardType == null || c.cardType == type.cardType },
            )
        }
    }

    private fun sortedChars(lists: PreparedCardLists, key: CharSortKey): List<GcgCard> {
        val byUse = compareByDescending<GcgCard> { it.useCount ?: 0 }
        return when (key) {
            CharSortKey.Use -> lists.charCards.sortedWith(byUse)
            CharSortKey.Wins -> lists.charCards.sortedWith(
                compareByDescending<GcgCard> { (it.proficiency ?: 0).toDouble() }.then(byUse),
            )
            CharSortKey.WinRate -> lists.charCards.sortedWith(
                compareByDescending<GcgCard> {
                    percentSortKey(calcPercent((it.proficiency ?: 0).toDouble(), (it.useCount ?: 0).toDouble()))
                }.then(byUse),
            )
        }
    }

    private fun ApiGcgCard.toDomain(): GcgCard =
        GcgCard(name = name, cardType = cardType, useCount = useCount, proficiency = proficiency)

    private fun ApiGcgStats.toDomain(): GcgStats =
        GcgStats(
            nickname = nickname,
            level = level,
            avatarCardNumGained = avatarCardNumGained,
            actionCardNumGained = actionCardNumGained,
        )

    /** retcode 业务失败与网络失败分文案（对齐 tsx load 的 catch 分支） */
    private fun Throwable.toStatsMessage(): String {
        val e = this
        return if (e is ApiError && e.kind == API_ERROR_KIND_RETCODE) {
            "$RETCODE_PREFIX${e.message ?: ""}"
        } else {
            RETRY_HINT
        }
    }

    companion object {
        const val RETCODE_PREFIX = "卡牌信息获取失败: "
        const val RETRY_HINT = "请检查网络重试"

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CardStatsViewModel(container) as T
            }
    }
}
