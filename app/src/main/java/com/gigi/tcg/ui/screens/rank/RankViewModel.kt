// 排行榜状态机：移植 web/src/pages/RankPage.tsx。
// - 巅峰/赛事两 Tab 独立懒加载（首次切换才请求，对应 loadedTabs Set）；
// - 刷新重取两表（force=true，绕 3 分钟 TTL 缓存）；下拉刷新/retry 只重取当前 Tab，
//   refreshing 标志由"发起刷新的 tab"（refreshingTab）落定时清除，与切换后的 activeTab 无关；
// - 分页渲染：首屏 PAGE_CHUNK 条，滚动到底追加（对应 visibleCount + IntersectionObserver）；
// - retcode 判定集中在数据层，本层只经 describeApiError 转文案（设计红线 2）。

package com.gigi.tcg.ui.screens.rank

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gigi.tcg.data.api.describeApiError
import com.gigi.tcg.data.model.RankInfo
import com.gigi.tcg.data.repo.RankTab
import com.gigi.tcg.di.AppContainer
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 单 Tab 榜单状态：NotLoaded=尚未懒加载（对齐 loadedTabs 未命中） */
sealed interface AsyncRankList {
    data object NotLoaded : AsyncRankList
    data object Loading : AsyncRankList
    data class Content(val items: List<RankInfo>) : AsyncRankList
    data class Error(val message: String) : AsyncRankList
}

data class RankUiState(
    val peak: AsyncRankList = AsyncRankList.NotLoaded,
    val competition: AsyncRankList = AsyncRankList.NotLoaded,
    val activeTab: RankTab = RankTab.Peak,
)

/**
 * 下拉刷新指示器清除判定（缺陷 A，纯函数便于 JVM 测，风格对齐 AppGate.shouldRetryVerify）：
 * 只认"本次刷新实际发起的那个 tab"（refreshingTab），与 activeTab 无关——
 * 刷新途中横滑切走 Tab 后，原 Tab 落定仍须清除；其他 Tab 落定不得误清。
 */
internal fun shouldClearRefreshing(settledTab: RankTab, refreshingTab: RankTab?): Boolean =
    refreshingTab != null && settledTab == refreshingTab

class RankViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(RankUiState())
    val uiState: StateFlow<RankUiState> = _uiState.asStateFlow()

    private val _visibleCount = MutableStateFlow(PAGE_CHUNK)
    val visibleCount: StateFlow<Int> = _visibleCount.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    // 本次下拉刷新实际发起的 tab（缺陷 A 修复核心）：清除判定只认它，不认 activeTab
    private var refreshingTab: RankTab? = null

    init {
        // 会话 UID 就绪（badge_uid 参数）后才拉取当前 Tab；未登录时保持 NotLoaded 不发请求
        viewModelScope.launch {
            container.sessionUid.collect { uid ->
                if (uid != null) ensureLoaded(_uiState.value.activeTab)
            }
        }
    }

    fun selectTab(tab: RankTab) {
        if (_uiState.value.activeTab == tab) return
        _uiState.value = _uiState.value.copy(activeTab = tab)
        _visibleCount.value = PAGE_CHUNK
        ensureLoaded(tab)
    }

    /**
     * 当前 Tab 的 force 刷新（绕缓存）：ErrorState 重试按钮与下拉刷新共用此入口。
     * 与 refresh() 的区别：refresh() 是全局两表重取；retry() 只动 activeTab。
     */
    fun retry() {
        if (_refreshing.value) return
        if (container.sessionUid.value == null) return
        _refreshing.value = true
        refreshingTab = _uiState.value.activeTab
        ensureLoaded(_uiState.value.activeTab, force = true)
    }

    /** 全局刷新：两表重取（force 绕过 3min 缓存） */
    fun refresh() {
        _visibleCount.value = PAGE_CHUNK
        _uiState.value = _uiState.value.copy(peak = AsyncRankList.NotLoaded, competition = AsyncRankList.NotLoaded)
        fetch(RankTab.Peak, force = true)
        fetch(RankTab.Competition, force = true)
    }

    /** 滚动到底：追加一页，封顶为当前榜单长度 */
    fun loadMore() {
        val size = activeContent()?.items?.size ?: return
        _visibleCount.update { minOf(it + PAGE_CHUNK, size) }
    }

    private fun ensureLoaded(tab: RankTab, force: Boolean = false) {
        val state = stateOf(_uiState.value, tab)
        if (!force && state !is AsyncRankList.NotLoaded && state !is AsyncRankList.Error) return
        fetch(tab, force)
    }

    private fun fetch(tab: RankTab, force: Boolean) {
        val uid = container.sessionUid.value ?: run {
            // 兜底：发起刷新的 tab 因 uid 提前变 null 而在此返回（异步协程未启动，
            // 永远不会再有 setState 调用）——就地清刷新标志，否则指示器再次卡死。
            // 只清"本次刷新的那个 tab"，不波及其余在飞请求。
            if (shouldClearRefreshing(tab, refreshingTab)) {
                _refreshing.value = false
                refreshingTab = null
            }
            return
        }
        val server = container.currentServer.value
        // 已有数据时静默刷新（不显示骨架，避免切 Tab / 刷新整屏抖动）
        if (force || stateOf(_uiState.value, tab) !is AsyncRankList.Content) {
            setState(tab, AsyncRankList.Loading)
        }
        viewModelScope.launch {
            try {
                val data = container.repository.fetchRankCached(uid, server, tab, force)
                setState(tab, AsyncRankList.Content(data.rankInfos.orEmpty()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState(tab, AsyncRankList.Error(describeApiError(e)))
            }
        }
    }

    private fun activeContent(): AsyncRankList.Content? =
        stateOf(_uiState.value, _uiState.value.activeTab) as? AsyncRankList.Content

    private fun stateOf(state: RankUiState, tab: RankTab): AsyncRankList =
        if (tab == RankTab.Peak) state.peak else state.competition

    private fun setState(tab: RankTab, value: AsyncRankList) {
        _uiState.update {
            if (tab == RankTab.Peak) it.copy(peak = value) else it.copy(competition = value)
        }
        // 发起刷新的那个 Tab 落定（Content/Error）即结束下拉刷新指示器；
        // 判定不看 activeTab——刷新途中横滑切 Tab 会导致永久卡死（缺陷 A）。
        val settled = value !is AsyncRankList.Loading && value !is AsyncRankList.NotLoaded
        if (settled && shouldClearRefreshing(tab, refreshingTab)) {
            _refreshing.value = false
            refreshingTab = null
        }
    }

    companion object {
        /** 首屏渲染条数，滚动到底自动追加（对齐 RankPage PAGE_CHUNK） */
        const val PAGE_CHUNK = 60

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RankViewModel(container) as T
            }
    }
}
