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
    fun `分母优先取图鉴真实总数`() {
        // 已得 30/120，图鉴 147/941 ⇒ 胶囊必须是 30/147，不能因为服务端字段缺失就显示"全收集"
        val s = computeGcgSummary(stats, lists, WikiCardTotals(avatarTotal = 147, actionTotal = 941))
        assertEquals(147, s.avatarCardTotal)
        assertEquals(941, s.actionCardTotal)
        assertEquals(30, s.avatarCardNum)
        assertEquals(120, s.actionCardNum)
        // 三级优先级：图鉴 > 服务端 total（若哪天真返回）> 已得数
        val priority = computeGcgSummary(
            stats.copy(avatarCardNumTotal = 999, actionCardNumTotal = 999),
            lists,
            WikiCardTotals(avatarTotal = 147, actionTotal = 941),
        )
        assertEquals(147, priority.avatarCardTotal)
        assertEquals(941, priority.actionCardTotal)
    }

    @Test
    fun `图鉴总数拿不到时回退已得数（分母不为0）`() {
        val s = computeGcgSummary(stats, lists, WikiCardTotals())
        assertEquals(30, s.avatarCardTotal)
        assertEquals(120, s.actionCardTotal)
        val zeroed = computeGcgSummary(stats, lists, WikiCardTotals(0, 0))
        assertEquals(30, zeroed.avatarCardTotal)
        assertEquals(120, zeroed.actionCardTotal)
    }

    @Test
    fun `总数缺失时兜底为已得数`() {
        // 老接口没有 *_num_total 字段 ⇒ null ⇒ 分母取已得数，绝不显示 30/0
        val s = computeGcgSummary(
            GcgStats(nickname = "n", level = 1, avatarCardNumGained = 30, actionCardNumGained = 120),
            lists,
        )
        assertEquals(30, s.avatarCardTotal)
        assertEquals(120, s.actionCardTotal)
    }

    @Test
    fun `总数为0时同样兜底为已得数`() {
        val s = computeGcgSummary(
            stats.copy(avatarCardNumTotal = 0, actionCardNumTotal = 0),
            lists,
        )
        assertEquals(30, s.avatarCardTotal)
        assertEquals(120, s.actionCardTotal)
    }

    @Test
    fun `总数正常时直接采用服务端值`() {
        // 图鉴总数缺省（未传参）时，服务端 total 字段仍是次级来源
        val s = computeGcgSummary(
            stats.copy(avatarCardNumTotal = 147, actionCardNumTotal = 941),
            lists,
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
    fun `basicInfo 为空时回退图鉴数`() {
        val s = computeGcgSummary(
            stats,
            lists,
            wikiTotals = WikiCardTotals(avatarTotal = 149, actionTotal = 568),
            officialTotals = WikiCardTotals(),
        )
        assertEquals(149, s.avatarCardTotal)
        assertEquals(568, s.actionCardTotal)
    }

    @Test
    fun `basicInfo 只回一半字段时按级降级不互相污染`() {
        // avatar 有官方值 ⇒ 用官方；action 缺字段（0）⇒ 该级跳过，落到图鉴那一级
        val s = computeGcgSummary(
            stats,
            lists,
            wikiTotals = WikiCardTotals(avatarTotal = 149, actionTotal = 568),
            officialTotals = WikiCardTotals(avatarTotal = 147, actionTotal = 0),
        )
        assertEquals(147, s.avatarCardTotal)
        assertEquals(568, s.actionCardTotal)
    }

    @Test
    fun `basicInfo 与图鉴都缺失时回退服务端 total 再回退已得数`() {
        val withServerTotal = computeGcgSummary(
            stats.copy(avatarCardNumTotal = 150, actionCardNumTotal = 950),
            lists,
            wikiTotals = WikiCardTotals(),
            officialTotals = WikiCardTotals(),
        )
        assertEquals(150, withServerTotal.avatarCardTotal)
        assertEquals(950, withServerTotal.actionCardTotal)

        val lastResort = computeGcgSummary(
            stats,
            lists,
            wikiTotals = WikiCardTotals(),
            officialTotals = WikiCardTotals(),
        )
        assertEquals(30, lastResort.avatarCardTotal)
        assertEquals(120, lastResort.actionCardTotal)
    }
}
