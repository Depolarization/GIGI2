// 「我的」页状态（V35 P0 骸架）：账号管理直接投影容器级 StateFlow（accounts/activeUid），
// 切账号/登出/添加后由 AppContainer 自动刷新，本 VM 不复制状态。
// 个人信息接 gcg/basicInfo（昵称/牌手等级，5min TTL 内存缓存；失败静默降级 ——
// 「我的」页总有账户本地数据可看，接口失败不影响页面可用。V36/2 起牌手等级并入账户行副标题，
// 原「个人信息」分区卡片已删（用户第 7、14 项：与账户行重复、且一段一段加独立文本是错的）。
// P1/P2 计划在此补：卡组/卡背/收藏对局/胜冠之试四组接口数据（设计文档 §4.3 分阶段）。
// P2 已补四组：全部沿用 loadProfile 的静默口径 —— 失败吞异常置 null，页面据此显示空态，
// 任何一组接口失败都**不允许**把「我的」页拖进错误态（一级页还有账户本地数据可看）。
//
// 🔴 V36/2 装载语义（用户第 12 项「进卡组页再返回，胜冠之试和收藏的数字刷新了一下」）：
// 旧 load() 无条件 `target.value = null` 再重取。force 全 false、零网络请求、5min 内存缓存秒回，
// **坏的并不是缓存失效，而是"先清空再回填"这个动作本身**。现在拆成两条路径：
// - 账号切换（activeUid 变化）⇒ 清空（旧账号的数据不该挂在新账号页面上）；
// - 页面重入（离开 Composition 导致 LaunchedEffect 重启）⇒ 不清空，只 cancel 在途请求，静默替换。
// ⚠️ `LaunchedEffect(key)` 的 key 相同只防"同一次组合内 key 变化"，**不防组合重新进入**。

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 账户归属守卫（纯状态机，JVM 单测钉死）：只有 uid **变化**（含首次装载与登出置 null）才返回 true，
 * 调用方据此决定要不要清空账户级 StateFlow。同一 uid 的重复装载（页面重入）返回 false。
 */
internal class AccountScopeGuard {
    private var uid: String? = null

    val currentUid: String? get() = uid

    /** @return true = 账户变了（或首次装载），调用方必须清空所有账户级数据 */
    fun onLoadedFrom(nextUid: String?): Boolean {
        val changed = uid != nextUid
        uid = nextUid
        return changed
    }
}

/**
 * 错峰串行：steps 逐个执行，相邻两步之间隔 [staggerMs]。
 * 口径与首页 `runStaggeredFirstLoad` 一致（一步 → 延迟 → 下一步），只是步数从 2 扩到「我的」页的 5 组摘要；
 * 提取为纯函数便于 JVM 单测钉死时序（首页那次也是并发打私有接口触发 -500004 保流后改的）。
 */
internal suspend fun runStaggeredSteps(staggerMs: Long, steps: List<suspend () -> Unit>) {
    steps.forEachIndexed { index, step ->
        if (index > 0) delay(staggerMs)
        step()
    }
}

class MyViewModel(app: Application) : AndroidViewModel(app) {

    private val container: AppContainer = (app as GigiApp).container

    /** 账号列表与激活账户：容器级状态的只读投影（切账号/登出/添加后容器刷新，此处自动跟随） */
    val accounts: StateFlow<List<StoredAccount>> = container.accounts
    val activeUid: StateFlow<String?> = container.activeAccountUid

    private val _profile = MutableStateFlow<GcgBasicInfoData?>(null)

    /** 个人信息（昵称/牌手等级）；null = 加载中或不可用（账户行降级显示本地昵称/UID） */
    val profile: StateFlow<GcgBasicInfoData?> = _profile.asStateFlow()

    private val _deckList = MutableStateFlow<GcgDeckListData?>(null)

    /** 卡组页数据 + 一级页摘要；null = 加载中或不可用（页面显示空态，不显示 0 组） */
    val deckList: StateFlow<GcgDeckListData?> = _deckList.asStateFlow()

    private val _cardBackList = MutableStateFlow<GcgCardBackListData?>(null)

    /** 卡背页数据（含未收集项，靠 hasObtained 区分）；null = 加载中或不可用 */
    val cardBackList: StateFlow<GcgCardBackListData?> = _cardBackList.asStateFlow()

