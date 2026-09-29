// 卡组导出图的纯逻辑回归锁（V37-F 任务 E，纯 JVM、无 Robolectric）。
// 锁三件事：① 官方 375/90/76/54/15·15·20/10·11·16 这套数值的 ×2 换算口径；
// ② 空牌组 / 超长牌组名 / 40 张牌三类边界不崩、不溢出画布；③ 文件名清洗与反馈/默认文案通道。
// 假 measurer 与 TableLayoutTest 同一口径：CJK=1.0×字号、拉丁≈0.55×字号（真机字宽只小不大 ⇒ 保守上界）。
package com.gigi.tcg.ui.export

import com.gigi.tcg.R
import com.gigi.tcg.data.model.GcgDeck
import com.gigi.tcg.data.model.GcgDeckCard
import com.gigi.tcg.i18n.LocaleStrings
import java.io.File
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

    // ---- ① 官方尺寸换算 ----

    @Test
    fun canvasAndPartSizes_matchOfficialSpec() {
        assertEquals("画布宽 = 375 设计宽 × DPR 2", 750, DECK_CANVAS_WIDTH_PX)
        assertEquals("share-header 90dp → 180px", 180, DECK_HEADER_HEIGHT_PX)
        assertEquals("share-body 左右 padding 15 → 30px", 30, DECK_BODY_PADDING_SIDE_PX)
        assertEquals("share-body 底 padding 20 → 40px", 40, DECK_BODY_PADDING_BOTTOM_PX)
        assertEquals("牌组区 padding 10/11/16 → 20/22/32px", 22, DECK_GROUP_PADDING_SIDE_PX)
        assertEquals("角色牌图 76 → 152px", 152, DECK_ROLE_CARD_WIDTH_PX)
        assertEquals("行动牌图 54 → 108px", 108, DECK_ACTION_CARD_WIDTH_PX)
        assertEquals("152 宽按卡面 160:275 立框", 261, deckCardHeightPx(152))
        assertEquals("108 宽按卡面 160:275 立框", 186, deckCardHeightPx(108))
    }

    @Test
    fun layout_textAndCardLanes_useBodyThenGroupPadding() {
        val layout = computeDeckImageLayout(spec(), fakeMeasurer)
        assertEquals(30, layout.textLeftPx)
        assertEquals(690, layout.textContentWidthPx)
        assertEquals("牌面区左缘 = body 15 + 牌组区 11", 52, layout.cardsLeftPx)
        assertEquals(646, layout.cardsContentWidthPx)
        assertEquals("标题顶 = header 180 + body 顶 padding 30", 210, layout.titleTopPx)
    }

    @Test
    fun layout_realDeck_rolesThreeColumnsActionsFive() {
        val layout = computeDeckImageLayout(spec(roleCount = 3, actionCount = 24), fakeMeasurer)
        assertEquals(3, layout.roleSlots.size)
        assertEquals("角色牌一行三列（3×152+2×16 ≤ 646）", 3, layout.roleSlots.map { it.xPx }.distinct().size)
        assertEquals(1, layout.roleSlots.map { it.yPx }.distinct().size)
        assertEquals(24, layout.actionSlots.size)
        assertEquals("行动牌一行五列（5×108+4×16 ≤ 646）", 5, layout.actionSlots.take(5).map { it.xPx }.distinct().size)
        assertEquals("24 张 → 5 行", 5, layout.actionSlots.map { it.yPx }.distinct().size)
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
        assertEquals("40 张 5 列 → 8 行", 8, layout.actionSlots.map { it.yPx }.distinct().size)
        val rightEdge = layout.actionSlots.maxOf { it.xPx + it.widthPx }
        assertTrue("最右牌面不得越过右内边距：$rightEdge > 750-52", rightEdge <= 750 - 52)
        assertTrue("末行底不得越过画布高：${layout.actionSlots.last().yPx}", layout.actionSlots.last().yPx + layout.actionCardHeightPx <= layout.heightPx)
        val small = computeDeckImageLayout(spec(actionCount = 5), fakeMeasurer)
        assertTrue("纵向只增长不分页：40 张比 5 张高", layout.heightPx > small.heightPx)
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
        assertFalse("张数文字已删（card.num 恒 1 无信息量）", code.contains("card.num"))
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
}
