// V39-A2：卡组列表摘要「行动牌总张数」判据 + 与导出图同口径 + 详情页动作行版式闸门。
// 🔴 钉死的实测事实（真机 gcg/deckList，出处 .task/v36-probe/basicinfo/raw/ctrl-deckList.json）：
//   一副合法牌组的行动牌**种类数只有 22~25**，但 `sum(num)` 恒为 30（同一张牌可带 2 份）。
//   列表行原来画 `.size`（种类数 22）⇒ 用户看到「行动牌 22」而规则要求 30 张（本轮改动动机）。
// 口径必须与 ui/export/DeckImageExport.expandActionCardsByCount 一致：`num` 缺失 / ≤0 一律记 1。
// 本类只做字符串与算术 + 读源码文本，不 import android.graphics / Compose（工程铁律）。
package com.gigi.tcg.ui.screens.my

import com.gigi.tcg.data.model.GcgDeckCard
import com.gigi.tcg.ui.export.expandActionCardsByCount
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyDeckActionCardTotalTest {

    // ---- ① 纯判据：总张数怎么算 ----

    @Test
    fun `null list and empty list both read zero`() {
        assertEquals("接口可能整段不下发 action_cards", 0, deckActionCardTotal(null))
        assertEquals(0, deckActionCardTotal(emptyList()))
    }

    @Test
    fun `missing num counts as one card`() {
        // 🔴 关键口径：num == null 不是 0 张，保守按 1 张（与导出图「缺张数也画一格」同一取舍）
        assertEquals(1, deckActionCardTotal(listOf(GcgDeckCard(name = "没写张数", num = null))))
        val mixed = listOf(
            GcgDeckCard(name = "a", num = null),
            GcgDeckCard(name = "b", num = 2),
            GcgDeckCard(name = "c", num = null),
        )
        assertEquals("null + 2 + null = 1 + 2 + 1", 4, deckActionCardTotal(mixed))
    }

    @Test
    fun `zero or negative num clamps to one and never drags the total down`() {
        assertEquals(1, deckActionCardTotal(listOf(GcgDeckCard(name = "写了 0", num = 0))))
        assertEquals(1, deckActionCardTotal(listOf(GcgDeckCard(name = "脏数据", num = -3))))
        val dirty = listOf(-3, 0, -1, 0).map { GcgDeckCard(name = "x", num = it) }
        assertEquals("四条脏数据也要数出 4 张，不得为 0 或负", 4, deckActionCardTotal(dirty))
        assertTrue(deckActionCardTotal(dirty) > 0)
    }

    @Test
    fun `normal nums are summed not counted`() {
        val cards = listOf(1, 2, 1, 2, 3).map { GcgDeckCard(name = "x", num = it) }
        assertEquals("1+2+1+2+3", 9, deckActionCardTotal(cards))
        assertEquals("对照：种类数是 5", 5, cards.size)
    }

    @Test
    fun `measured deck shows thirty not twenty-two`() {
        // 实测第 1 副（原样抄自 raw JSON 的 action_cards.num，与 MyDeckCardTileTest 同一数组）
        val deck1 = listOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 1, 1, 2, 1, 2, 2, 2)
            .map { GcgDeckCard(name = "行动牌", num = it) }
        assertEquals("种类数 22 —— 旧代码显示的就是它", 22, deck1.size)
        assertEquals("列表行要显示总张数 30", 30, deckActionCardTotal(deck1))
        // 实测第 2 副 24 种，同样合计 30 ⇒ 「sum 恒 30」不是单副巧合
        val deck2 = listOf(1, 1, 1, 2, 1, 1, 1, 2, 2, 2, 1, 2, 1, 1, 1, 1, 1, 1, 2, 1, 1, 1, 1, 1)
            .map { GcgDeckCard(name = "行动牌", num = it) }
        assertEquals(24, deck2.size)
        assertEquals(30, deckActionCardTotal(deck2))
    }

    // ---- ② 与导出图同口径（两处「一共几张」必须对得上） ----

    @Test
    fun `total matches the number of slots the export image expands`() {
        val shapes = listOf(
            emptyList(),
            listOf(GcgDeckCard(num = null)),
            listOf(GcgDeckCard(num = 0), GcgDeckCard(num = -5)),
            listOf(GcgDeckCard(num = 1), GcgDeckCard(num = 2), GcgDeckCard(num = 3)),
            listOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 1, 1, 2, 1, 2, 2, 2)
                .map { GcgDeckCard(num = it) },
        )
        shapes.forEachIndexed { index, cards ->
            assertEquals(
                "第 $index 种形态：列表总张数必须等于导出图展开格数",
                expandActionCardsByCount(cards).size,
                deckActionCardTotal(cards),
            )
        }
    }

    // ---- ③ 版式闸门：改动点必须在代码里，而不是只活在注释里 ----

    @Test
    fun `deck row summary uses the total instead of the kind count`() {
        val code = myDecksCode()
        assertTrue("列表摘要必须传总张数", code.contains("deckActionCardTotal(deck.actionCards)"))
        assertFalse("种类数口径已废弃", code.contains("deck.actionCards.orEmpty().size"))
    }

    @Test
    fun `detail action row leads with the deck name and trails with two icons`() {
        val code = myDecksCode()
        // 动作行的唯一指纹：顶栏已删（V37-F），这一行的 padding 就是它
        val row = code.blockAfter(".padding(top = 4.dp, end = 4.dp)")
        assertTrue("动作行本体必须存在（源码指纹变了就同步改测试）", row.contains("IconButton"))
        assertFalse("动作行不再整体右对齐（End），改为名称占权重 + 图标贴右", row.contains("horizontalArrangement = Arrangement.End"))
        assertTrue("左端要画牌组名", row.contains("deckDisplayName(deck),"))
        assertTrue("名称占满剩余宽度", row.contains("modifier = Modifier.weight(1f),"))
        assertTrue("名称单行 + 省略号（不能把图标挤出屏幕）", row.contains("maxLines = 1,") && row.contains("overflow = TextOverflow.Ellipsis,"))
        assertTrue("名称必须在复制按钮之前（左端 vs 右端）", row.indexOf("deckDisplayName(deck)") < row.indexOf("IconButton"))
        assertEquals("行内两个动作图标：复制分享码 / 导出图", 2, row.lines().count { it.trim().startsWith("IconButton(") })
        assertTrue("两个图标仍是 ContentCopy / IosShare", row.contains("Icons.Outlined.ContentCopy") && row.contains("Icons.Outlined.IosShare"))
    }

    @Test
    fun `avatar tile on the list row is widened to 72dp within the width budget`() {
        val code = myDecksCode()
        assertTrue("卡面宽度 52 → 72dp", code.contains(".width(72.dp)"))
        assertFalse("旧宽度不得残留", code.contains(".width(52.dp)"))
        // 三张 72 + 两道 6dp 间隙 = 228dp，360dp 屏的行可用宽 296dp ⇒ 不铺满、不挤压
        val rowUsableWidthOn360 = 360 - 16 * 2 - 16 * 2
        assertTrue(
            "360dp 屏放不下：3×72 + 2×6 = 228 > $rowUsableWidthOn360",
            3 * 72 + 2 * 6 <= rowUsableWidthOn360,
        )
    }

    @Test
    fun `total helper stays in the same file as its only two call sites`() {
        val code = myDecksCode()
        assertTrue(
            "纯函数签名（internal + 可空入参，列表与导出口径同源）",
            code.contains("internal fun deckActionCardTotal(cards: List<GcgDeckCard>?): Int"),
        )
        assertEquals(
            "工程内只允许列表行一个调用点（另一个是测试自己）",
            1,
            code.lines().count { it.contains("deckActionCardTotal(deck.actionCards)") },
        )
    }

    // ---- ④ deckDisplayName 兜底链（@Composable ⇒ JVM 只能做源码闸门，见报告） ----

    @Test
    fun `deck name fallback keeps blank-checking and the slash join`() {
        val code = fileCode("src/main/java/com/gigi/tcg/ui/screens/my/MySubpages.kt")
        assertTrue("牌组名要判空白而不是判 null（实测 name 恒为空串）", code.contains("deck?.name?.takeIf { it.isNotBlank() }"))
        assertTrue("二级兜底取角色牌名，斜杠分隔", code.contains("""avatars.joinToString(" / ")"""))
        assertTrue("三级兜底走 string", code.contains("stringResource(R.string.my_deck_entry)"))
    }

    private fun myDecksCode(): String =
        fileCode("src/main/java/com/gigi/tcg/ui/screens/my/MyDecksPage.kt")

    /** 只看代码行：注释里允许出现被删除的旧写法（KDoc 要写清改了什么）。 */
    private fun fileCode(path: String): String {
        val file = File(path)
        assertTrue("源码文件不存在: ${file.absolutePath}", file.exists())
        return file.readText().lines()
            .filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }
            .joinToString("\n")
    }

    /** 取该 key 之后的若干行，用于断言同一 Row 内的子元素顺序。 */
    private fun String.blockAfter(key: String, lines: Int = 30): String {
        val index = this.lines().indexOfFirst { it.contains(key) }
        if (index < 0) return ""
        return this.lines().subList(index, minOf(index + lines, this.lines().size)).joinToString("\n")
    }
}
