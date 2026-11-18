package com.gigi.tcg.domain

import com.gigi.tcg.data.model.EntryPage
import com.gigi.tcg.data.model.EntryPageComponent
import com.gigi.tcg.data.model.EntryPageData
import com.gigi.tcg.data.model.EntryPageModule
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡牌详情解析（JSON-first）。样本取自官方 entry_page：
 * 法尔伽 510291（角色牌，3 技能）与重光临晨 510307（行动牌，rich_text）。
 */
class CardDetailParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val characterBasic = """
        {"name":"北风骑士·法尔伽","common_img":"c.png","gold_img":"g.gif","life":12,"energy":3,
         "attr":[{"key":"元素：","value":["风元素"]},{"key":"武器：","value":["双手剑"]},
                 {"key":"阵营：","value":["蒙德"]},{"key":"获取方式：","value":["角色邀请·友好对局"]}]}
    """.trimIndent()

    private val characterSkills = """
        {"list":[
          {"tab_name":"西风剑术·流光之舞","skill_type":"普通攻击","icon":"i1.png","life":1,"energy":2,
           "desc":{"key":"技能效果","value":["<p>造成2点<strong><u>物理伤害</u></strong>。</p>"]}},
          {"tab_name":"烈风终坠","skill_type":"元素战技","icon":"i2.png","life":3,"energy":0,
           "desc":{"key":"技能效果","value":["<p>造成1点<strong>风元素伤害</strong>。</p>"]}},
          {"tab_name":"我即朔风","skill_type":"元素爆发","icon":"i3.png","life":3,"energy":3,
           "desc":{"key":"技能效果","value":["<p>造成3点<strong>风元素伤害</strong>。</p>"]}}
        ]}
    """.trimIndent()

    private val actionBasic = """
        {"name":"重光临晨","common_img":"a.png","gold_img":"a.gif","life":3,"energy":0,
         "attr":[{"key":"类型：","value":["装备牌"]},{"key":"标签：","value":["天赋，魔导"]},
                 {"key":"获取：","value":["卡牌商店购买获取"]},{"key":"花费：","value":["3"]}]}
    """.trimIndent()

    private val actionEffect = """
        {"rich_text":"<p><strong><u>快速行动</u></strong><span data-type=\"详情\" data-name=\"&lt;p&gt;&lt;strong&gt;快速行动：&lt;/strong&gt;&lt;/p&gt;&lt;p&gt;执行了一次快速行动后，我方可以继续进行其他行动。&lt;/p&gt;\" class=\"wiki-note-text\"><sup class=\"wiki-sup\">[详情]</sup></span>：装备给我方的法尔伽。</p>"}
    """.trimIndent()

    private fun page(vararg modules: EntryPageModule) = EntryPageData(EntryPage(modules.toList()))

    private fun module(name: String?, data: String) =
        EntryPageModule(name = name, components = listOf(EntryPageComponent(data = data)))

    private fun characterPage() = page(
        module("基础信息", characterBasic),
        module("卡牌技能", characterSkills),
    )

    private fun actionPage() = page(
        module("基础信息", actionBasic),
        module("效果描述", actionEffect),
    )

    @Test
    fun `角色牌解析技能列表与费用`() {
        val detail = CardDetailParser.parse(json, characterPage())
        assertNotNull(detail)
        detail!!

        assertEquals("北风骑士·法尔伽", detail.info.name)
        assertEquals(CardElement.Wind, detail.element)
        assertFalse(detail.isAction)
        assertEquals(3, detail.skills.size)

        val normal = detail.skills[0]
        assertEquals("西风剑术·流光之舞", normal.name)
        assertEquals("普通攻击", normal.type)
        assertEquals(
            listOf(SkillCost(CostKind.Element, 1), SkillCost(CostKind.Untyped, 2)),
            normal.costs,
        )
        assertEquals("技能效果", normal.descLabel)
        assertEquals("造成2点物理伤害。", normal.description)

        assertEquals(listOf(SkillCost(CostKind.Element, 3)), detail.skills[1].costs)
        assertEquals(
            listOf(SkillCost(CostKind.Element, 3), SkillCost(CostKind.Energy, 3)),
            detail.skills[2].costs,
        )
    }

    @Test
    fun `行动牌解析 rich_text 与关键词注释`() {
        val detail = CardDetailParser.parse(json, actionPage())
        assertNotNull(detail)
        detail!!

        assertTrue(detail.isAction)
        assertTrue(detail.skills.isEmpty())
        assertNotNull(detail.effect)
        assertEquals("效果描述", detail.effect!!.label)
        assertEquals("快速行动：装备给我方的法尔伽。", detail.effect!!.description)
        assertEquals(1, detail.effect!!.notes.size)
        assertEquals("快速行动", detail.effect!!.notes[0].title)
        assertEquals("执行了一次快速行动后，我方可以继续进行其他行动。", detail.effect!!.notes[0].body)
    }

    @Test
    fun `重复卡牌技能模块只取第一个`() {
        val variant = """
            {"list":[{"tab_name":"变体技能","skill_type":"元素战技","life":2,
             "desc":{"key":"技能效果","value":["<p>变体</p>"]}}]}
        """.trimIndent()
        val detail = CardDetailParser.parse(
            json,
            page(
                module("基础信息", characterBasic),
                module("卡牌技能", characterSkills),
                module("卡牌技能", variant),
            ),
        )

        assertEquals(3, detail!!.skills.size)
        assertFalse(detail.skills.any { it.name == "变体技能" })
    }

    @Test
    fun `缺技能与效果模块返回空列表而不是空详情`() {
        val detail = CardDetailParser.parse(json, page(module("基础信息", characterBasic)))

        assertNotNull(detail)
        assertTrue(detail!!.skills.isEmpty())
        assertNull(detail.effect)
        assertFalse(detail.isAction)
    }

    @Test
    fun `同名同类型技能去重`() {
        val duplicated = """
            {"list":[
              {"tab_name":"烈风终坠","skill_type":"元素战技","life":3,
               "desc":{"key":"技能效果","value":["<p>第一次</p>"]}},
              {"tab_name":"烈风终坠","skill_type":"元素战技","life":3,
               "desc":{"key":"技能效果","value":["<p>第二次</p>"]}}
            ]}
        """.trimIndent()
        val detail = CardDetailParser.parse(
            json,
            page(module("基础信息", characterBasic), module("卡牌技能", duplicated)),
        )

        assertEquals(1, detail!!.skills.size)
        assertEquals("第一次", detail.skills[0].description)
    }

    @Test
    fun `基础信息缺失或损坏返回 null`() {
        assertNull(CardDetailParser.parse(json, page(module("卡牌技能", characterSkills))))
        assertNull(CardDetailParser.parse(json, page(module("基础信息", "{不是 JSON"))))
    }

    @Test
    fun `无元素属性时元素为 Unknown`() {
        val plain = """{"name":"无属性","attr":[]}"""
        val detail = CardDetailParser.parse(json, page(module("基础信息", plain)))

        assertEquals(CardElement.Unknown, detail!!.element)
    }

    @Test
    fun `元素属性按名称映射到八元素与物理`() {
        assertEquals(CardElement.Wind, CardElement.fromName("风元素"))
        assertEquals(CardElement.Water, CardElement.fromName("水元素"))
        assertEquals(CardElement.Fire, CardElement.fromName("火元素"))
        assertEquals(CardElement.Thunder, CardElement.fromName("雷元素"))
        assertEquals(CardElement.Ice, CardElement.fromName("冰元素"))
        assertEquals(CardElement.Rock, CardElement.fromName("岩元素"))
        assertEquals(CardElement.Grass, CardElement.fromName("草元素"))
        assertEquals(CardElement.Physical, CardElement.fromName("物理"))
        assertEquals(CardElement.Unknown, CardElement.fromName(null))
    }
}
