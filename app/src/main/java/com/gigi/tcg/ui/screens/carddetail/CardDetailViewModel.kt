// 卡牌详情页状态机（V3 全屏页）：由 CardCoverViewModel 迁移而来。
// 与旧版的差异（报告 §5/§7/§8.5）：
// ① 没有 format / selectFormat —— 格式选择收进下载对话框，页面不再有格式态；
// ② 解析结果缓存上移到 AppContainer.cardDetailCache（页面 VM 随回退栈销毁）；
// ③ 下载按「资源」而不是「当前格式」：卡面 / 技能图标共用一条保存链路。
// 默认 Tab 不在这里：页签由 HorizontalPager 的 initialPage = 0（卡面）承担，
// 每次新进页面都是新的 NavBackStackEntry ⇒ 新的 VM ⇒ 天然复位。

package com.gigi.tcg.ui.screens.carddetail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gigi.tcg.GigiApp
import com.gigi.tcg.R
import com.gigi.tcg.di.AppContainer
import com.gigi.tcg.domain.CardDetail
import com.gigi.tcg.domain.CardDetailParser
import com.gigi.tcg.domain.Throttle
import com.gigi.tcg.i18n.LocaleStrings
import com.gigi.tcg.i18n.apiErrorText
import com.gigi.tcg.i18n.apiLangParam
import com.gigi.tcg.ui.dialogs.cardcover.CardImageSaver
import com.gigi.tcg.ui.dialogs.cardcover.EXPORT_DIR_SKILL_ICON
import com.gigi.tcg.ui.dialogs.cardcover.exportDirName
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface CardDetailUiState {
    data object Loading : CardDetailUiState

    data class Content(val detail: CardDetail) : CardDetailUiState

    data class Error(val message: String) : CardDetailUiState
}

class CardDetailViewModel(
    app: Application,
    private val contentId: Long,
) : AndroidViewModel(app) {

    private val container: AppContainer = (app as GigiApp).container
    private val saver = CardImageSaver(app)
    private val downloadThrottle = Throttle(DOWNLOAD_THROTTLE_MS)

    private val _uiState = MutableStateFlow<CardDetailUiState>(CardDetailUiState.Loading)
    val uiState: StateFlow<CardDetailUiState> = _uiState.asStateFlow()

    /** 保存中：对话框 positive 置灰，避免重复点击（报告 §7.2） */
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private var generation = 0
    private var loadJob: Job? = null
    private var downloadJob: Job? = null

    init {
        load()
    }

    /** ErrorState 重试：缓存优先，未命中重新请求 */
    fun retry() {
        load()
    }

    /**
     * 保存当前选中的资源。对话框保持打开直到成功（失败让用户看到错误并重试）。
     * 名称规则与旧版一致：卡面用卡名；技能图标用技能名（图标 URL 反查技能）。
     */
    fun download(option: DownloadOption, show: (String) -> Unit, onSaved: () -> Unit = {}) {
        val detail = (_uiState.value as? CardDetailUiState.Content)?.detail ?: return
        if (_saving.value || !downloadThrottle()) return
        _saving.value = true
        downloadJob = viewModelScope.launch {
            try {
                val name = when (option.dir) {
                    DownloadDir.AlbumRoot -> detail.info.name
                    DownloadDir.SkillIcon -> detail.skills
                        .firstOrNull { it.iconUrl == option.url }
                        ?.name
                }?.takeIf { it.isNotBlank() } ?: LocaleStrings.get(R.string.cover_title_fallback)
                saver.save(
                    url = option.url,
                    name = name,
                    extension = option.extension,
                    mimeType = option.mimeType,
                    subDir = when (option.dir) {
                        DownloadDir.AlbumRoot -> null
                        DownloadDir.SkillIcon -> exportDirName(EXPORT_DIR_SKILL_ICON)
                    },
                    accountUid = when (option.dir) {
                        DownloadDir.AlbumRoot -> null
                        DownloadDir.SkillIcon -> container.activeAccountUid.value
                    },
                )
                show(LocaleStrings.get(R.string.toast_saved_to_album))
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                show(e.message ?: LocaleStrings.get(R.string.toast_save_failed))
            } finally {
                _saving.value = false
            }
        }
    }

    /**
     * 取详情：LRU（容器级，key 含 lang）命中即出内容，不进 Loading；
     * 未命中走 repository（其内层仍有 1s 详情节流）。
     */
    private fun load() {
        val gen = ++generation
        val cacheKey = "${LocaleStrings.currentLanguage().apiLangParam()}:$contentId"
        container.cardDetailCache.get(cacheKey)?.let { cached ->
            _uiState.value = CardDetailUiState.Content(cached)
            return
        }
        _uiState.value = CardDetailUiState.Loading
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val detail = CardDetailParser.parse(
                    container.json,
                    container.repository.fetchCardDetail(contentId.toInt()),
                )
                if (gen != generation) return@launch
                if (detail == null) {
                    _uiState.value = CardDetailUiState.Error(
                        LocaleStrings.get(R.string.error_cover_parse_failed),
                    )
                } else {
                    container.cardDetailCache.put(cacheKey, detail)
                    _uiState.value = CardDetailUiState.Content(detail)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) {
                    // retcode/网络归因集中在数据层，本层经 apiErrorText 转文案（设计红线 2）
                    _uiState.value = CardDetailUiState.Error(apiErrorText(e))
                }
            }
        }
    }

    companion object {
        /** 对齐 web createThrottle(1000) */
        private const val DOWNLOAD_THROTTLE_MS: Long = 1000L

        fun factory(app: Application, contentId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.AndroidViewModelFactory(app) {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CardDetailViewModel(app, contentId) as T
            }
    }
}
