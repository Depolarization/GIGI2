// 图鉴页签「页序号 ↔ 分类」映射回归锁（纯 JVM）：
// HorizontalPager 的三页与 TabRow 的三页签必须同序（角色牌/行动牌/魔物牌），
// 且每页的筛选状态按页序号对应的规范频道 id 记忆（见 CardWikiRoute.WikiPage）——
// 顺序错乱会让"角色牌的搜索词"落到"行动牌"页上（正是三页状态隔离要防的串台）。
// 越界取安全值：pager 在数据未就绪/接口缺频道时可能给出列表外的页号，
// 映射必须夹到边界页而不是抛 IndexOutOfBounds。
package com.gigi.tcg.ui.screens.cardwiki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiTabsTest {

    /** 页数固定三分类，与枚举项数一致（新增分类要同步改枚举与文案，别只改一边） */
    @Test
    fun pageCountMatchesEnum() {
        assertEquals(WIKI_PAGE_COUNT, WikiCategory.entries.size)
    }

    /** 页序 = 枚举声明序 = 展示序：角色牌 → 行动牌 → 魔物牌 */
    @Test
    fun pageOrderMapsToCategoriesOneToOne() {
        val byPage = (0 until WIKI_PAGE_COUNT).map { wikiCategoryOf(it) }
        assertEquals(
            listOf(WikiCategory.Character, WikiCategory.Action, WikiCategory.Monster),
            byPage,
        )
        // 一一对应：页号与分类互不重复
        assertEquals(WIKI_PAGE_COUNT, byPage.distinct().size)
    }

    /**
     * 频道 id 与原接口口径一致（233 角色牌 / 234 行动牌 / 235 魔物牌），
     * 首项直接引用 VM 的 CATEGORY_HERO_ID 做编译期绑定，防止两处魔数各跑偏。
     */
    @Test
    fun channelsMatchWikiContract() {
        assertEquals(CATEGORY_HERO_ID, WikiCategory.Character.channel)
        assertEquals(listOf(233, 234, 235), WikiCategory.entries.map { it.channel })
        assertEquals(WIKI_PAGE_COUNT, WikiCategory.entries.map { it.channel }.distinct().size)
    }

    /** 越界取安全值：小越界夹到首分类（与 VM activeCatId 默认值同口径），大越界夹到末分类 */
    @Test
    fun outOfRangePageFallsBackSafely() {
        assertEquals(WikiCategory.Character, wikiCategoryOf(-1))
        assertEquals(WikiCategory.Character, wikiCategoryOf(Int.MIN_VALUE))
        assertEquals(WikiCategory.Monster, wikiCategoryOf(WIKI_PAGE_COUNT))
        assertEquals(WikiCategory.Monster, wikiCategoryOf(Int.MAX_VALUE))
        // 安全值本身必须是合法页：再映射一次不自越界
        val fallback = wikiCategoryOf(99)
        assertTrue((0 until WIKI_PAGE_COUNT).any { wikiCategoryOf(it) == fallback })
    }
}
