package com.gigi.tcg.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** 逐字移植 web/src/utils/__tests__/summary.test.ts（7 用例） */
class SummaryTest {

    // 用一组可手算的已知数据核对汇总公式（对齐原 game.lua）
    private val cards = listOf(
        GcgCard(name = "角色A", cardType = "CardTypeCharacter", useCount = 9, proficiency = 6),
        GcgCard(name = "角色B", cardType = "CardTypeCharacter", useCount = 3, proficiency = 3),
        GcgCard(name = "角色C", cardType = "CardTypeCharacter", useCount = 3, proficiency = 0),
        GcgCard(name = "装备X", cardType = "CardTypeModify", useCount = 10),
        GcgCard(name = "支援Y", cardType = "CardTypeAssist", useCount = 6),
        GcgCard(name = "事件Z", cardType = "CardTypeEvent", useCount = 4),
        GcgCard(name = "事件W", cardType = "CardTypeEvent", useCount = 2),
    )

    // ---- prepareCardLists ----

    @Test
    fun `prepareCardLists 角色行动牌拆分并按 use_count 降序`() {
        val (charCards, actionCards, charTotalUse, charTotalProficiency) = prepareCardLists(cards)
        assertEquals(listOf("角色A", "角色B", "角色C"), charCards.map { it.name })
        assertEquals(listOf("装备X", "支援Y", "事件Z", "事件W"), actionCards.map { it.name })
        assertEquals(15, charTotalUse)
        assertEquals(9, charTotalProficiency)
    }

    @Test
    fun `prepareCardLists use_count 缺失按 0 参与排序与合计`() {
        val (_, _, charTotalUse, charTotalProficiency) = prepareCardLists(
            listOf(GcgCard(name = "x", cardType = "CardTypeCharacter")),
        )
        assertEquals(0, charTotalUse)
        assertEquals(0, charTotalProficiency)
    }

    // ---- computeGcgSummary（公式逐条对齐原代码）----

    private val lists = prepareCardLists(cards)
    /** 空卡池（模拟 cardList 不可用）：此时「cardList 份数口径」算不出，分母链才降级到图鉴/服务端/已得数 */
    private val emptyLists = prepareCardLists(emptyList())
    private val stats = GcgStats(
        nickname = "测试牌手",
        level = 9,
        avatarCardNumGained = 30,
        actionCardNumGained = 120,
    )
    private val s = computeGcgSummary(stats, lists)

    @Test
    fun `总对局数 floor(Σ角色use 3) = 5 获胜对局数 floor(Σ角色prof 3) = 3`() {
        assertEquals(5L, s.totalGames)
        assertEquals(3L, s.winGames)
    }

    @Test
    fun `总胜率 3 除以 5 = 60%（整值去点零）`() {
        assertEquals("60%", s.winRate)
    }

    @Test
    fun `行动牌合计与三类占比`() {
        assertEquals(22, s.actionTotalUse)
        assertEquals(10, s.modifyUse)
        assertEquals("45.5%", s.modifyPercent) // 10/22
        assertEquals(6, s.assistUse)
        assertEquals("27.3%", s.assistPercent) // 6/22
        assertEquals(6, s.eventUse)
        assertEquals("27.3%", s.eventPercent) // 6/22
    }

    @Test
    fun `stats 字段直接透传 缺失字段容错`() {
        val empty = computeGcgSummary(null, prepareCardLists(emptyList()))
        assertEquals("未知", empty.nickname)
        assertEquals(0, empty.level)
        assertEquals(0, empty.avatarCardNum)
        assertEquals(0, empty.actionCardNum)
        // stats 整体缺失 ⇒ 总数也归 0（胶囊显示 0/0，不会出现 x/0 之外的怪值）
        assertEquals(0, empty.avatarCardTotal)
        assertEquals(0, empty.actionCardTotal)
        assertEquals(0L, empty.totalGames)
        assertEquals(0L, empty.winGames)
        assertEquals("0%", empty.winRate)
        assertEquals(0, empty.actionTotalUse)
        assertEquals("0%", empty.modifyPercent)
    }

    // ---- 图鉴总数（导出图胶囊 `角色牌 143/147` 的分母）----

