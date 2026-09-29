// 卡牌图鉴（卡面下载）状态机：移植 web/src/pages/CardWikiPage.tsx。
// 三分类频道（233 角色牌 / 234 行动牌 / 235 魔物牌）+ 多维筛选（AND）+ 关键词搜索 + 网格。
// 筛选语义逐字对齐 Web：选中维度子项拼成 "label/sub" 键，卡牌 filterArray 须包含全部选中键；
// 关键词按去后缀标题不区分大小写包含匹配。列表数据经 repository（内存→磁盘 1h TTL 缓存）。
// V37-5：装载入口不再无条件打 Loading —— 已有分类就静默替换（见 shouldShowBlockingLoading），
// 否则内存/磁盘缓存命中也先闪一帧菊花（V37-5 用户实测：切页必闪 progressbar + 正在加载）。

package com.gigi.tcg.ui.screens.cardwiki

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gigi.tcg.i18n.apiErrorText
import com.gigi.tcg.data.repo.WikiListData
import com.gigi.tcg.di.AppContainer
import androidx.annotation.StringRes
import com.gigi.tcg.R
import com.gigi.tcg.domain.WikiFilterDef
import com.gigi.tcg.domain.parseCardFilters
import com.gigi.tcg.domain.parseFilterDefs
import com.gigi.tcg.domain.stripTitleSuffix
import com.gigi.tcg.i18n.LocaleStrings
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 图鉴频道 id 与展示名资源/归属标签键（对齐原 cards.lua 与 CardWikiPage） */
internal const val CATEGORY_HERO_ID: Int = 233
private val CATEGORY_IDS: List<Int> = listOf(233, 234, 235)
private val CATEGORY_TITLE_RES: Map<Int, Int> =
    mapOf(233 to R.string.card_type_character, 234 to R.string.card_type_action, 235 to R.string.card_type_monster)
private val EXT_KEYS: Map<Int, String> =
    mapOf(233 to "c_233", 234 to "c_234", 235 to "c_235")

/** 单卡展示模型：contentId 上抛给卡面下载弹层（用 Long 对齐 coverId） */
data class CardVm(
    val contentId: Long?,
    val title: String,
    val icon: String,
    val filterArray: List<String>,
)

/** 一个频道分类：筛选维度定义 + 卡牌列表；titleRes 由 UI 侧 stringResource 解析 */
data class CategoryVm(
    val id: Int,
    @StringRes val titleRes: Int,
    val filterDefs: List<WikiFilterDef>,
    val cards: List<CardVm>,
)

data class WikiUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val categories: List<CategoryVm> = emptyList(),
    val activeCatId: Int = CATEGORY_HERO_ID,
    val keyword: String = "",
    /** 每个筛选维度选中子项：缺省/空串表示"全部" */
    val selections: Map<String, String> = emptyMap(),
    /** 待打开卡面的 contentId（本 VM 只持有，由 UI 回调上抛后置空） */
    val coverId: Long? = null,
) {
    val activeCategory: CategoryVm? get() = categories.find { it.id == activeCatId }

    /**
     * 有没有「既有的可展示内容」——[shouldShowBlockingLoading] 的输入。
     * 三个频道只要解析出任一分类就算有内容：整页结构（tab + 搜索框 + 网格）已经能画，
     * 这时候再打 Loading 就是把已经看着的东西擦掉重画（切页闪菊花的来源）。
     * 分类里筛不出牌（关键词/筛选不命中）走的是 EmptyState，不是 Loading。
     */
    val hasContent: Boolean get() = categories.isNotEmpty()

    /** 当前分类经"各维度选中项 AND + 关键词"过滤后的卡牌（对齐 Web filteredCards useMemo） */
    val filteredCards: List<CardVm>
        get() {
            val category = activeCategory ?: return emptyList()
            val selectedKeys = category.filterDefs.mapNotNull { def ->
                val label = def.label ?: return@mapNotNull null
                val sel = selections[label] ?: ""
                if (sel.isNotEmpty()) "$label/$sel" else null
            }
            val kw = keyword.lowercase()
            return category.cards.filter { card ->
                selectedKeys.all { card.filterArray.contains(it) } &&
                    (kw.isEmpty() || card.title.lowercase().contains(kw))
            }
        }
}

