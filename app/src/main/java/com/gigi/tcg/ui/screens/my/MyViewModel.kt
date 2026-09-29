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
import android.util.Log
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

/**
 * 切旬/翻页连点时，单旬战绩请求的合并延迟（V37-E）。
 * 取值口径：Android 的双击判定窗口 `ViewConfiguration.getDoubleTapTimeout()` = 300ms ⇒
 * 300ms 内的连续点击按"一次意图"处理，不再逐次打接口。
 * 🔴 只在**上一旬的请求还在途**时生效（见 [challengeRecordFetchDelayMs]）：
 * 首次装载和回看缓存旬都是零额外延迟，不会因为 dock 把翻页变得很顺手就给每次点击都加 300ms。
 * 私有接口有 -500004 保流窗口（同 [MyViewModel.MY_LOAD_STAGGER_MS] 的错峰理由），
 * dock 化之后「切旬」从低频动作变成了高频动作，这里是配套的收口。
 */
internal const val CHALLENGE_RECORD_COALESCE_MS: Long = 300L

/** 纯函数（JVM 单测钉死）：上一次单旬请求仍在途 ⇒ 合并连点、延迟取；否则立即取 */
internal fun challengeRecordFetchDelayMs(previousFetchInFlight: Boolean): Long =
    if (previousFetchInFlight) CHALLENGE_RECORD_COALESCE_MS else 0L

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

    /** 头像回填链的在途 job（同一时刻只允许一条链，重复进页/换账户先取消上一条） */
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
     * 进「我的」页的一揽子装载：5 组摘要 + 头像回填（V36/2b 引入，V37-G 起逐账户补**全部**缺头账户）
     * **串行 + 错峰**（间隔 [MY_LOAD_STAGGER_MS]）。
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
                    { backfillAllAvatars() },
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
     * 存量账户头像回填（V36/2b 引入，🔴 V37-G 起覆盖**全部账户**，用户澄清 B2「所有账户都要有」）：
     * 旧实现只补激活账户 ⇒ 切到第二个账户时它仍是灰占位，要下次进本页才补。
     * 现在按 [planAvatarBackfill] 筛出缺头像的账户**逐个**补，其余口径不变：
     * - 数据源仍是 my_home_page 的 `page_info.avatar_url`（gcg/basicInfo 结构上没有头像）；
     * - 🔴 串行 + 错峰（[MY_LOAD_STAGGER_MS]，同 [runStaggeredSteps] 与整页装载链的口径）：
     *   一次性并发 N 个私有接口正中米游社 -500004 保流窗口 ⇒ 任一时刻链上只有一条头像请求在途；
     * - 每个账户用它**自己绑定的服务器** [StoredAccount.server]（多账户可以分属不同服），
     *   激活账户与容器 currentServer 同值，故激活账户的行为与 V36/2b 完全一致；
     * - 同值/未命中不重写（[CredentialStore.replaceAvatar] 的幂等判据），补齐后稳态**零请求**。
     */
    fun backfillAllAvatars(staggerMs: Long = MY_LOAD_STAGGER_MS) {
        val pending = planAvatarBackfill(container.accounts.value)
        if (pending.isEmpty()) return
        avatarJob?.cancel()
        avatarJob = viewModelScope.launch {
            runStaggeredSteps(
                staggerMs,
                pending.map { account ->
                    suspend { backfillOneAvatar(account.uid, account.server()) }
                },
            )
        }
    }

    /**
     * 补单个账户的头像（[backfillAllAvatars] 链上的一步）。
     * 🔴 逐账户隔离：本账户请求失败（网络 / 风控 / 1034 类）只跳过本账户，异常不外抛，
     *   链上后面的账户照常补 —— 一个账户的坏数据不许拖垮整页头像。
     * 🔴 落盘键永远是**这一步自己的 uid**，不读「当前激活账户」⇒ 回填期间用户切账号，
     *   旧账户的头像不可能挂到新账户头上。每次进这一步都用实时索引复核：
     *   账户已登出（索引里没有它）或已被登录/续命路径补齐 ⇒ 直接跳过，不发请求。
     */
    private suspend fun backfillOneAvatar(uid: String, server: ServerId) {
        val account = container.accounts.value.firstOrNull { it.uid == uid }
            ?: run {
                // 诊断通道（V37-I 任务 B）：回填失败曾长期静默（链上看不到任何错误），
                // 逐账户记一行结果；🔴 只进 logcat，不弹 UI、不写 account_index
                Log.i(AVATAR_BACKFILL_LOG_TAG, "skip uid=$uid reason=loggedOut")
                return
            }
        if (!account.avatar.isNullOrBlank()) {
            Log.i(AVATAR_BACKFILL_LOG_TAG, "skip uid=$uid nickname=${account.nickname} reason=alreadyFilled")
            return
        }
        var failure: String? = null
        val avatar = try {
            container.repository.fetchMyHomePageCached(uid, server).pageInfo?.avatarUrl
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            failure = "${e.javaClass.simpleName}: ${e.message}"
            null
        }
        if (avatar.isNullOrBlank()) {
            if (failure != null) {
                Log.w(AVATAR_BACKFILL_LOG_TAG, "fail uid=$uid nickname=${account.nickname} error=$failure")
            } else {
                Log.w(AVATAR_BACKFILL_LOG_TAG, "fail uid=$uid nickname=${account.nickname} reason=noAvatarUrl")
            }
            return
        }
        // 在途期间该账户可能被别处补齐或已登出：replaceAvatar 的「uid 未命中 / 同值」判据兜住，
        // 返回 false 就不落盘也不刷列表（避免每次进本页都惊动账户行）
        if (container.credentialStore.updateAccountAvatar(uid, avatar)) {
            container.refreshAccounts()
            Log.i(AVATAR_BACKFILL_LOG_TAG, "ok uid=$uid nickname=${account.nickname} result=backfilled")
        } else {
            Log.i(AVATAR_BACKFILL_LOG_TAG, "skip uid=$uid nickname=${account.nickname} reason=replaceNoop")
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
        // 🔴 连点合并（V37-E）：dock 把「切旬」变成高频动作（一次翻页 = 一次加载）。
        // 在途标记必须读在下面 cancel **之前** —— cancel 后旧 Job 立刻 isActive=false，
        // 就再也判不出"用户是不是在连着翻页"了。
        val fetchDelayMs = challengeRecordFetchDelayMs(jobs[_challengeRecord]?.isActive == true)
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
            // 连点时只有最后一次真正打到接口：前面的在途 Job 已被 cancel，delay 直接被中断
            if (fetchDelayMs > 0) delay(fetchDelayMs)
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

/**
 * 头像回填计划（纯函数，JVM 单测钉死）：筛出**缺头像**的账户（null 或空白），保持索引序
 * （索引头 = 最近使用 ⇒ 激活账户天然是第一个被补的）。
 * 🔴 空列表 ⇒ [MyViewModel.backfillAllAvatars] 直接返回，一次请求都不发（V37-G 稳态零请求判据：
 * 头像补齐后每次进「我的」页都必须零请求、零写盘）。
 * 空白串同样算缺：my_home_page 偶发下发空 avatar_url，占位回落比一张空图有用。
 */
internal fun planAvatarBackfill(accounts: List<StoredAccount>): List<StoredAccount> =
    accounts.filter { it.avatar.isNullOrBlank() }

/** 头像回填诊断日志 tag（V37-I 任务 B）：logcat `adb logcat -s GigiAvatar` 可逐账户看结果 */
private const val AVATAR_BACKFILL_LOG_TAG = "GigiAvatar"
