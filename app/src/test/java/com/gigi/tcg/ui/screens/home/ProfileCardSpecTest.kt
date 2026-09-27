// 个人信息卡规格的段位文本口径（V27）：tierDisplayText 必须与 ui/components/tierLabel
// （StateViews，组合上下文版）逐字同口径——段位名走当前语言资源、★ 星缀同 domain
// formatTier（0 星不显示）、无段位画 home_tier_none。纯 JVM 无 resolver 时
// 断中文默认值（🔴 与 values/strings.xml 同 id 文案逐字一致）；注入 resolver 验证三语通道。

package com.gigi.tcg.ui.screens.home

import com.gigi.tcg.R
import com.gigi.tcg.data.model.PageInfo
import com.gigi.tcg.domain.TierStars
import com.gigi.tcg.i18n.LocaleStrings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileCardSpecTest {

    @After
    fun tearDown() {
        LocaleStrings.installResolverForTest(null)
    }

    private fun tierTextOf(score: Int): String =
        buildProfileCardSpec(PageInfo(ladderScore = score), "1", 0).tierText

    @Test
    fun `段位文本等于 tierLabel getTierStars 口径（多处分数抽查）`() {
        assertEquals("无段位", tierTextOf(0))          // getTierStars(score<1)→无段位
        assertEquals("黄铜★", tierTextOf(1))           // 下边界
        assertEquals("黄铜★", tierTextOf(1199))        // <1200 仍是黄铜一星
        assertEquals("黄铜★★", tierTextOf(1200))       // 1200 落入下一档（<1400 黄铜二星）
        assertEquals("黄铜★★★★★", tierTextOf(1999))    // <2000 黄铜五星
        assertEquals("星银★", tierTextOf(2050))        // <2100 星银一星
        assertEquals("星银★★", tierTextOf(2100))
        assertEquals("赤金★", tierTextOf(2550))        // <2600 赤金一星
        assertEquals("赤金★★", tierTextOf(2600))
        assertEquals("赤金★★★", tierTextOf(2760))
        assertEquals("影幻", tierTextOf(3000))         // ≥3000，0 星不带 ★
        assertEquals("影幻", tierTextOf(9999))
    }

    @Test
    fun `段位名按当前语言资源映射（resolver 注入即三语通道生效）`() {
        LocaleStrings.installResolverForTest { id ->
            when (id) {
                R.string.tier_silver -> "Silver"
                R.string.home_tier_none -> "Unranked"
                else -> null
            }
        }
        assertEquals("Silver★", tierTextOf(2050))
        assertEquals("Unranked", tierTextOf(0))
    }

    @Test
    fun `未知段位名回落原始值本身（不显示成空白）`() {
        // tierDisplayText 的映射表只认四档；domain 若新增段位，旧客户端也要能读出来
        assertEquals("钻石★★", tierDisplayText(TierStars("钻石", 2)))
        assertEquals("钻石", tierDisplayText(TierStars("钻石", 0)))
    }
}