/**
 * 阻塞式 Loading 判据（纯函数，无 Compose/Android 依赖，JVM 单测直接钉死）：
 * 口径与 V36-5 定稿、HomeViewModel.loadProfile 落地的「有内容即静默替换」一致 ——
 * 状态里已经有分类可画，本次装载（含 force=true 的手动刷新）只静默替换，不把 loading 打回 true。
 * 缓存命中时协程几乎立刻返回，但入口那一次 `loading = true` 已足够让 UI 先画一帧菊花，
 * 这就是用户报的「每次切页都闪 progressbar」（V37-5 实测根因）。
 *
 * 判据读的是 **StateFlow 里的状态**，不是 `LaunchedEffect(key)` 的 key：组合离开会被 cancel、
 * 重入会重启（手册 §8.6 红线 6），每次 load() 都重新求值 ⇒ 天然免疫重入漏判。
 *
 * 与统计页 CardStatsViewModel 的同名判据少一个 isError 入参：
 * 本页两处错误分支都把 categories 清成空 ⇒ 「错误页」必然「无内容」，hasContent 单条判据已经够。
 */
internal fun shouldShowBlockingLoading(hasContent: Boolean): Boolean = !hasContent

class CardWikiViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(WikiUiState())
    val uiState: StateFlow<WikiUiState> = _uiState.asStateFlow()

    private var generation = 0

    init {
        load(force = false)
    }

    /** force=true 绕过双层缓存（重试 / 手动刷新入口）；每次自增 generation 丢弃过期回调 */
    fun load(force: Boolean) {
        val gen = ++generation
        // 有内容即静默替换（判据见 shouldShowBlockingLoading）；error 的清理时机保持现状：入口清、落定按结果写。
        _uiState.update { it.copy(loading = shouldShowBlockingLoading(it.hasContent), error = null) }
        viewModelScope.launch {
            try {
                val data = container.repository.fetchCardWikiListCached(force)
                if (gen != generation) return@launch
                val categories = parseCategories(data)
                if (categories.isEmpty()) {
                    _uiState.update {
                        it.copy(loading = false, error = LocaleStrings.get(R.string.error_format), categories = emptyList())
                    }
                } else {
                    _uiState.update {
                        it.copy(loading = false, error = null, categories = categories)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // retcode/网络归因集中在数据层，本层经 apiErrorText 转文案（设计红线 2）
                if (gen == generation) {
                    _uiState.update {
                        it.copy(loading = false, error = apiErrorText(e), categories = emptyList())
                    }
                }
            }
        }
    }

    fun selectCategory(id: Int) {
        // 切频道重置关键词与筛选（对齐 Web tab onClick）
        _uiState.update { it.copy(activeCatId = id, keyword = "", selections = emptyMap()) }
    }

    fun onKeywordChange(text: String) {
        _uiState.update { it.copy(keyword = text) }
    }

    fun onSelectionChange(label: String, value: String) {
        _uiState.update { it.copy(selections = it.selections + (label to value)) }
    }

    fun openCover(contentId: Long) {
        _uiState.update { it.copy(coverId = contentId) }
    }

    fun closeCover() {
        _uiState.update { it.copy(coverId = null) }
    }

    private fun parseCategories(data: WikiListData): List<CategoryVm> {
        val root = data.list?.firstOrNull() ?: return emptyList()
        val children = root.children ?: return emptyList()
        return CATEGORY_IDS.mapNotNull { id ->
            val child = children.find { it.id == id } ?: return@mapNotNull null
            val extKey = EXT_KEYS.getValue(id)
            val cards = child.list.orEmpty().map { entry ->
                CardVm(
                    contentId = entry.contentId?.toLong(),
                    title = stripTitleSuffix(entry.title ?: LocaleStrings.get(R.string.common_unknown)),
                    icon = entry.icon ?: "",
                    filterArray = parseCardFilters(entry.ext, extKey),
                )
            }
            CategoryVm(
                id = id,
                titleRes = CATEGORY_TITLE_RES.getValue(id),
                filterDefs = parseFilterDefs(child.chExt),
                cards = cards,
            )
        }
    }

    companion object {

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CardWikiViewModel(container) as T
            }
    }
}
