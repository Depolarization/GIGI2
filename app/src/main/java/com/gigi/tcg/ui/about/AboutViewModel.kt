// 关于页状态（V9-G）：把 V9-D 的更新检查 / 公告 / Bug 上报数据层接到 UI。
// 本类只做「调数据层 + 记状态 + 存已读」，版本比较与镜像回退一律不在此重算。
//
// 网络全在 viewModelScope：页面关掉即随 VM 取消，不留后台请求。
// CancellationException 必须原样上抛——吞掉它会把「用户退出页面」误判成「检查失败」。

package com.gigi.tcg.ui.about

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gigi.tcg.BuildConfig
import com.gigi.tcg.GigiApp
import com.gigi.tcg.data.github.Announcement
import com.gigi.tcg.data.github.BugReporter
import com.gigi.tcg.data.github.UpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AboutViewModel(
    private val updateChecker: UpdateChecker,
    private val announcementStore: AnnouncementStore,
    private val bugReporter: BugReporter,
    private val appContext: Context,
) : ViewModel() {

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _pendingAnnouncements = MutableStateFlow<List<Announcement>>(emptyList())

    /** 当前版本可见且未读的公告，按发布顺序；已读的自动过滤 */
    val pendingAnnouncements: StateFlow<List<Announcement>> = _pendingAnnouncements.asStateFlow()

    private var loadingAnnouncements = false

    /** 检查更新。进行中重复点击直接 return：镜像回退链串起来最长约 1 分钟，不兜防抖会连打多轮请求 */
    fun checkUpdate() {
        if (_updateState.value is UpdateState.Checking) return
        _updateState.value = UpdateState.Checking
        viewModelScope.launch {
            _updateState.value = try {
                val result = updateChecker.check()
                if (result.hasUpdate) {
                    UpdateState.Available(result)
                } else {
                    UpdateState.UpToDate(result.source)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                Log.w(TAG, "更新检查失败", error)
                UpdateState.Failed(error.message)
            }
        }
    }

    /** 拉 announcement.json（数据层内部已按当前版本过滤），再滤掉已读；并发调用只发一次请求 */
    fun loadAnnouncements() {
        if (loadingAnnouncements) return
        loadingAnnouncements = true
        viewModelScope.launch {
            try {
                val visible = updateChecker.announcementsForCurrentVersion()
                _pendingAnnouncements.value =
                    pendingAnnouncements(visible, BuildConfig.VERSION_NAME, announcementStore.readIds())
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                // 公告是附加信息：拉不到就当没有，不打扰用户，也不占更新检查的状态位
                Log.w(TAG, "公告读取失败", error)
                _pendingAnnouncements.value = emptyList()
            } finally {
                loadingAnnouncements = false
            }
        }
    }

    /** 记已读并立刻从待展示队列移除（不这么做的弹窗会在同一页面停留时反复弹出） */
    fun markAnnouncementRead(id: String) {
        announcementStore.markRead(id)
        _pendingAnnouncements.update { list -> list.filterNot { it.id == id } }
    }

    /** 预填好设备信息的 GitHub Issue 链接，交给浏览器提交 */
    fun buildBugReportUrl(summary: String = DEFAULT_ISSUE_SUMMARY): String =
        bugReporter.buildIssueUrl(summary, appContext)

    /** 剪贴板版反馈正文：非 GitHub 渠道（B 站私信/群）也能带上完整上下文 */
    fun buildCopyableReport(): String = bugReporter.buildCopyableReport(appContext)

    companion object {
        const val TAG: String = "GIGI.AboutViewModel"
        const val DEFAULT_ISSUE_SUMMARY: String = "问题反馈"

        fun factory(app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.AndroidViewModelFactory(app) {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val container = (app as GigiApp).container
                    val context = app.applicationContext
                    return AboutViewModel(
                        updateChecker = container.updateChecker,
                        announcementStore = AnnouncementStore(context),
                        bugReporter = container.bugReporter,
                        appContext = context,
                    ) as T
                }
            }
    }
}
