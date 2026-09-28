// 「我的」页状态（V35 P0 骸架）：账号管理直接投影容器级 StateFlow（accounts/activeUid），
// 切账号/登出/添加后由 AppContainer 自动刷新，本 VM 不复制状态。
// 个人信息区接 gcg/basicInfo（昵称/牌手等级，5min TTL 内存缓存；失败静默降级 ——
// 分区② 有账户本地数据兜底，接口失败不影响页面可用）。
// P1/P2 计划在此补：卡组/卡背/收藏对局/胜冠之试四组接口数据（设计文档 §4.3 分阶段）。

package com.gigi.tcg.ui.screens.my

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gigi.tcg.GigiApp
import com.gigi.tcg.data.auth.StoredAccount
import com.gigi.tcg.data.model.GcgBasicInfoData
import com.gigi.tcg.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MyViewModel(app: Application) : AndroidViewModel(app) {

    private val container: AppContainer = (app as GigiApp).container

    /** 账号列表与激活账户：容器级状态的只读投影（切账号/登出/添加后容器刷新，此处自动跟随） */
    val accounts: StateFlow<List<StoredAccount>> = container.accounts
    val activeUid: StateFlow<String?> = container.activeAccountUid

    private val _profile = MutableStateFlow<GcgBasicInfoData?>(null)

    /** 个人信息区数据（昵称/牌手等级）；null = 加载中或不可用（降级显示账户本地昵称/UID） */
    val profile: StateFlow<GcgBasicInfoData?> = _profile.asStateFlow()

    private var profileJob: Job? = null

    /**
     * 拉当前激活账户的个人信息。调用点：进入页面 / 激活账户变化（LaunchedEffect(activeUid)）。
     * 每次调用先取消在途请求并清空旧值 —— 切账号后**不允许**残留上一账户的等级展示。
     * 失败静默（吞异常置 null）：分区② 总是有账户本地的昵称/UID 可显示，接口只是增强。
     */
    fun loadProfile(force: Boolean = false) {
        profileJob?.cancel()
        _profile.value = null
        val uid = container.activeAccountUid.value ?: return
        profileJob = viewModelScope.launch {
            val result = try {
                container.repository.fetchGcgBasicInfo(uid, container.currentServer.value, force)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
            _profile.value = result
        }
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.AndroidViewModelFactory(app) {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MyViewModel(app) as T
            }
    }
}