    private val _matchList = MutableStateFlow<GcgMatchListData?>(null)

    /** 收藏对局页数据；null = 加载中或不可用。favouriteMatches 实测可为空数组（正常） */
    val matchList: StateFlow<GcgMatchListData?> = _matchList.asStateFlow()

    private val _challengeSchedule = MutableStateFlow<GcgChallengeScheduleData?>(null)

    /** 胜冠之试旬列表；null = 加载中或不可用 */
    val challengeSchedule: StateFlow<GcgChallengeScheduleData?> = _challengeSchedule.asStateFlow()

    private val _challengeRecord = MutableStateFlow<GcgChallengeRecordData?>(null)

    /** 当前选中的一旬战绩；null = 加载中或该旬不可用。切旬时先清 null，避免残留上一旬数字 */
    val challengeRecord: StateFlow<GcgChallengeRecordData?> = _challengeRecord.asStateFlow()

    /** 每个 StateFlow 对应一条在途请求，重新加载同一格时先取消它 */
    private val jobs = mutableMapOf<MutableStateFlow<*>, Job?>()

    /** 账户归属守卫：见 [AccountScopeGuard]，只有换账户才清空、页面重入不清空 */
    private val accountGuard = AccountScopeGuard()

    /** 单旬缓存归属的账户；uid 变了就整表作废（切账户不许带着上一账户的战绩继续翻旬） */
    private var challengeRecordUid: String? = null
    private val challengeRecordCache = mutableMapOf<Int, GcgChallengeRecordData>()

    /** 单旬战绩当前归属的旬；换旬必须先清 null（预览区不能残留上一旬的数字） */
    private var challengeRecordScheduleId: Int? = null

    /** [loadAllStaggered] 的在途串行链，重新触发时先取消，避免两条链并发 */
    private var staggeredJob: Job? = null

    /** 头像回填的在途请求（同一时刻只允许一条，重复进页不叠请求） */
    private var avatarJob: Job? = null

    /** 作废上一账户的全部数据：取消所有在途请求 + 6 个账户级 StateFlow 整组清空 */
    private fun clearAccountScoped() {
        jobs.values.forEach { it?.cancel() }
        jobs.clear()
        _profile.value = null
        _deckList.value = null
        _cardBackList.value = null
        _matchList.value = null
        _challengeSchedule.value = null
        _challengeRecord.value = null
        challengeRecordScheduleId = null
    }

