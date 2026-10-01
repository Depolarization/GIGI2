// 卡组导出图的纯逻辑回归锁（V37-F 任务 E，V38-B 按官方参考图重做版式；纯 JVM、无 Robolectric）。
// 锁三件事：① 参考图（20260929181737.png，面板 900 → 画布 750，系数 5/6）推出的牌尺/6 列网格/页脚算术；
// ② 空牌组 / 超长牌组名 / 40 张牌三类边界不崩、不溢出画布；③ 文件名清洗与反馈/默认文案通道。
// 🔴 V38-B 新增：顶部 banner（米游社 logo + 七圣召唤）移除的源码闸门、角色牌恒 3 张钳制、采样配色。
// 假 measurer 与 TableLayoutTest 同一口径：CJK=1.0×字号、拉丁≈0.55×字号（真机字宽只小不大 ⇒ 保守上界）。
package com.gigi.tcg.ui.export

import com.gigi.tcg.R
import com.gigi.tcg.data.model.GcgDeck
import com.gigi.tcg.data.model.GcgDeckCard
import com.gigi.tcg.i18n.LocaleStrings
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckImageExportTest {

    private val fakeMeasurer = TextMeasurer { text, textSizePx ->
        text.sumOf { c -> if (c.code >= 0x2E80) textSizePx.toDouble() else textSizePx * 0.55 }.toFloat()
    }

    private fun spec(
        roleCount: Int = 3,
        actionCount: Int = 24,
        title: String = "胡桃行秋速攻",
        authorText: String = "分享人：Oscuro",
        pills: List<String> = listOf("胡桃", "行秋"),
        descText: String = "角色牌 3 · 行动牌 24 · 2026-09-29",
    ) = DeckImageSpec(
        title = title,
        authorText = authorText,
        pills = pills,
        descText = descText,
        roleLabel = "角色牌",
        actionLabel = "行动牌",
        brandText = "七圣召唤",
        roleCards = List(roleCount) { DeckCardFace("角色$it", "https://cdn/role$it.jpg") },
        actionCards = List(actionCount) { DeckCardFace("行动$it", "https://cdn/action$it.jpg") },
    )

    // ---- ① 参考图换算（V38-B：面板 900 → 画布 750，系数 5/6） ----

    @Test
    fun canvasAndPartSizes_matchOfficialSpec() {
        assertEquals("画布宽 = 375 设计宽 × DPR 2（派单硬指标，V38 不改）", 750, DECK_CANVAS_WIDTH_PX)
        assertEquals("body 左右 padding 15 → 30px", 30, DECK_BODY_PADDING_SIDE_PX)
        assertEquals("牌组区 padding 11 → 22px", 22, DECK_GROUP_PADDING_SIDE_PX)
        assertEquals("角色牌 144×240@900 → 120×200@750", 120, DECK_ROLE_CARD_WIDTH_PX)
        assertEquals("角色牌更竖长（1:1.667）", 200, DECK_ROLE_CARD_HEIGHT_PX)
        assertEquals("行动牌 6 列铺满内容宽：6×96+5×14 = 646", 96, DECK_ACTION_CARD_WIDTH_PX)
        assertEquals("行动牌高按参考图 96:160", 160, DECK_ACTION_CARD_HEIGHT_PX)
        assertEquals("行列间距单一常量 14", 14, DECK_CARD_GRID_GAP_PX)
        assertEquals("行动牌恒 6 列", 6, DECK_ACTION_GRID_COLUMNS)
        assertEquals("角色牌恒 3 张", 3, DECK_ROLE_CARD_COUNT)
        assertEquals("152 宽按卡面 160:275 立框（接口图口径不变）", 261, deckCardHeightPx(152))
        assertEquals("108 宽按卡面 160:275 立框", 186, deckCardHeightPx(108))
    }

    @Test
    fun layout_textAndCardLanes_useBodyThenGroupPadding() {
        val layout = computeDeckImageLayout(spec(), fakeMeasurer)
        assertEquals(30, layout.textLeftPx)
        assertEquals(690, layout.textContentWidthPx)
        assertEquals("牌面区左缘 = body 15 + 牌组区 11", 52, layout.cardsLeftPx)
        assertEquals(646, layout.cardsContentWidthPx)
        assertEquals("顶部 banner 移除：标题流不再压在 180px header 之下", 30, layout.titleTopPx)
    }

    @Test
    fun layout_realDeck_rolesThreeColumnsActionsSix() {
        val layout = computeDeckImageLayout(spec(roleCount = 3, actionCount = 24), fakeMeasurer)
        assertEquals(3, layout.roleSlots.size)
        assertEquals("角色牌一行三列居中（3×120+2×14 ≤ 750）", 3, layout.roleSlots.map { it.xPx }.distinct().size)
        assertEquals(1, layout.roleSlots.map { it.yPx }.distinct().size)
        assertEquals(24, layout.actionSlots.size)
        assertEquals("行动牌一行六列（6×96+5×14 = 646 恰好铺满内容宽）", 6, layout.actionSlots.take(6).map { it.xPx }.distinct().size)
        assertEquals("24 张 → 4 行", 4, layout.actionSlots.map { it.yPx }.distinct().size)
    }

    // ---- ② 边界：空牌组 / 超多牌 / 超长名 ----

    @Test
    fun layout_emptyDeck_noSlotsNoDivisionByZero() {
        val layout = computeDeckImageLayout(spec(roleCount = 0, actionCount = 0, pills = emptyList()), fakeMeasurer)
        assertTrue("空牌组也要有合法高度", layout.heightPx > 0)
        assertEquals(emptyList<DeckCardSlot>(), layout.roleSlots)
        assertEquals(emptyList<DeckCardSlot>(), layout.actionSlots)
        assertNull("空组整块不画：连「角色牌」小标题都不占位", layout.roleLabelTopPx)
        assertNull(layout.actionLabelTopPx)
        assertTrue("所有槽位仍在画布内", layout.heightPx < 10_000)
    }

    @Test
    fun layout_fortyCards_growsVerticallyButNeverOverflowsWidth() {
        val layout = computeDeckImageLayout(spec(actionCount = 40), fakeMeasurer)
        assertEquals("40 张 6 列 → 7 行", 7, layout.actionSlots.map { it.yPx }.distinct().size)
        val rightEdge = layout.actionSlots.maxOf { it.xPx + it.widthPx }
        assertTrue("最右牌面不得越过右内边距：$rightEdge > 750-52", rightEdge <= 750 - 52)
        assertTrue("末行底不得越过画布高：${layout.actionSlots.last().yPx}", layout.actionSlots.last().yPx + layout.actionCardHeightPx <= layout.heightPx)
        val small = computeDeckImageLayout(spec(actionCount = 5), fakeMeasurer)
        assertTrue("纵向只增长不分页：40 张比 5 张高", layout.heightPx > small.heightPx)
    }

    /**
     * V38 用户规则纠正：七圣召唤必须 **3 角色牌 + 30 行动牌** 才能出战。
     * ⇒ 画布高度按合法卡组的 5 行（6×5=30）铺满，玩家没填满时**末行留空占位**，
     * 不能压缩网格 —— 压缩会把「这牌组不合法」掩盖掉。
     */
    @Test
    fun layout_incompleteDeck_reservesFullFiveRowsWithTrailingPlaceholders() {
        val full = computeDeckImageLayout(spec(actionCount = 30), fakeMeasurer)
        assertEquals("合法卡组：6 列 × 5 行 = 30 格", 30, full.actionSlots.size)
        assertEquals("行数恒 5", 5, full.actionSlots.map { it.yPx }.distinct().size)

        // 不满 30（玩家没填满）⇒ 格子数照实，但高度必须仍按 5 行留占位
        val partial = computeDeckImageLayout(spec(actionCount = 22), fakeMeasurer)
        assertEquals("22 张只画 22 格（不补假牌）", 22, partial.actionSlots.size)
        assertEquals(
            "高度仍按合法 5 行留占位（与 30 张同高）",
            full.heightPx,
            partial.heightPx,
        )
        // 占位确实存在：22 格的底缘以上还有整行空间
        val lastCardBottom = partial.actionSlots.maxOf { it.yPx + it.heightPx }
        assertTrue(
            "末行留了空位：最后一张牌底缘($lastCardBottom) 距画布底(${partial.heightPx}) 还差一整行",
            partial.heightPx - lastCardBottom > partial.actionCardHeightPx,
        )
    }

    /**
     * 官方参考图是 **6 列 × 5 行 = 30 格**，重复的牌**各占一格**（成对并排），**不画张数徽标**。
     * ⇒ 导出图必须按 `num` 展开成独立格，而不是按种类数画。
     */
    @Test
    fun actionCards_expandByNumSoDuplicatesGetTheirOwnSlot() {
        val cards = listOf(
            GcgDeckCard(name = "普通的牌", image = "https://cdn/a.jpg", num = 1),
            GcgDeckCard(name = "两张的牌", image = "https://cdn/b.jpg", num = 2),
            GcgDeckCard(name = "没写张数", image = "https://cdn/c.jpg", num = null),
            GcgDeckCard(name = "写了 0", image = "https://cdn/d.jpg", num = 0),
        )
        val expanded = expandActionCardsByCount(cards)
        assertEquals("1+2+1+1（num 缺失/0 保守按 1）= 5 格", 5, expanded.size)
        assertEquals("第 2、3 格是同一张牌（'两张的牌' 展开两份）", "两张的牌", expanded[1].name)
        assertEquals("第 3 格仍是它", "两张的牌", expanded[2].name)
        assertEquals("第 4 格是缺 num 的那张", "没写张数", expanded[3].name)
        assertEquals("num=0 保守画一格（宁可多画同图，也不少画导致总数对不上）", "写了 0", expanded[4].name)
    }

    /** 实测：11 副牌组的 `sum(num)` 恒为 30，数组长度（种类数）只有 22~25 ⇒ 展开后必须正好 30 格 */
    @Test
    fun actionGridRows_thirtyCardsIsFiveRows() {
        assertEquals("30 张 = 5 行", 5, actionGridRows(DECK_ACTION_FULL_DECK_COUNT))
        assertEquals("22 张向上取整 4 行", 4, actionGridRows(22))
        assertEquals("0 张至少 1 行（保底，防除零）", 1, actionGridRows(0))
        assertEquals("合法卡组张数常量 = 30", 30, DECK_ACTION_FULL_DECK_COUNT)
    }

    @Test
    fun layout_veryLongDeckName_doesNotChangeHeightArithmetic() {
        val longName = "很长的牌组名字".repeat(30)
        val layout = computeDeckImageLayout(spec(title = longName), fakeMeasurer)
        val normal = computeDeckImageLayout(spec(), fakeMeasurer)
        assertEquals("标题恒单行（超长由绘制层 ellipsize），版式高不受牌组名长度影响", normal.heightPx, layout.heightPx)
    }

    @Test
    fun layout_blankAuthorBlock_collapsesWithItsGap() {
        val withAuthor = computeDeckImageLayout(spec(authorText = "分享人：Oscuro"), fakeMeasurer)
        val without = computeDeckImageLayout(spec(authorText = ""), fakeMeasurer)
        assertNull(without.authorTopPx)
        assertTrue("空行连间距一起不占", without.descTopPx < withAuthor.descTopPx)
        assertTrue("空行不产生负间距", without.descTopPx > without.titleTopPx)
    }

    @Test
    fun layout_pillsWrapInsideContentLaneAndNeverOverflow() {
        val layout = computeDeckImageLayout(
            spec(pills = List(8) { "很长的标签名称$it" }),
            fakeMeasurer,
        )
        assertTrue("放不下就该折到第二行", layout.pills.map { it.yPx }.distinct().size >= 2)
        layout.pills.forEach {
            assertTrue("胶囊右缘 ${it.xPx + it.widthPx} 越界", it.xPx + it.widthPx <= layout.textLeftPx + layout.textContentWidthPx)
        }
    }

    @Test
    fun layout_singleOverwidePill_ellipsizedToLaneWidth() {
        val layout = computeDeckImageLayout(spec(pills = listOf("超".repeat(200))), fakeMeasurer)
        val pill = layout.pills.single()
        assertEquals("单枚胶囊最宽只到内容宽", layout.textContentWidthPx, pill.widthPx)
        assertTrue("超出部分省略而不是撑破：${pill.text}", pill.text.endsWith("…"))
    }

    // ---- ③ 内容组装 / 文件名 / 反馈 ----

    @Test
    fun spec_authorPrefersNicknameThenUidThenBlank() {
        val deck = GcgDeck(
            id = 3,
            name = "速攻",
            avatarCards = listOf(GcgDeckCard(name = "胡桃", image = "https://cdn/1.jpg", num = 1)),
            actionCards = listOf(GcgDeckCard(name = "重振", image = "", num = 1), GcgDeckCard(name = null, image = null, num = 1)),
        )
        val byNick = buildDeckImageSpec(deck, deckTitle = "速攻", nickname = "Oscuro", uid = "261958214", dateText = "2026-09-29")
        assertEquals("分享人：Oscuro", byNick.authorText)
        val byUid = buildDeckImageSpec(deck, deckTitle = "速攻", nickname = "   ", uid = "261958214", dateText = "2026-09-29")
        assertEquals("昵称空白要落到 UID，而不是画空行", "分享人：261958214", byUid.authorText)
        val none = buildDeckImageSpec(deck, deckTitle = "速攻", nickname = null, uid = "", dateText = "2026-09-29")
        assertEquals("", none.authorText)
    }

    @Test
    fun spec_mapsFacesAndLabelsAndDesc() {
        val deck = GcgDeck(
            id = 3,
            name = "速攻",
            avatarCards = listOf(GcgDeckCard(name = "胡桃", image = "https://cdn/1.jpg"), GcgDeckCard(name = "  ", image = "https://cdn/2.jpg")),
            actionCards = List(3) { GcgDeckCard(name = "行动$it", image = "https://cdn/a$it.jpg") },
        )
        val built = buildDeckImageSpec(deck, deckTitle = "我的牌组", nickname = "Oscuro", uid = null, dateText = "2026-09-29")
        assertEquals("标题用调用方算好的展示名（接口名可能是空串）", "我的牌组", built.title)
        assertEquals(2, built.roleCards.size)
        assertEquals("图 URL 原样交给渲染层（空串在取图处判 null）", "https://cdn/2.jpg", built.roleCards[1].imageUrl)
        assertEquals("标签条只取非空白角色牌名", listOf("胡桃"), built.pills)
        assertTrue(built.descText.contains("2026-09-29"))
        assertTrue(built.descText.contains("行动牌"))
    }

    @Test
    fun baseName_sanitizesIllegalFileNameChars() {
        val name = deckExportBaseName("我的卡组", "a/b\\c:d*e?f\"g<h>i|j", "2026-09-29")
        listOf('/', '\\', ':', '*', '?', '"', '<', '>', '|').forEach {
            assertFalse("文件名不能含 $it ⇒ $name", name.contains(it))
        }
        assertTrue(name.endsWith("_2026-09-29"))
    }

    @Test
    fun baseName_blankOrCntrlOnlyName_fallsBackToLabel() {
        val allIllegal = deckExportBaseName("我的卡组", "///", "2026-09-29")
        assertTrue(allIllegal.startsWith("我的卡组"))
        assertTrue(allIllegal.endsWith("_2026-09-29"))
        assertTrue(allIllegal.length <= 60)
    }

    @Test
    fun baseName_keepsDateWithinSixtyCharBudget() {
        val name = deckExportBaseName("我的卡组", "牌".repeat(200), "2026-09-29")
        assertTrue("总长必须留在清洗预算内：$name", name.length <= 60)
        assertTrue("日期不能因截断丢失：$name", name.endsWith("_2026-09-29"))
    }

    @Test
    fun feedback_textChannels() {
        assertEquals("已保存卡组图片到相册", deckExportFeedback(succeeded = true, error = null))
        assertEquals("导出失败：没有存储权限", deckExportFeedback(succeeded = false, error = "没有存储权限"))
        assertEquals("导出失败", deckExportFeedback(succeeded = false, error = "   "))
    }

    @Test
    fun feedback_resolvesThroughLocaleResolverWhenAttached() {
        val templates = mapOf(
            R.string.deck_export_saved to "saved",
            R.string.stats_export_failed_detail to "bad:%1\$s",
            R.string.error_export_failed to "bad",
        )
        LocaleStrings.installResolverForTest { templates[it] }
        try {
            assertEquals("saved", deckExportFeedback(true, null))
            assertEquals("bad:boom", deckExportFeedback(false, "boom"))
            assertEquals("bad", deckExportFeedback(false, null))
        } finally {
            LocaleStrings.installResolverForTest(null)
        }
    }

    // ---- ④ 结构闸门 ----

    @Test
    fun pureLayer_mustNotReferenceAndroidGraphics() {
        val src = File("src/main/java/com/gigi/tcg/ui/export/DeckImageExport.kt")
        assertTrue("源码文件不存在: ${src.absolutePath}", src.exists())
        src.readText().lines().forEach { line ->
            assertFalse("纯逻辑层不得引用 android.graphics（约定见 CardStatsExport.kt:2）：$line", line.startsWith("import android.graphics"))
        }
    }

    @Test
    fun deckDetailPage_noLongerDrawsItsOwnTitleRow() {
        val src = File("src/main/java/com/gigi/tcg/ui/screens/my/MyDecksPage.kt").readText()
        val code = src.lines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }.joinToString("\n")
        assertFalse("双标题栏回归：页内不得再画返回箭头", code.contains("ArrowBack"))
        assertFalse("双标题栏回归：详情不再是页内状态（应已升为独立路由）", code.contains("selectedDeck"))
        assertFalse("占满全宽的按钮已改 icon", code.contains("OutlinedButton"))
        assertTrue("复制/导出改 trailing icon", code.contains("Icons.Outlined.ContentCopy") && code.contains("Icons.Outlined.IosShare"))
        assertTrue("icon 必须带 contentDescription", code.contains("contentDescription = copyLabel") && code.contains("contentDescription = exportLabel"))
        // V38-B 裁决（.task/progress/V38-B-ADJUDICATION.md）：V37「card.num 恒 1」只对角色牌成立，
        // 行动牌实测 70/70 例有 2 张（ctrl-deckList.json）⇒ 张数徽标已恢复，闸门倒转为正面断言。
        assertTrue("行动牌张数徽标已恢复（V38 用户指令：只移除角色牌张数）", code.contains("card.num"))
    }

    @Test
    fun newStrings_existInAllThreeLocales() {
        val ids = listOf("my_export_deck_image", "deck_export_saved", "export_deck_author", "export_dir_deck")
        val locales = listOf("values", "values-en", "values-zh-rTW")
        locales.forEach { dir ->
            val file = File("src/main/res/$dir/strings.xml")
            assertTrue("资源文件不存在: ${file.absolutePath}", file.exists())
            val text = file.readText()
            ids.forEach { id -> assertTrue("$dir 缺 $id", text.contains("name=\"$id\"")) }
        }
    }

    @Test
    fun pureLayerDefaults_matchSimplifiedChineseResources() {
        // 🔴 工程约定：getOrDefault 的中文默认值必须与 values/strings.xml 同 id 逐字一致，
        // 否则纯 JVM 路径与真机中文路径会各画一套文案（CardStatsExport.kt 同一口径）。
        val text = File("src/main/res/values/strings.xml").readText()
        mapOf(
            "export_deck_author" to "分享人：%1\$s",
            "deck_export_saved" to "已保存卡组图片到相册",
            "export_dir_deck" to "卡组",
            "my_deck_card_summary" to "角色牌 %1\$d · 行动牌 %2\$d",
        ).forEach { (id, expected) ->
            val actual = Regex("""<string name="$id">(.*?)</string>""").find(text)?.groupValues?.get(1)
            assertEquals("$id 默认值与资源不一致", expected, actual)
        }
    }

    // ---- ⑤ V38-B：顶部 banner 移除 + 参考图版式 ----

    @Test
    fun renderer_topBannerRemoved_mihoyoLogoAndZhiShengTextGone() {
        // 🔴 用户原话「无需米游社logo和其下方的七圣召唤文本（移除顶部banner）」——本条是最高优先闸门。
        // 只看代码行：注释里允许出现「七圣召唤」等字样（KDoc 要写清删了什么）。
        val code = File("src/main/java/com/gigi/tcg/ui/export/DeckImageRenderer.kt").readText()
            .lines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }.joinToString("\n")
        assertFalse("渲染层不得再引用米游社 logo 素材", code.contains("export_logo"))
        assertFalse("渲染层不得再画七圣召唤品牌文本", code.contains("七圣召唤"))
        assertFalse("渲染层不得残留 header 绘制", code.contains("drawHeader"))
        assertFalse("渲染层不得 import 米游社相关", code.lowercase().contains("mihoyo"))
        val layout = computeDeckImageLayout(spec(), fakeMeasurer)
        assertTrue("顶部 banner（180px header）已移除：标题流起点回到 body 顶 padding", layout.titleTopPx <= 30)
    }

    @Test
    fun characterCardCount_alwaysClampedToThree() {
        assertEquals(3, characterCardCount(3))
        assertEquals("异常输入（4 张/几十张）钳到 3，多出的牌不进图", 3, characterCardCount(4))
        assertEquals(3, characterCardCount(Int.MAX_VALUE))
        assertEquals(2, characterCardCount(2))
        assertEquals(0, characterCardCount(0))
        assertEquals("负数不炸版式", 0, characterCardCount(-5))
    }

    @Test
    fun layout_moreThanThreeRoleCards_onlyThreeSlotsDrawn() {
        val layout = computeDeckImageLayout(spec(roleCount = 5, actionCount = 12), fakeMeasurer)
        assertEquals("恒 3 张判据落到槽位：5 张只排 3 个槽", 3, layout.roleSlots.size)
        assertEquals(120, layout.roleSlots.first().widthPx)
        assertEquals(200, layout.roleSlots.first().heightPx)
        // 居中：块宽 3×120+2×14=388 ⇒ 左缘 (750-388)/2=181
        assertEquals(181, layout.roleSlots.first().xPx)
    }

    @Test
    fun layout_actionGridSixColumnsCenteredAndFooterReserved() {
        val layout = computeDeckImageLayout(spec(actionCount = 7), fakeMeasurer)
        assertEquals("7 张 → 6+1 两行", 2, layout.actionSlots.map { it.yPx }.distinct().size)
        // 居中：块宽 6×96+5×14=646 ⇒ 左缘 (750-646)/2=52（与 cardsLeft 重合，恰好铺满）
        assertEquals(52, layout.actionSlots.first().xPx)
        // 🔴 V38：页脚**不再跟最后一张实际牌走**，而是按合法卡组固定 5 行（6×5=30）的底缘算 ——
        //    7 张只画 2 行，但末行空位仍占高，否则「这牌组不合法」会被压缩掉看不出来。
        val legalRows = DECK_ACTION_FULL_DECK_COUNT / DECK_ACTION_GRID_COLUMNS
        val legalBottom = layout.actionSlots.first().yPx +
            legalRows * layout.actionCardHeightPx + (legalRows - 1) * DECK_CARD_GRID_GAP_PX
        assertTrue("页脚给 UID/昵称两行留了位", layout.heightPx - legalBottom >= 90)
        assertEquals("footerTop 即 UID 行起点（按合法 5 行底缘，非最后一张牌底缘）", legalBottom + 30, layout.footerTopPx)
    }

    @Test
    fun deckFooterLines_uidFirstThenNickname() {
        assertEquals(
            listOf("UID:261958214", "Oscuro"),
            deckFooterLines("分享人：Oscuro", "261958214"),
        )
        assertEquals("UID 缺失只画一行昵称", listOf("Oscuro"), deckFooterLines("分享人：Oscuro", null))
        assertEquals("全空不画", emptyList<String>(), deckFooterLines("", "  "))
    }

    @Test
    fun sectionColors_sampledFromReferenceScreenshot() {
        // 🔴 数值取自 PIL 对 20260929181737.png 的采样（纸面众数 219,213,206 / 标题笔画众数 132,96,61），
        // 判据用通道距离 ≤12，防手滑改数而不是防采样误差。
        fun rgb(p: Int) = Triple(p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF)
        fun closeTo(c: Int, r: Int, g: Int, b: Int): Boolean {
            val (cr, cg, cb) = rgb(c)
            return abs(cr - r) <= 12 && abs(cg - g) <= 12 && abs(cb - b) <= 12
        }
        assertTrue("纸面米白要贴参考图采样值", closeTo(DECK_COLOR_PAPER_BG, 219, 213, 206))
        assertTrue("分区标题金棕要贴参考图采样值", closeTo(DECK_COLOR_SECTION_TITLE, 132, 96, 61))
    }

    @Test
    fun renderer_usesCardFrameAssetFromNodpi() {
        // 卡框素材必须落 drawable-nodpi（否则 Android 按 dpi 缩放会糊），且资源真实存在
        val src = File("src/main/java/com/gigi/tcg/ui/export/DeckImageRenderer.kt").readText()
        assertTrue("渲染层要叠官方卡框", src.contains("v38_card_frame"))
        assertTrue("素材必须放 nodpi 目录", File("src/main/res/drawable-nodpi/v38_card_frame.png").exists())
    }

    // ---- ⑥ V39-A1：页脚左缘对齐卡牌网格 + 网格右边界下方画牌组名 ----

    /**
     * 用户原话：左下角的 UID 和 ID 要**左对齐于卡牌网格的左边界**，并在**网格右边界下方**加一行**卡组名**。
     * 版式层出 footerLeftPx/footerRightPx，绘制层照抄 ⇒ 这两个数就是全部实现，JVM 锁死。
     * 数值口径：textLeft 30（body 15）/ cardsLeft 52（+ 牌组区 11）/ 网格右边界 698（= 750 − 52）。
     */
    @Test
    fun layout_footerLanes_matchCardGridEdges() {
        val layout = computeDeckImageLayout(spec(), fakeMeasurer)
        assertEquals("页脚左缘 = 卡牌网格左缘", layout.cardsLeftPx, layout.footerLeftPx)
        assertEquals("页脚右缘 = 卡牌网格右边界", layout.cardsLeftPx + layout.cardsContentWidthPx, layout.footerRightPx)
        assertTrue("左缘要比旧的 body padding 更靠右（否则就是用户看到的没对齐）", layout.footerLeftPx > layout.textLeftPx)
        assertTrue("右缘不得越过画布右内边距", layout.footerRightPx <= layout.widthPx - layout.cardsLeftPx)
        assertEquals(52, layout.footerLeftPx)
        assertEquals(698, layout.footerRightPx)
    }

    /** 页脚两缘只由画布宽与两级 padding 决定 ⇒ 空牌组 / 40 张 / 超长牌组名都不该漂 */
    @Test
    fun layout_footerLanes_stableAcrossDeckShapes() {
        listOf(
            spec(roleCount = 0, actionCount = 0, pills = emptyList()),
            spec(actionCount = 40),
            spec(title = "很长的牌组名字".repeat(30)),
            spec(authorText = ""),
        ).forEach { shape ->
            val layout = computeDeckImageLayout(shape, fakeMeasurer)
            assertEquals("footerLeft 恒等于 cardsLeft", layout.cardsLeftPx, layout.footerLeftPx)
            assertEquals("footerRight 恒等于网格右边界", layout.cardsLeftPx + layout.cardsContentWidthPx, layout.footerRightPx)
            assertTrue("右缘不越界", layout.footerRightPx <= layout.widthPx - layout.cardsLeftPx)
            assertTrue(
                "页脚一行预算宽 = 网格宽 646（左行 UID + 右行卡组名共用）",
                layout.footerRightPx - layout.footerLeftPx == layout.cardsContentWidthPx,
            )
        }
    }

    /** 绘制层闸门：页脚必须消费 layout 的两个新字段，且不得再用 body padding 画 UID 行左缘 */
    @Test
    fun renderer_footerDrawsOnGridLayoutLanes() {
        val code = File("src/main/java/com/gigi/tcg/ui/export/DeckImageRenderer.kt").readText()
            .lines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }.joinToString("\n")
        assertTrue("UID 行左缘要走 layout.footerLeftPx", code.contains("layout.footerLeftPx"))
        assertTrue("卡组名右缘要走 layout.footerRightPx", code.contains("layout.footerRightPx"))
        assertFalse("不得再用 body padding 画页脚左缘（比网格少 11 设计 px，正是用户指的没对齐）",
            code.contains("drawText(line, DECK_BODY_PADDING_SIDE_PX"))
        assertTrue("卡组名超长要截断（复用 ellipsize，不另造一套）", code.contains("ellipsize(deckName,"))
        assertTrue("画完卡组名要把 textAlign 复位，别给后续绘制串味", code.contains("textAlign = Paint.Align.LEFT"))
    }

    // ---- ⑦ V39-F3：页脚按**墨迹**边缘对齐（drawText 的 origin 带 left side bearing） ----

    /**
     * 真机导出图像素实测：行动牌网格墨迹左缘 x=52、右缘 x=698，而页脚 UID 墨迹左缘 x=54（+2）、
     * 卡组名墨迹右缘 x=695（−3）⇒ 差值正好是字形的 side bearing。
     * 纯函数把「目标边缘 + 墨迹包围盒」反推成 origin，JVM 直接锁算术（`Paint.getTextBounds` 依赖
     * Android，测不了，故渲染层只负责喂 bounds.left / bounds.right）。
     */
    @Test
    fun footerTextOriginX_shiftsInkLeftEdgeOntoGridEdge() {
        // bearing = 2（实测 UID 行内缩 2px）⇒ origin 往左让 2，墨迹才落在 52
        assertEquals(50, footerTextOriginX(edgePx = 52, inkLeftPx = 2))
        assertEquals("墨迹左缘 = origin + bearing 回到目标边", 52, footerTextOriginX(52, 2) + 2)
        assertEquals("bearing = 0（无前伸）时 origin 就等于目标边", 52, footerTextOriginX(52, 0))
        assertEquals("bearing 更大也照样贴边（不同字号/字族）", 46, footerTextOriginX(52, 6))
        assertEquals("负 bearing（斜体 f 一类左突）⇒ origin 右移，不能夹成 0", 55, footerTextOriginX(52, -3))
    }

    /** 右列同理：bounds.right 是墨迹右缘相对 origin 的正偏移（实测 ≈3px），origin 要往右挪 */
    @Test
    fun footerTextOriginXRight_shiftsInkRightEdgeOntoGridEdge() {
        assertEquals(695, footerTextOriginXRight(edgePx = 698, inkRightPx = 3))
        assertEquals("墨迹右缘 = origin + bounds.right 回到目标边", 698, footerTextOriginXRight(698, 3) + 3)
        assertEquals("bounds.right 含字宽（Align.LEFT 下是从 origin 到墨迹右缘的全长）", 498, footerTextOriginXRight(698, 200))
        assertEquals("bearing = 0 时不漂", 698, footerTextOriginXRight(698, 0))
    }

    /** 每行各算自己的 bounds：UID 行与昵称行字族不同 ⇒ bearing 不能复用 */
    @Test
    fun footerOriginX_perLineBounds_canDifferAcrossLines() {
        val uidOrigin = footerTextOriginX(52, 2)
        val nicknameOrigin = footerTextOriginX(52, 0)
        assertTrue("两行 bearing 不同 ⇒ origin 就该不同（只算第一行复用会留下 1~2px 残差）", uidOrigin != nicknameOrigin)
        assertEquals("但墨迹左缘都落到同一条网格线", 52, uidOrigin + 2)
        assertEquals(52, nicknameOrigin + 0)
    }

    /** 绘制层闸门：页脚必须逐行取墨迹包围盒并走两个纯函数，右列不得再用 Align.RIGHT */
    @Test
    fun renderer_footerAlignsInkEdgesNotOrigins() {
        val code = File("src/main/java/com/gigi/tcg/ui/export/DeckImageRenderer.kt").readText()
            .lines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }.joinToString("\n")
        assertTrue("左列要走墨迹 origin 纯函数", code.contains("footerTextOriginX(layout.footerLeftPx"))
        assertTrue("右列要走墨迹 origin 纯函数", code.contains("footerTextOriginXRight(layout.footerRightPx"))
        assertTrue("必须逐行取墨迹包围盒（getTextBounds 才有 bounds.left/right）", code.contains("footerPaint.getTextBounds(line,"))
        assertTrue("卡组名也要取自己的 bounds", code.contains("footerPaint.getTextBounds(nameText,"))
        assertFalse("右列不得再用 Align.RIGHT（bearing 会留在另一边，实测内缩 3px）",
            code.contains("footerPaint.textAlign = Paint.Align.RIGHT"))
    }
}
