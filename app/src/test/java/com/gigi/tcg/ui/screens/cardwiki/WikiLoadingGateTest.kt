// V37-C（V37-5 缓存 Loading 语义）图鉴页回归锁，纯 JVM：
// 统计页同源的缺陷 —— load() 入口无条件 `loading = true`，内存 1h / 磁盘缓存命中也先画一帧
// LoadingView（CardWikiRoute 的阻塞分支只看 state.loading，闪得更明显）。
// 判据口径来自 V36-5 定稿：有缓存内容时不置 loading=true，直接静默替换。
package com.gigi.tcg.ui.screens.cardwiki

import com.gigi.tcg.R
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiLoadingGateTest {

    private fun categoryVm(id: Int, cardCount: Int) = CategoryVm(
        id = id,
        titleRes = R.string.card_type_character,
        filterDefs = emptyList(),
        cards = (1..cardCount).map { CardVm(contentId = it.toLong(), title = "卡$it", icon = "", filterArray = emptyList()) },
    )

    /** 装载成功、三分类都在的正常态 */
    private val loaded = WikiUiState(
        loading = false,
        categories = listOf(categoryVm(CATEGORY_HERO_ID, 3), categoryVm(234, 2), categoryVm(235, 1)),
    )

    @Test
    fun `首屏无内容必须进阻塞 Loading`() {
        val first = WikiUiState()
        assertFalse("初值没有分类", first.hasContent)
        assertTrue("首屏必须转圈", shouldShowBlockingLoading(first.hasContent))
    }

    @Test
    fun `页面重入已有分类必须静默替换（本棒核心回归锁）`() {
        assertTrue(loaded.hasContent)
        assertFalse(
            "重入装载不得把 loading 打回 true —— 图鉴页切页闪菊花的来源",
            shouldShowBlockingLoading(loaded.hasContent),
        )
    }

    @Test
    fun `错误态装载必须进阻塞 Loading`() {
        // 本页错误分支把 categories 清成空 ⇒ 「错误页」必然「无内容」，
        // 判据无需 isError 入参也自然回到阻塞 Loading（与统计页的差异写在 VM 的 KDoc）。
        val failed = loaded.copy(loading = false, error = "请检查网络重试", categories = emptyList())
        assertFalse(failed.hasContent)
        assertTrue(shouldShowBlockingLoading(failed.hasContent))
    }

    @Test
    fun `分类在但筛不出牌仍算有内容`() {
        // 筛选/关键词不命中是 EmptyState 的事，跟装载态无关：此时整页结构可画，不该再打 Loading。
        val filteredOut = loaded.copy(
            categories = listOf(categoryVm(CATEGORY_HERO_ID, 0)),
            keyword = "不存在的关键字",
        )
        assertTrue("分类存在即有内容（与筛出多少张无关）", filteredOut.hasContent)
        assertFalse(shouldShowBlockingLoading(filteredOut.hasContent))
        assertTrue("当前分类经筛选后确实没牌（走 EmptyState，不是 Loading）", filteredOut.filteredCards.isEmpty())
    }

    // ---- 源文件级不变量（旧写法不得复活） ----

    private val vmSrc: String by lazy {
        val file = File("src/main/java/com/gigi/tcg/ui/screens/cardwiki/CardWikiViewModel.kt")
        assertTrue("源码文件不存在: ${file.absolutePath}", file.exists())
        file.readText()
    }

    private val code: String by lazy {
        vmSrc.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
    }

    @Test
    fun loadEntryMustNotUnconditionallySetLoading() {
        assertFalse(
            "load() 入口的无条件 loading = true 必须消失",
            code.contains("copy(loading = true"),
        )
        assertTrue("入口判据必须走纯函数", code.contains("shouldShowBlockingLoading("))
        // 代数防竞态与过期回调丢弃不得为「少改点」删掉
        assertTrue("每次装载必须自增代数", code.contains("val gen = ++generation"))
        assertTrue("过期回包必须丢弃", code.contains("if (gen != generation) return@launch"))
        // error 的清理时机保持现状：入口照旧清 error，只是 loading 改成按判据写
        assertTrue(
            "入口必须是判据 + 清 error 的一次 update",
            code.contains("it.copy(loading = shouldShowBlockingLoading(it.hasContent), error = null)"),
        )
    }
}