    @Test
    fun `cardList 不可用时图鉴真实总数兜底`() {
        // cardList 不可用（空卡池）⇒ 份数口径算不出，图鉴 147/941 兜底 ⇒ 胶囊必须是 30/147，不能显示"全收集"
        val s = computeGcgSummary(stats, emptyLists, WikiCardTotals(avatarTotal = 147, actionTotal = 941))
        assertEquals(147, s.avatarCardTotal)
        assertEquals(941, s.actionCardTotal)
        assertEquals(30, s.avatarCardNum)
        assertEquals(120, s.actionCardNum)
        // 优先级：图鉴 > 服务端 total（若哪天真返回）> 已得数
        val priority = computeGcgSummary(
            stats.copy(avatarCardNumTotal = 999, actionCardNumTotal = 999),
            emptyLists,
            WikiCardTotals(avatarTotal = 147, actionTotal = 941),
        )
        assertEquals(147, priority.avatarCardTotal)
        assertEquals(941, priority.actionCardTotal)
    }

    @Test
    fun `cardList 不可用且图鉴拿不到时回退已得数（分母不为0）`() {
        val s = computeGcgSummary(stats, emptyLists, WikiCardTotals())
        assertEquals(30, s.avatarCardTotal)
        assertEquals(120, s.actionCardTotal)
        val zeroed = computeGcgSummary(stats, emptyLists, WikiCardTotals(0, 0))
        assertEquals(30, zeroed.avatarCardTotal)
        assertEquals(120, zeroed.actionCardTotal)
    }

    @Test
    fun `cardList 不可用且总数缺失时兜底为已得数`() {
        // 老接口没有 *_num_total 字段 ⇒ null ⇒ 分母取已得数，绝不显示 30/0
        val s = computeGcgSummary(
            GcgStats(nickname = "n", level = 1, avatarCardNumGained = 30, actionCardNumGained = 120),
            emptyLists,
        )
        assertEquals(30, s.avatarCardTotal)
        assertEquals(120, s.actionCardTotal)
    }

    @Test
    fun `cardList 不可用且总数为0时同样兜底为已得数`() {
        val s = computeGcgSummary(
            stats.copy(avatarCardNumTotal = 0, actionCardNumTotal = 0),
            emptyLists,
        )
        assertEquals(30, s.avatarCardTotal)
        assertEquals(120, s.actionCardTotal)
    }

    @Test
    fun `cardList 不可用时服务端 total 作次级来源`() {
        // cardList 不可用（空卡池）⇒ 份数口径算不出；图鉴缺省时，服务端 total 字段仍是次级来源
        val s = computeGcgSummary(
            stats.copy(avatarCardNumTotal = 147, actionCardNumTotal = 941),
            emptyLists,
        )
        assertEquals(147, s.avatarCardTotal)
        assertEquals(941, s.actionCardTotal)
        // 已得数与总数各走各的口径，互不覆盖
        assertEquals(30, s.avatarCardNum)
        assertEquals(120, s.actionCardNum)
    }

    @Test
    fun `字段名以 action_card_num_gained 为准`() {
        assertEquals(120, s.actionCardNum)
    }

    // ---- basicInfo 官方总数（分母首选来源）：图鉴会去重手牌，口径不可靠 ----

    @Test
    fun `分母优先取 basicInfo 官方总数（图鉴去重数次之）`() {
        // 图鉴去重后只有 149/568（把未收集画成全收集的根源），basicInfo 给 147/941 ⇒ 必须用 147/941
        val s = computeGcgSummary(
            stats,
            lists,
            wikiTotals = WikiCardTotals(avatarTotal = 149, actionTotal = 568),
            officialTotals = WikiCardTotals(avatarTotal = 147, actionTotal = 941),
        )
        assertEquals(147, s.avatarCardTotal)
        assertEquals(941, s.actionCardTotal)
        // 分子仍走 cardList 的已得数，与分母各走各的口径
        assertEquals(30, s.avatarCardNum)
        assertEquals(120, s.actionCardNum)
    }

    @Test
    fun `basicInfo 与 cardList 都为空时回退图鉴数`() {
        val s = computeGcgSummary(
            stats,
            emptyLists,
            wikiTotals = WikiCardTotals(avatarTotal = 149, actionTotal = 568),
            officialTotals = WikiCardTotals(),
        )
        assertEquals(149, s.avatarCardTotal)
        assertEquals(568, s.actionCardTotal)
    }

    @Test
    fun `basicInfo 为空时用 cardList 份数口径（优先于图鉴去重数）`() {
        // lists 为完整卡池（3 角色 + 4 行动，行动牌无秘传/幻变 ⇒ 全限 2 份）
        // ⇒ avatar=3（角色每张限 1）、action=2×4=8，均优先于图鉴去重数 149/568
        val s = computeGcgSummary(
            stats,
            lists,
            wikiTotals = WikiCardTotals(avatarTotal = 149, actionTotal = 568),
            officialTotals = WikiCardTotals(),
        )
        assertEquals(3, s.avatarCardTotal)
        assertEquals(8, s.actionCardTotal)
    }

