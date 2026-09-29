// 首页状态机：移植 web/src/pages/HomePage.tsx 的 ProfileState/RecordsState 双块独立加载。
// - 两块各自持有代数（对齐 profileSeq/recordsSeq）：单块重试不作废另一块在途请求；
// - 已有数据时静默更新（不回落 Loading，避免整屏抖动）；任何结局 loading 必落定；
// - retcode 判定集中在数据层（设计红线 2），本层仅经 apiErrorText 转文案；
//   凭据失效由 AppGate 后台校验统一处理，页面不自行踢会话。

package com.gigi.tcg.ui.screens.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gigi.tcg.GigiApp
import com.gigi.tcg.data.ServerId
import com.gigi.tcg.i18n.apiErrorText
import com.gigi.tcg.data.model.GameRecord
import com.gigi.tcg.data.model.PageInfo
import com.gigi.tcg.di.AppContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 首刷时序：profile 落定后延迟 staggerMs 再发 records。
 * 启动瞬态 AppGate.observeMain 的 fetchLoginInfo 会与首刷并发命中米游社保流
 * （-500004），故首刷两块错峰；顶栏手动 refresh 仍并行（web HomePage 语义，
 * web 侧无第三个并发请求，无需错峰）。提取为纯函数便于 JVM 单测验证时序。
 */
internal suspend fun runStaggeredFirstLoad(
    staggerMs: Long,
    loadProfile: suspend () -> Unit,
    loadRecords: suspend () -> Unit,
) {
    loadProfile()
    if (staggerMs > 0) delay(staggerMs)
    loadRecords()
}

/** 单块数据的三态（对齐 Feedback.tsx 的 loading / data / error 形态） */
sealed class Async<out T> {
    data object Loading : Async<Nothing>()

    data class Content<out T>(val value: T) : Async<T>()

    /** message 为 null = 合法响应但数据缺失（web 的 "资料卡数据为空" 分支） */
    data class Error(val message: String? = null) : Async<Nothing>()
}

data class HomeUiState(
    val profile: Async<PageInfo> = Async.Loading,
    val records: Async<List<GameRecord>> = Async.Loading,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val container: AppContainer = (app as GigiApp).container

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // 下拉刷新指示器：只由用户主动刷新（refresh()）置位，两块都落定后经
    // maybeEndRefreshing 清零（对齐 RankViewModel._refreshing 的形状：
    // isRefreshing 不由 Async.Loading 派生，否则首屏顶部圈 + 居中圈同转）
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** 会话 UID（容器镜像）：路由据此决定点击回调传参 */
    val sessionUid: StateFlow<String?> = container.sessionUid

    // 两块各自代数：仅最新一次请求允许落定状态（对齐 web 的 seq 语义）
    private var profileGeneration = 0
    private var recordsGeneration = 0

    // 在途请求计数：主线程单线程读写（viewModelScope = Dispatchers.Main.immediate），无需原子类。
    // 下拉圈收否看它而非只看状态（见 maybeEndRefreshing）
    private var pendingLoads = 0

    init {
        // AppGate 零等待渲染：会话可能尚未写入，等首个非空 UID 再拉两块。
        // 首刷串行化错峰（profile → 延迟 → records），避开启动瞬态与
        // AppGate.observeMain 校验请求并发触发米游社保流（详见 runStaggeredFirstLoad）
        viewModelScope.launch {
            val uid = container.sessionUid.filterNotNull().first()
            val server = container.currentServer.value
            runStaggeredFirstLoad(
                FIRST_LOAD_STAGGER_MS,
                loadProfile = { loadProfile(uid, server, force = false) },
                loadRecords = { loadRecords(uid, server, force = false) },
            )
        }
    }

    /** 下拉刷新 / 刷新按钮：两块同时 force 绕缓存；置位必有人清零（见 maybeEndRefreshing） */
    fun refresh() {
        val uid = container.sessionUid.value ?: return
        val server = container.currentServer.value
        // 置位在 uid 提前返回之后：未登录不会留下无人清零的 true
        _refreshing.value = true
        loadProfile(uid, server, force = true)
        loadRecords(uid, server, force = true)
    }

    /**
     * V36 任务 E 后单块重试入口已删：首页错误视图二合一为整屏一个，
     * 重试统一走 [refresh] 整页重拉两块（原先「个人信息一套错、对局区一套错」的分区重试不复存在）。
     */
    private fun loadProfile(uid: String, server: ServerId, force: Boolean) {
        val gen = ++profileGeneration
        val current = _uiState.value.profile
        // force 只决定是否绕缓存，不再把已有内容打成 Loading（下拉刷新保留整页）；
        // 首载（初值 Loading）与 Error 重试仍进 Loading 转圈
        val silent = current is Async.Content
        if (!silent) _uiState.value = _uiState.value.copy(profile = Async.Loading)
        pendingLoads += 1
        viewModelScope.launch {
            // 重试统一由 MihoyoClient 指数退避负责（设计红线 2：retcode 语义集中在客户端层，
            // 页面不得自行判定重发）。原先的 fetchWithSilentRetry 与退避重试叠加，
            // 会把最坏等待翻倍，V36 起删除。
            val next: Async<PageInfo> = try {
                val pageInfo = container.repository.fetchMyHomePageCached(uid, server, force).pageInfo
                if (pageInfo != null) Async.Content(pageInfo) else Async.Error()
            } catch (e: Exception) {
                Async.Error(apiErrorText(e))
            }
            if (gen == profileGeneration) {
                _uiState.value = _uiState.value.copy(profile = next)
            }
            pendingLoads -= 1
            maybeEndRefreshing()
        }
    }

    private fun loadRecords(uid: String, server: ServerId, force: Boolean) {
        val gen = ++recordsGeneration
        val current = _uiState.value.records
        // 同 loadProfile：有内容即静默，force 不再打回 Loading
        val silent = current is Async.Content
        if (!silent) _uiState.value = _uiState.value.copy(records = Async.Loading)
        pendingLoads += 1
        viewModelScope.launch {
            val next: Async<List<GameRecord>> = try {
                Async.Content(
                    container.repository.fetchGameRecordsCached(uid, server, force).gameRecords.orEmpty(),
                )
            } catch (e: Exception) {
                Async.Error(apiErrorText(e))
            }
            if (gen == recordsGeneration) {
                _uiState.value = _uiState.value.copy(records = next)
            }
            pendingLoads -= 1
            maybeEndRefreshing()
        }
    }

    /**
     * 统一判定：在途请求清零 **且** 两块状态都落定（不再 Loading）才收回下拉刷新指示器。
     * 只看状态会提前收圈：静默刷新（两块都有内容）不进 Loading，先返回的那一块
     * 一落定就让状态判定成立，而慢的那块仍在途 ⇒ 圈收了、数据还没换（V36 审计 P3）。
     */
    private fun maybeEndRefreshing() {
        if (_refreshing.value && pendingLoads == 0 &&
            _uiState.value.profile !is Async.Loading &&
            _uiState.value.records !is Async.Loading
        ) {
            _refreshing.value = false
        }
    }

    companion object {
        /** 首刷 profile 与 records 的错峰间隔（毫秒）：落在米游社保流窗口之外 */
        const val FIRST_LOAD_STAGGER_MS: Long = 400L

        fun factory(app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.AndroidViewModelFactory(app) {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(app) as T
            }
    }
}