    /**
     * 通用装载：**只在账户变化时清空**（见 [accountGuard]），随后拉新值并静默替换。
     * 页面重入（LaunchedEffect 随 Composition 重新进入而重启）走的是同一条 uid ⇒ 旧值留在原位，
     * 内存缓存命中后直接覆盖 —— 不再有"数字先消失再出现"的闪动（V36/2 用户第 12 项）。
     * 在途请求始终先 cancel：重入时同格并发只会留下最后一次结果。
     * 失败静默（吞异常置 null），页面按 null 走空态；`CancellationException` 必须放行。
     */
    private fun <T : Any> load(
        target: MutableStateFlow<T?>,
        force: Boolean,
        fetch: suspend (uid: String, server: ServerId, force: Boolean) -> T?,
    ) {
        jobs[target]?.cancel()
        val uid = container.activeAccountUid.value
        if (accountGuard.onLoadedFrom(uid)) clearAccountScoped()
        if (uid == null) return
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
     * 进「我的」页的一揽子装载：5 组摘要 + 头像回填（V36/2b）**串行 + 错峰**（间隔 [MY_LOAD_STAGGER_MS]）。
     * 🔴 一次性并发 5 个私有接口正中米游社 -500004 保流窗口，失败率抬升；
     * 首页首刷早已改错峰（`runStaggeredFirstLoad`），本页沿用同一口径与同一间隔。
     */
    fun loadAllStaggered(staggerMs: Long = MY_LOAD_STAGGER_MS) {
        staggeredJob?.cancel()
        staggeredJob = viewModelScope.launch {
            runStaggeredSteps(
                staggerMs,
                listOf(
                    { loadProfile() },
                    { backfillActiveAvatar() },
                    { loadDeckList() },
                    { loadCardBackList() },
                    { loadMatchList() },
                    { loadChallengeSchedule() },
                ),
            )
        }
    }

    /**
     * 拉当前激活账户的个人信息（牌手等级，供账户行副标题）。
     * 调用点：进入页面 / 激活账户变化（LaunchedEffect(activeUid)）。
     * 失败静默：账户行总有本地昵称/UID 可显示，接口只是增强。
     */
    fun loadProfile(force: Boolean = false) {
        load(_profile, force) { uid, server, f ->
            container.repository.fetchGcgBasicInfo(uid, server, f)
        }
    }

    /**
     * 存量账户头像回填（V36/2b）：`StoredAccount.avatar` 原唯一来源 getUserGameRolesByCookie
     * 实测**不下发** avatar_url，且 V36 新字段对存量索引无回填 ⇒ 账户行永远灰占位。
     * 唯一下发头像的是首页资料卡 my_home_page 的 `page_info.avatar_url`（repository 已有 45s
     * 内存缓存，首页刚拉过时静默命中，不是新增端点）。
     * 🔴 gcg/basicInfo（[loadProfile] 的 profile）结构上没有头像也没有 uid/role_id ⇒ 不做 uid 匹配，
     * 走「按当前激活账户落一次盘」：缺头像才取、同值不重写（CredentialStore.replaceAvatar），
     * 落盘后 refreshAccounts() 让账户行立即反映；补齐后本步骤零请求、零写盘。
     */
    fun backfillActiveAvatar() {
        val uid = container.activeAccountUid.value ?: return
        val account = container.accounts.value.firstOrNull { it.uid == uid } ?: return
        if (!account.avatar.isNullOrBlank()) return
        avatarJob?.cancel()
        val server = container.currentServer.value
        avatarJob = viewModelScope.launch {
            val avatar = try {
                container.repository.fetchMyHomePageCached(uid, server).pageInfo?.avatarUrl
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
            if (avatar.isNullOrBlank()) return@launch
            // 在途期间切了账户：不能把旧账户的头像落到新账户头上
            if (container.activeAccountUid.value != uid) return@launch
            if (container.credentialStore.updateAccountAvatar(uid, avatar)) {
                container.refreshAccounts()
            }
        }
    }

    /** 我的卡组（「我的资产」摘要 + 卡组页） */
    fun loadDeckList(force: Boolean = false) {
        load(_deckList, force) { uid, server, f -> container.repository.fetchGcgDeckListCached(uid, server, f) }
    }

    /** 卡背图鉴（「我的资产」摘要 + 卡背页） */
    fun loadCardBackList(force: Boolean = false) {
        load(_cardBackList, force) { uid, server, f -> container.repository.fetchGcgCardBackListCached(uid, server, f) }
    }

    /** 收藏对局（「最近对局」摘要 + 收藏页） */
    fun loadMatchList(force: Boolean = false) {
        load(_matchList, force) { uid, server, f -> container.repository.fetchGcgMatchListCached(uid, server, f) }
    }

    /** 胜冠之试旬列表（「最近对局」摘要 + 胜冠页的旬列表） */
    fun loadChallengeSchedule(force: Boolean = false) {
        load(_challengeSchedule, force) { uid, server, f ->
            container.repository.fetchGcgChallengeScheduleCached(uid, server, f)
        }
    }

    /**
     * 单旬战绩：按 scheduleId 按需拉（一次选一旬拉一旬，不预取 9 旬）。
     * 同一账户内来回切旬走 [challengeRecordCache]（内存里秒回，不重复打接口）。
     * 🔴 换旬必须先清 null —— [load] 现在的语义是"同账户不清空"，
     * 少了这一步，预览区会顶着上一旬的胜场数显示。
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
        if (scheduleId != challengeRecordScheduleId) {
            challengeRecordScheduleId = scheduleId
            jobs[_challengeRecord]?.cancel()
            _challengeRecord.value = null
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
        /** 摘要串行装载的错峰间隔，取值钉住首页 `HomeViewModel.FIRST_LOAD_STAGGER_MS`（home/ 归 V36-3，不跨包引符号） */
        const val MY_LOAD_STAGGER_MS: Long = 400L

        fun factory(app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.AndroidViewModelFactory(app) {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MyViewModel(app) as T
            }
    }
}
