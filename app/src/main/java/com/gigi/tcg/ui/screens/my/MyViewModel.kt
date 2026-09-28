// 「我的」页状态（V35 P0 骸架）：账号管理直接投影容器级 StateFlow（accounts/activeUid），
// 切账号/登出/添加后由 AppContainer 自动刷新，本 VM 不复制状态。
// 个人信息区接 gcg/basicInfo（昵称/牌手等级，5min TTL 内存缓存；失败静默降级 ——
// 分区② 有账户本地数据兜底，接口失败不影响页面可用）。
// P1/P2 计划在此补：卡组/卡背/收藏对局/胜冠之试四组接口数据（设计文档 §4.3 分阶段）。
// P2 已补四组：全部沿用 loadProfile 的静默口径 —— 失败吞异常置 null，页面据此显示空态，
// 任何一组接口失败都**不允许**把「我的」页拖进错误态（一级页还有账户本地数据可看）。

package com.gigi.tcg.ui.screens.my

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gigi.tcg.GigiApp
import com.gigi.tcg.data.ServerId
import com.gigi.tcg.data.auth.StoredAccount
import com.gigi.tcg.data.model.GcgBasicInfoData
import com.gigi.tcg.data.model.GcgChallengeRecordData
import com.gigi.tcg.data.model.GcgChallengeScheduleData
import com.gigi.tcg.data.model.GcgCardBackListData
import com.gigi.tcg.data.model.GcgDeckListData
import com.gigi.tcg.data.model.GcgMatchListData
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

    private val _deckList = MutableStateFlow<GcgDeckListData?>(null)

    /** 分区③/卡组页数据；null = 加载中或不可用（页面显示空态，不显示 0 组） */
    val deckList: StateFlow<GcgDeckListData?> = _deckList.asStateFlow()

    private val _cardBackList = MutableStateFlow<GcgCardBackListData?>(null)

    /** 分区③/卡背页数据（含未收集项，靠 hasObtained 区分）；null = 加载中或不可用 */
    val cardBackList: StateFlow<GcgCardBackListData?> = _cardBackList.asStateFlow()

    private val _matchList = MutableStateFlow<GcgMatchListData?>(null)

    /** 分区④/收藏对局页数据；null = 加载中或不可用。favouriteMatches 实测可为空数组（正常） */
    val matchList: StateFlow<GcgMatchListData?> = _matchList.asStateFlow()

    private val _challengeSchedule = MutableStateFlow<GcgChallengeScheduleData?>(null)

    /** 分区④/胜冠之试旬列表；null = 加载中或不可用 */
    val challengeSchedule: StateFlow<GcgChallengeScheduleData?> = _challengeSchedule.asStateFlow()

    private val _challengeRecord = MutableStateFlow<GcgChallengeRecordData?>(null)

    /** 当前选中的一旬战绩；null = 加载中或该旬不可用。切旬时先清 null，避免残留上一旬数字 */
    val challengeRecord: StateFlow<GcgChallengeRecordData?> = _challengeRecord.asStateFlow()

    /** 每个 StateFlow 对应一条在途请求，重新加载同一格时先取消它 */
    private val jobs = mutableMapOf<MutableStateFlow<*>, Job?>()

    /** 单旬缓存归属的账户；uid 变了就整表作废（切账户不许带着上一账户的战绩继续翻旬） */
    private var challengeRecordUid: String? = null
    private val challengeRecordCache = mutableMapOf<Int, GcgChallengeRecordData>()

    /**
     * 通用装载：先取消本格在途请求并清空旧值，再拉新值。
     * 切账号时 [loadProfile] 等被重新触发，旧账户的数据一定先落地清空再谈新值 ——
     * 不允许出现「A 账户的卡组数还挂在 B 账户页面上」的中间态。
     * 失败静默（吞异常置 null），页面按 null 走空态；`CancellationException` 必须放行。
     */
    private fun <T : Any> load(
        target: MutableStateFlow<T?>,
        force: Boolean,
        fetch: suspend (uid: String, server: ServerId, force: Boolean) -> T?,
    ) {
        jobs[target]?.cancel()
        target.value = null
        val uid = container.activeAccountUid.value ?: return
        val server = container.currentServer.value
        jobs[target] = viewModelScope.launch {
            val result = try {
                fetch(uid, server, force)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
            target.value = result
        }
    }

    /**
     * 拉当前激活账户的个人信息。调用点：进入页面 / 激活账户变化（LaunchedEffect(activeUid)）。
     * 失败静默：分区② 总是有账户本地的昵称/UID 可显示，接口只是增强。
     */
    fun loadProfile(force: Boolean = false) {
        load(_profile, force) { uid, server, f ->
            container.repository.fetchGcgBasicInfo(uid, server, f)
        }
    }

    /** 我的卡组（分区③摘要 + 卡组页） */
    fun loadDeckList(force: Boolean = false) {
        load(_deckList, force) { uid, server, f -> container.repository.fetchGcgDeckListCached(uid, server, f) }
    }

    /** 卡背图鉴（分区③摘要 + 卡背页） */
    fun loadCardBackList(force: Boolean = false) {
        load(_cardBackList, force) { uid, server, f -> container.repository.fetchGcgCardBackListCached(uid, server, f) }
    }

    /** 收藏对局（分区④摘要 + 收藏页） */
    fun loadMatchList(force: Boolean = false) {
        load(_matchList, force) { uid, server, f -> container.repository.fetchGcgMatchListCached(uid, server, f) }
    }

    /** 胜冠之试旬列表（分区④摘要 + 胜冠页列表态） */
    fun loadChallengeSchedule(force: Boolean = false) {
        load(_challengeSchedule, force) { uid, server, f ->
            container.repository.fetchGcgChallengeScheduleCached(uid, server, f)
        }
    }

    /**
     * 单旬战绩：按 scheduleId 按需拉（一次进详情拉一旬，不预取 9 旬）。
     * 同一账户内来回切旬走 [challengeRecordCache]（内存里秒回，不重复打接口）。
     */
    fun loadChallengeRecord(scheduleId: Int, force: Boolean = false) {
        val uid = container.activeAccountUid.value ?: run {
            jobs[_challengeRecord]?.cancel()
            _challengeRecord.value = null
            return
        }
        if (uid != challengeRecordUid) {
            challengeRecordUid = uid
            challengeRecordCache.clear()
        }
        if (!force) {
            challengeRecordCache[scheduleId]?.let { cached ->
                jobs[_challengeRecord]?.cancel()
                _challengeRecord.value = cached
                return
            }
        }
        load(_challengeRecord, force) { fetchUid, server, f ->
            container.repository.fetchGcgChallengeRecordCached(fetchUid, server, scheduleId, f).also {
                // 只有仍属当前账户才入表：切账户期间在途请求已被 cancel，不会走到这里
                if (container.activeAccountUid.value == fetchUid) challengeRecordCache[scheduleId] = it
            }
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
