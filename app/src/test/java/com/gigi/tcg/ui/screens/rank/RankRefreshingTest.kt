// 缺陷 A 防回归：排行榜下拉刷新指示器的清除判定必须认"本次刷新实际发起的 tab"
// （refreshingTab），而非落定时刻的 activeTab——否则刷新途中横滑切 Tab 会让
// _refreshing 永久卡 true，指示器不停且 retry 被入口守卫短路，下拉刷新彻底失效。
// RankViewModel 本体构造依赖 AppContainer/Context，纯 JVM 不可直构（见 U3A2-WIRE.md §2），
// 故只测抽出的纯函数 shouldClearRefreshing（风格对齐 GateSeedAndRetryTest）。

package com.gigi.tcg.ui.screens.rank

import com.gigi.tcg.data.repo.RankTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RankRefreshingTest {

    @Test
    fun `refreshed tab settling clears the flag`() {
        assertEquals(true, shouldClearRefreshing(RankTab.Peak, RankTab.Peak))
        assertEquals(true, shouldClearRefreshing(RankTab.Competition, RankTab.Competition))
    }

    @Test
    fun `other tab settling must not clear the flag`() {
        // 刷 Peak 期间 Competition 落定（全局 refresh 等场景）→ 不得替 Peak 清除
        assertEquals(false, shouldClearRefreshing(RankTab.Competition, RankTab.Peak))
        assertEquals(false, shouldClearRefreshing(RankTab.Peak, RankTab.Competition))
    }

    @Test
    fun `nothing to clear when not refreshing`() {
        assertEquals(false, shouldClearRefreshing(RankTab.Peak, null))
        assertEquals(false, shouldClearRefreshing(RankTab.Competition, null))
    }

    /** 回归锁：刷 Peak 途中横滑到 Competition（activeTab 已变），Peak 落定仍须清除。 */
    @Test
    fun `regression lock - cleared by refreshed tab even after swiping away`() {
        val activeTabAfterSwipe = RankTab.Competition
        val refreshingTab: RankTab? = RankTab.Peak
        val settledTab = RankTab.Peak
        // 判定与 activeTab 无关：若按旧逻辑（settledTab == activeTab）此处会得到 false → 永久卡死
        assertNotEquals(settledTab, activeTabAfterSwipe)
        assertEquals(true, shouldClearRefreshing(settledTab, refreshingTab))
    }
}
