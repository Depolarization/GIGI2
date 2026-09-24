// 卡面预览状态机：移植 web/src/components/CardCoverDialog.tsx。
// 数据层 DetailCacheStore(LRU200) 缓存的是 EntryPageData，无法据此判断"命中"，
// 而"命中不显示 loading"（web 反馈 §4）要求缓存标记，故本层另存一份解析结果缓存
// （对应 web 模块级 detailCache）。图片自身加载态交 Coil AsyncImage。

package com.gigi.tcg.ui.dialogs.cardcover

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gigi.tcg.GigiApp
import com.gigi.tcg.data.api.describeApiError
import com.gigi.tcg.data.model.CardBasicInfo
import com.gigi.tcg.data.model.EntryPageData
import com.gigi.tcg.di.AppContainer
import com.gigi.tcg.domain.Throttle
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException

/** 卡面格式：普通(PNG)=common_img、动态(GIF)=gold_img */
enum class CoverFormat(val label: String, val extension: String, val mimeType: String) {
    Png("普通卡面(PNG)", "png", "image/png"),
    Gif("动态卡面(GIF)", "gif", "image/gif"),
}

sealed interface CoverUiState {
    data object Loading : CoverUiState

    data class Content(
        val info: CardBasicInfo?,
        val format: CoverFormat = CoverFormat.Png,
    ) : CoverUiState {
        /** 当前格式无图（服务端字段缺失）时为 null：预览位显示空态、下载按钮禁用 */
        val imageUrl: String?
            get() = when (format) {
                CoverFormat.Png -> info?.commonImg
                CoverFormat.Gif -> info?.goldImg
            }
    }

    data class Error(val message: String) : CoverUiState
}

class CardCoverViewModel(app: Application) : AndroidViewModel(app) {

    private val container: AppContainer = (app as GigiApp).container
    private val saver = CardImageSaver(app)
    private val downloadThrottle = Throttle(DOWNLOAD_THROTTLE_MS)

    private val _uiState = MutableStateFlow<CoverUiState>(CoverUiState.Loading)
    val uiState: StateFlow<CoverUiState> = _uiState.asStateFlow()

    private var contentId: Int? = null
    private var generation = 0
    private var loadJob: Job? = null
    private var downloadJob: Job? = null

    /** 解析结果缓存（对应 web 模块级 detailCache）：命中即出内容、不进 Loading */
    private val parsedCache = object : LinkedHashMap<Int, CardBasicInfo>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, CardBasicInfo>?) =
            size > DETAIL_CACHE_MAX
    }

    /** Sheet 开合：null=关闭（复位状态，下次打开重新走缓存/加载判定） */
    fun setContentId(id: Int?) {
        if (contentId == id) return
        contentId = id
        generation++
        loadJob?.cancel()
        if (id == null) {
            downloadJob?.cancel()
            _uiState.value = CoverUiState.Loading
            return
        }
        load(id)
    }

    /** ErrorState 重试：缓存命中则直接出内容，否则重新请求 */
    fun retry() {
        contentId?.let { load(it) }
    }

    fun selectFormat(format: CoverFormat) {
        val current = _uiState.value
        if (current is CoverUiState.Content && current.format != format) {
            _uiState.value = current.copy(format = format)
        }
    }

    /** 预览与下载相互独立：只取当前格式的 URL，经 1 秒节流后写相册 */
    fun download(show: (String) -> Unit) {
        val state = _uiState.value as? CoverUiState.Content ?: return
        val info = state.info ?: return
        val url = state.imageUrl ?: return
        if (!downloadThrottle()) return
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            try {
                saver.save(url, info.name ?: FALLBACK_NAME, state.format)
                show(SAVED_TOAST)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                show(e.message ?: SAVE_FAILED_TOAST)
            }
        }
    }

    /**
     * 取详情：解析结果命中缓存即直接出内容（不进 Loading，对齐 web 反馈 §4）；
     * 未命中走 repository（LRU200 + 1s 详情节流在其内层）。每次构建 Content 都复位 PNG。
     */
    private fun load(id: Int) {
        val gen = generation
        parsedCache[id]?.let { cached ->
            _uiState.value = CoverUiState.Content(cached)
            return
        }
        _uiState.value = CoverUiState.Loading
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val info = parseBasicInfo(container.repository.fetchCardDetail(id))
                if (gen != generation) return@launch
                if (info == null) {
                    _uiState.value = CoverUiState.Error(PARSE_FAILED)
                } else {
                    parsedCache[id] = info
                    _uiState.value = CoverUiState.Content(info)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) {
                    // retcode/网络归因集中在数据层，本层只转文案（设计红线 2）
                    _uiState.value = CoverUiState.Error(describeApiError(e))
                }
            }
        }
    }

    /** entry_page.page.modules["基础信息"].components[0].data 二次解析（JSON 字符串） */
    private fun parseBasicInfo(data: EntryPageData): CardBasicInfo? {
        for (module in data.page?.modules.orEmpty()) {
            if (module.name != BASIC_INFO_MODULE) continue
            val raw = module.components?.firstOrNull()?.data ?: continue
            return try {
                container.json.decodeFromString(CardBasicInfo.serializer(), raw)
            } catch (e: SerializationException) {
                return null
            }
        }
        return null
    }

    companion object {
        /** 对齐 CardCoverDialog DETAIL_CACHE_MAX */
        private const val DETAIL_CACHE_MAX = 200
        private const val BASIC_INFO_MODULE = "基础信息"
        private const val PARSE_FAILED = "获取数据失败"
        private const val SAVED_TOAST = "已保存到相册"
        private const val SAVE_FAILED_TOAST = "保存失败，请重试"
        private const val FALLBACK_NAME = "卡面"

        /** 对齐 web createThrottle(1000) */
        private const val DOWNLOAD_THROTTLE_MS: Long = 1000L

        fun factory(app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.AndroidViewModelFactory(app) {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CardCoverViewModel(app) as T
            }
    }
}
