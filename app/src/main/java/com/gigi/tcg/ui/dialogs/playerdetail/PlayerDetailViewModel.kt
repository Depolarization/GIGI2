// 玩家详情状态：移植 web/src/state/playerDetail.tsx 的查询/缓存/节流语义。
// - 1 秒详情节流 + 5 分钟资料缓存放在 companion（进程级），对齐 tsx 里模块级 detailThrottle
//   与"所有详情入口共享同一实例"的语义（本工程不提供全局 controller，故以类级单例近似）；
// - 换服作废缓存（对齐 tsx useEffect[server.id]）；
// - 错误统一经 describeApiError 出文案，重试入口暴露给 UI；retcode 业务失败不可重试。

package com.gigi.tcg.ui.dialogs.playerdetail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gigi.tcg.GigiApp
import com.gigi.tcg.data.api.API_ERROR_KIND_RETCODE
import com.gigi.tcg.data.api.ApiError
import com.gigi.tcg.data.api.describeApiError
import com.gigi.tcg.data.model.OtherHomePageData
import com.gigi.tcg.di.AppContainer
import com.gigi.tcg.domain.Throttle
import com.gigi.tcg.domain.generateCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** 玩家详情弹窗 UI 状态（对齐 PlayerDetailUiState：loading / pageInfo / uid；错误态带可重试标记） */
sealed interface DetailUiState {
    data object Loading : DetailUiState

    /** data.pageInfo 为 null → "查无此玩家"；pageInfo.isShield==true → 无权访问分支 */
    data class Content(val uid: String, val code: String, val data: OtherHomePageData) : DetailUiState

    data class Error(val message: String, val canRetry: Boolean) : DetailUiState
}

class PlayerDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val container: AppContainer = (app as GigiApp).container

    private val _uiState = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    /** 弹窗靠它判定"是否本人"：查询码只在本人详情里展示 */
    val sessionUid: StateFlow<String?> = container.sessionUid

    private var lastUid: String? = null

    init {
        // 换服：玩家详情缓存属于旧服务器，直接作废（跳过首帧，避免每次建 VM 都清空进程级缓存）
        viewModelScope.launch {
            container.currentServer.drop(1).collect { clearPlayerDetailCache() }
        }
    }

    /** 打开玩家详情：1s 内重复点击静默忽略；命中缓存直接展示 */
    fun openPlayerDetail(uid: String) {
        lastUid = uid
        if (!detailThrottle()) return
        val cached = cache[uid]
        if (cached != null && System.currentTimeMillis() - cached.at < PLAYER_CACHE_TTL_MS) {
            _uiState.value = cached.state
            return
        }
        fetch(uid)
    }

    /** 显式重试：绕过节流闸门，重新查询上一个 UID */
    fun retry() {
        val uid = lastUid ?: return
        fetch(uid)
    }

    private fun fetch(uid: String) {
        val code = generateCode(uid.toLongOrNull() ?: 0L)
        _uiState.value = DetailUiState.Loading
        viewModelScope.launch {
            val myUid = container.sessionUid.value.orEmpty()
            val server = container.currentServer.value
            try {
                val data = container.repository.fetchOtherHomePage(code, myUid, server)
                val content = DetailUiState.Content(uid, code, data)
                cache[uid] = CacheEntry(content, System.currentTimeMillis())
                if (lastUid == uid) _uiState.value = content
            } catch (e: Exception) {
                if (lastUid == uid) {
                    _uiState.value = DetailUiState.Error(
                        message = describeApiError(e),
                        canRetry = e !is ApiError || e.kind != API_ERROR_KIND_RETCODE,
                    )
                }
            }
        }
    }

    private class CacheEntry(val state: DetailUiState.Content, val at: Long)

    companion object {
        private const val PLAYER_CACHE_TTL_MS = 5 * 60 * 1000L

        // 进程级：所有详情入口共享（对齐 tsx 模块级 detailThrottle / 全局缓存语义）。
        // 均在主线程访问（openPlayerDetail 来自组合、fetch 回写经 viewModelScope 主调度器恢复）。
        private val detailThrottle = Throttle(1000)
        private val cache = HashMap<String, CacheEntry>()

        private fun clearPlayerDetailCache() = cache.clear()

        fun factory(app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.AndroidViewModelFactory(app) {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PlayerDetailViewModel(app) as T
            }
    }
}