    @Test
    fun `basicInfo 只回一半字段时按级降级不互相污染`() {
        // avatar 有官方值 ⇒ 用官方；action 缺字段（0）⇒ 该级跳过，落到 cardList 份数口径
        val s = computeGcgSummary(
            stats,
            lists,
            wikiTotals = WikiCardTotals(avatarTotal = 149, actionTotal = 568),
            officialTotals = WikiCardTotals(avatarTotal = 147, actionTotal = 0),
        )
        assertEquals(147, s.avatarCardTotal)
        assertEquals(8, s.actionCardTotal) // cardList 份数口径（优先于图鉴 568）
    }

    @Test
    fun `basicInfo 与 cardList 与图鉴都缺失时回退服务端 total 再回退已得数`() {
        val withServerTotal = computeGcgSummary(
            stats.copy(avatarCardNumTotal = 150, actionCardNumTotal = 950),
            emptyLists,
            wikiTotals = WikiCardTotals(),
            officialTotals = WikiCardTotals(),
        )
        assertEquals(150, withServerTotal.avatarCardTotal)
        assertEquals(950, withServerTotal.actionCardTotal)

        val lastResort = computeGcgSummary(
            stats,
            emptyLists,
            wikiTotals = WikiCardTotals(),
            officialTotals = WikiCardTotals(),
        )
        assertEquals(30, lastResort.avatarCardTotal)
        assertEquals(120, lastResort.actionCardTotal)
    }

    // ---- cardList 份数口径（actionCardTotal）：与官方 basicInfo 一致（实测 941）----

    @Test
    fun `份数口径 空列表返回0让分母链继续降级`() {
        assertEquals(0, actionCardTotal(emptyList()))
    }

    @Test
    fun `份数口径 无秘传无幻变时等于2倍种类数`() {
        val pool = listOf(
            GcgCard(name = "装备X", cardType = "CardTypeModify", desc = "普通装备"),
            GcgCard(name = "支援Y", cardType = "CardTypeAssist", desc = "普通支援"),
            GcgCard(name = "事件Z", cardType = "CardTypeEvent", desc = "普通事件"),
        )
        assertEquals(6, actionCardTotal(pool))
    }

    @Test
    fun `份数口径 秘传牌按desc关键词限1份`() {
        val pool = listOf(
            GcgCard(name = "旧时庭园", cardType = "CardTypeEvent", desc = "…（整局游戏只能打出一张「秘传」卡牌；这张牌一定在你的起始手牌中）"),
            GcgCard(name = "普通事件", cardType = "CardTypeEvent", desc = "造成2点伤害"),
            GcgCard(name = "装备X", cardType = "CardTypeModify", desc = "普通装备"),
        )
        // 3 种类，1 张秘传限1 ⇒ 2×3−1 = 5
        assertEquals(5, actionCardTotal(pool))
    }

    @Test
    fun `份数口径 元素幻变牌按牌名前缀限1份`() {
        val pool = listOf(
            GcgCard(name = "元素幻变：超导祝佑", cardType = "CardTypeAssist", desc = "元素幻变：冰元素雷元素…"),
            GcgCard(name = "普通支援", cardType = "CardTypeAssist", desc = "普通支援"),
        )
        // 2 种类，1 张幻变限1 ⇒ 2×2−1 = 3
        assertEquals(3, actionCardTotal(pool))
    }

    @Test
    fun `份数口径 缺desc字段不误判秘传（null安全）`() {
        val pool = listOf(
            GcgCard(name = "装备X", cardType = "CardTypeModify"), // desc = null
            GcgCard(name = "支援Y", cardType = "CardTypeAssist"),
        )
        assertEquals(4, actionCardTotal(pool))
    }

    @Test
    fun `份数口径 真实卡池结构（14秘传+9幻变+459限2=941）`() {
        // 模拟官方卡池：459 张限2 + 14 张秘传 + 9 张元素幻变 = 482 种类 ⇒ 2×482−23 = 941
        val pool = buildList {
            repeat(459) { add(GcgCard(name = "普通牌$it", cardType = "CardTypeEvent", desc = "普通")) }
            repeat(14) { add(GcgCard(name = "秘传牌$it", cardType = "CardTypeEvent", desc = "…一张「秘传」卡牌…")) }
            repeat(9) { add(GcgCard(name = "元素幻变：$it", cardType = "CardTypeAssist", desc = "元素幻变：…")) }
        }
        assertEquals(941, actionCardTotal(pool))
    }
}
