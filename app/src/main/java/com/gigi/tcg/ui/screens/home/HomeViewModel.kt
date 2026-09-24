// 首页状态机：移植 web/src/pages/HomePage.tsx 的 ProfileState/RecordsState 双块独立加载。
// - 两块各自持有代数（对齐 profileSeq/recordsSeq）：单块重试不作废另一块在途请求；
// - 已有数据时静默更新（不回落 Loading，避免整屏抖动）；任何结局 loading 必落定；
// - retcode 判定集中在数据层（设计红线 2），本层仅经 describeApiError 转文案；
//   凭据失效由 AppGate 后台校验统一处理，页面不自行踢会话。

package com.gigi.tcg.ui.screens.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gigi.tcg.GigiApp
import com.gigi.tcg.data.ServerId
import com.gigi.tcg.data.api.describeApiError
import com.gigi.tcg.data.model.GameRecord
import com.gigi.tcg.data.model.PageInfo
import com.gigi.tcg.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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

    /** 会话 UID（容器镜像）：路由据此决定点击回调传参 */
    val sessionUid: StateFlow<String?> = container.sessionUid

    // 两块各自代数：仅最新一次请求允许落定状态（对齐 web 的 seq 语义）
    private var profileGeneration = 0
    private var recordsGeneration = 0

    init {
        // AppGate 零等待渲染：会话可能尚未写入，等首个非空 UID 再并行拉两块
        viewModelScope.launch {
            val uid = container.sessionUid.filterNotNull().first()
            loadProfile(uid, container.currentServer.value, force = false)
            loadRecords(uid, container.currentServer.value, force = false)
        }
    }

    /** 顶栏刷新：两块同时 force 绕缓存 */
    fun refresh() {
        val uid = container.sessionUid.value ?: return
        val server = container.currentServer.value
        loadProfile(uid, server, force = true)
        loadRecords(uid, server, force = true)
    }

    /** ErrorState 重试按钮：对应块 force 重取 */
    fun retryProfile() {
        val uid = container.sessionUid.value ?: return
        loadProfile(uid, container.currentServer.value, force = true)
    }

    fun retryRecords() {
        val uid = container.sessionUid.value ?: return
        loadRecords(uid, container.currentServer.value, force = true)
    }

    private fun loadProfile(uid: String, server: ServerId, force: Boolean) {
        val gen = ++profileGeneration
        val current = _uiState.value.profile
        val silent = current is Async.Content && !force
        if (!silent) _uiState.value = _uiState.value.copy(profile = Async.Loading)
        viewModelScope.launch {
            val next: Async<PageInfo> = try {
                val pageInfo =
                    container.repository.fetchMyHomePageCached(uid, server, force).pageInfo
                if (pageInfo != null) Async.Content(pageInfo) else Async.Error()
            } catch (e: Exception) {
                Async.Error(describeApiError(e))
            }
            if (gen == profileGeneration) {
                _uiState.value = _uiState.value.copy(profile = next)
            }
        }
    }

    private fun loadRecords(uid: String, server: ServerId, force: Boolean) {
        val gen = ++recordsGeneration
        val current = _uiState.value.records
        val silent = current is Async.Content && !force
        if (!silent) _uiState.value = _uiState.value.copy(records = Async.Loading)
        viewModelScope.launch {
            val next: Async<List<GameRecord>> = try {
                Async.Content(
                    container.repository
                        .fetchGameRecordsCached(uid, server, force).gameRecords.orEmpty(),
                )
            } catch (e: Exception) {
                Async.Error(describeApiError(e))
            }
            if (gen == recordsGeneration) {
                _uiState.value = _uiState.value.copy(records = next)
            }
        }
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.AndroidViewModelFactory(app) {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(app) as T
            }
    }
}
