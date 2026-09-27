// 图鉴总数（导出图胶囊分母）纯 JVM 单测：不碰 android.graphics / Context。
// 覆盖实测树形态（无嵌套）、假想的子频道嵌套、频道缺失/list 缺失、魔物牌不计入，
// 以及真实 JSON 字段名（channel id / list / content_id）能解出来。
package com.gigi.tcg.domain

import com.gigi.tcg.data.model.GcgBasicInfoData
import com.gigi.tcg.data.model.WikiCardEntry
import com.gigi.tcg.data.model.WikiChannelNode
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class WikiCardTotalsTest {

    private fun entries(count: Int) = List(count) { WikiCardEntry(contentId = it, title = "卡$it") }

    /** 真实接口形态：data.list = [231 卡牌图鉴]，三个分类频道挂在 children 上且自身无 children */
    private fun flatTree(charCount: Int, actionCount: Int, monsterCount: Int = 0) = listOf(
        WikiChannelNode(
            id = 231,
            name = "卡牌图鉴",
            children = listOf(
                WikiChannelNode(id = 233, name = "角色牌", list = entries(charCount)),
                WikiChannelNode(id = 234, name = "行动牌", list = entries(actionCount)),
                WikiChannelNode(id = 235, name = "魔物牌", list = entries(monsterCount)),
            ),
        ),
    )

    @Test
    fun `无嵌套时按频道 list 条目数计数（147 与 941 口径）`() {
        val totals = wikiCardTotals(flatTree(charCount = 147, actionCount = 941, monsterCount = 61))
        assertEquals(147, totals.avatarTotal)
        assertEquals(941, totals.actionTotal)
    }

    @Test
    fun `条目挂在子频道里也要递归累加`() {
        val tree = listOf(
            WikiChannelNode(
                id = 231,
                children = listOf(
                    WikiChannelNode(
                        id = 233,
                        list = entries(100),
                        children = listOf(
                            WikiChannelNode(id = 233, list = entries(40)),
                            // 混在其中的其它分类不计入
                            WikiChannelNode(id = 234, list = entries(941)),
                        ),
                    ),
                ),
            ),
        )
        val totals = wikiCardTotals(tree)
        assertEquals(140, totals.avatarTotal)
        assertEquals(941, totals.actionTotal)
    }

    @Test
    fun `真实 JSON 形态可解（字段名 content_id、list、children）`() {
        val raw = """
            [{"id":231,"name":"卡牌图鉴","children":[
              {"id":233,"name":"角色牌","list":[{"content_id":1,"title":"行秋【角色牌】"},{"content_id":2,"title":"香菱【角色牌】"}]},
              {"id":234,"name":"行动牌","list":[{"content_id":3,"title":"顺风"}]}
            ]}]
        """.trimIndent()
        val json = Json { ignoreUnknownKeys = true }
        val tree = json.decodeFromString(ListSerializer(WikiChannelNode.serializer()), raw)
        val totals = wikiCardTotals(tree)
        assertEquals(2, totals.avatarTotal)
        assertEquals(1, totals.actionTotal)
    }

    @Test
    fun `频道缺失 list 缺失 入参 null 一律返回 0 且不抛异常`() {
        assertEquals(0, wikiCardTotals(null).avatarTotal)
        assertEquals(0, wikiCardTotals(emptyList()).actionTotal)
        // 只有魔物牌频道 ⇒ 角色/行动都为 0（由调用方兜底已得数）
        val onlyMonster = wikiCardTotals(listOf(WikiChannelNode(id = 235, list = entries(61))))
        assertEquals(0, onlyMonster.avatarTotal)
        assertEquals(0, onlyMonster.actionTotal)
        // 频道存在但 list 字段缺失
        val noList = wikiCardTotals(listOf(WikiChannelNode(id = 233, children = listOf(WikiChannelNode(id = 234)))))
        assertEquals(0, noList.avatarTotal)
        assertEquals(0, noList.actionTotal)
    }

    @Test
    fun `id 缺失的节点不计入任何分类`() {
        val tree = listOf(WikiChannelNode(id = null, list = entries(5)))
        assertEquals(0, wikiCardTotals(tree).avatarTotal)
    }

    // ---- basicInfo 官方总数（分母首选来源）----

    @Test
    fun `basicInfo 真实字段名可解出官方总数`() {
        // 实测响应体（2026-09，server=cn_gf01 / role_id=<游戏内 9 位 UID>）
        val raw = """{"nickname":"测试牌手","level":57,
            "avatar_card_num_gained":143,"avatar_card_num_total":147,
            "action_card_num_gained":500,"action_card_num_total":941}"""
        val json = Json { ignoreUnknownKeys = true }
        val data = json.decodeFromString(GcgBasicInfoData.serializer(), raw)
        val totals = officialCardTotals(data)
        assertEquals(147, totals.avatarTotal)
        assertEquals(941, totals.actionTotal)
    }

    @Test
    fun `basicInfo 缺失 total 字段 入参 null 非正值一律折算 0`() {
        assertEquals(0, officialCardTotals(null).avatarTotal)
        assertEquals(0, officialCardTotals(null).actionTotal)
        // 服务端只回了 gained（老版本响应形态）⇒ total 缺字段 ⇒ 两级都 0，让调用方降级到图鉴口径
        val onlyGained = GcgBasicInfoData(avatarCardNumGained = 143, actionCardNumGained = 500)
        assertEquals(0, officialCardTotals(onlyGained).avatarTotal)
        assertEquals(0, officialCardTotals(onlyGained).actionTotal)
        // 非正值不采信（0 分母比没有分母更糟）
        val zeroed = officialCardTotals(GcgBasicInfoData(avatarCardNumTotal = 0, actionCardNumTotal = -1))
        assertEquals(0, zeroed.avatarTotal)
        assertEquals(0, zeroed.actionTotal)
    }
}
