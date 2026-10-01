// V39-F4：卡组详情页动作行「卡组名」左缘内距闸门 —— 与主壳顶栏主标题同线（16dp）。
// 🔴 钉死的实测事实（真机 1080×2340 / 440dpi，density 2.75，uiautomator dump）：
//   顶栏主标题「我的牌组」bounds=[44,124][284,211] ⇒ 文字墨迹左缘 x=44px = 44/2.75 = **16dp**；
//   动作行卡组名 bounds=[0,300][805,366]        ⇒ 文字墨迹左缘 x=0px = **0dp**（父 Row 没有 start 内距）。
//   差的就是这 16dp ⇒ 用户说的「没有左边距」。修法是给动作行 Row 补 start = MyRowHorizontalPadding。
//
// deckDisplayName 是 @Composable ⇒ JVM 单测调不动，照本目录先例 MyDeckActionCardTotalTest 做**源码闸门**：
// 只读源码文本 + 纯算术，不 import android.graphics / Compose（工程铁律）。
// 常量值也从 MySubpages.kt 原文里抠数字来断，避免为了拿 Dp 而破铁律。
package com.gigi.tcg.ui.screens.my

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckDetailTitleIndentTest {

    // ---- ① 实测依据：两边左缘的 dp 差就是 16dp，别拿"看着差不多"当口径 ----

    @Test
    fun `measured topbar title ink starts at 16dp and the unfixed action row at 0dp`() {
        val density = 2.75 // 440dpi / 160
        val topbarTitleLeftPx = 44 // bounds=[44,124][284,211]
        val actionRowNameLeftPx = 0 // bounds=[0,300][805,366]（修复前实测）
        assertEquals("顶栏主标题左缘 = 16dp", 16.0, topbarTitleLeftPx / density, 0.001)
        assertEquals("修复前动作行卡组名左缘 = 0dp", 0.0, actionRowNameLeftPx / density, 0.001)
        assertEquals("缺的正是 16dp", 16.0, (topbarTitleLeftPx - actionRowNameLeftPx) / density, 0.001)
    }

    @Test
    fun `the 16dp inset puts the name back on the topbar title column`() {
        val density = 2.75
        assertEquals("补齐后动作行卡组名左缘 = 44px，与顶栏主标题同线", 44.0, 16f * density, 0.5)
    }

    // ---- ② 修复点：动作行 Row 必须有 start 内距，且走常量而非硬编码 ----

    @Test
    fun `action row pads its start with the shared my-page content inset`() {
        val code = myDecksCode()
        assertTrue("动作行 Row 必须有 start 内距", code.contains(".padding(start = MyRowHorizontalPadding)"))
        assertFalse("不许硬编码 16.dp（顶栏口径变了会一起漂）", code.contains(".padding(start = 16.dp)"))
        assertFalse("不许为新页硬编码 12dp 混口径", code.contains(".padding(start = 12.dp)"))
        assertEquals("start 内距只出现在动作行这一处", 1, code.countOccurrences(".padding(start = MyRowHorizontalPadding)"))
    }

    @Test
    fun `shared inset constant itself stays at 16dp so both sides cannot drift together`() {
        val subpages = File(MY_SUBPAGES).readText()
        val line = subpages.lines().firstOrNull { it.contains("val MyRowHorizontalPadding") }
        assertTrue("常量定义还在 MySubpages.kt（本任务不许改这个文件）", line != null)
        val rhs = line!!.substringAfter("=").trim()
        assertTrue("必须仍是 Dp 字面量，不许悄悄换成 Int / dimensionResource", rhs.contains(".dp"))
        val value = rhs.substringBefore(".dp").trim().toFloatOrNull()
        assertEquals("MyRowHorizontalPadding 现值必须仍是 16dp（与顶栏标题墨迹同口径）", 16f, value ?: -1f, 0.001f)
    }

    @Test
    fun `the two paddings stay split so the row fingerprint survives`() {
        val code = File(MY_DECKS).readText()
        val startIdx = code.indexOf(".padding(start = MyRowHorizontalPadding)")
        val legacyIdx = code.indexOf(".padding(top = 4.dp, end = 4.dp)")
        assertTrue("start 内距那行还在", startIdx >= 0)
        assertTrue("锚点 .padding(top = 4.dp, end = 4.dp) 原文必须还在（合并成一个调用就没了）", legacyIdx >= 0)
        assertTrue("两段 padding 相邻、start 在前", legacyIdx > startIdx)
        assertTrue(
            "两行之间只允许空白缩进（不许插进第三个 modifier）",
            code.substring(startIdx + ".padding(start = MyRowHorizontalPadding)".length, legacyIdx).all { it.isWhitespace() },
        )
    }

    @Test
    fun `other paddings on the action row are untouched`() {
        val code = myDecksCode()
        assertTrue("top = 4.dp 保持不动", code.contains(".padding(top = 4.dp, end = 4.dp)"))
        assertFalse("end = 4.dp 不许改成 16dp（48dp 触摸区外沿留 4dp，图标才落在 16dp 线上）", code.contains("end = 16.dp"))
    }

    // ---- ③ 版式没被顺手改掉：名在左、两个图标在右、占满剩余宽度 ----

    @Test
    fun `deck name still leads the row and the two icon buttons still trail it`() {
        val row = myDecksCode().blockAfter(".padding(top = 4.dp, end = 4.dp)")
        assertTrue("动作行本体还在", row.contains("IconButton"))
        assertTrue("左端仍是牌组名", row.contains("deckDisplayName(deck),"))
        assertTrue("名称仍占满剩余宽度", row.contains("modifier = Modifier.weight(1f),"))
        assertTrue("名称仍在复制按钮之前（左 vs 右）", row.indexOf("deckDisplayName(deck)") < row.indexOf("IconButton"))
        assertEquals("行内仍是两个动作图标", 2, row.lines().count { it.trim().startsWith("IconButton(") })
        assertTrue("仍是单行 + 省略号", row.contains("maxLines = 1,") && row.contains("overflow = TextOverflow.Ellipsis,"))
    }

    @Test
    fun `start inset is added after fillMaxWidth so the row keeps its full width`() {
        val code = File(MY_DECKS).readText()
        val startIdx = code.indexOf(".padding(start = MyRowHorizontalPadding)")
        val fillIdx = code.lastIndexOf(".fillMaxWidth()", startIdx)
        assertTrue("Row 仍是 fillMaxWidth", fillIdx >= 0)
        assertTrue("先占满再缩内容 ⇒ 行总宽不变，图标仍贴右", startIdx > fillIdx)
    }

    @Test
    fun `inset is on the action row not on the scrolling column so groups are not double-padded`() {
        // 下面的 CardGroup 自己已经 pad(horizontal = MyRowHorizontalPadding)；
        // 若把内距补到外层 Column / Scaffold 上，卡面组会被二次内距（32dp）⇒ 整页变窄。
        val code = myDecksCode()
        assertEquals("CardGroup 的 horizontal 内距调用点数不变", 2, code.countOccurrences("padding(horizontal = MyRowHorizontalPadding"))
        val scaffold = File(MY_SUBPAGES).readText()
        assertTrue("二级页容器仍不加水平内距（所以动作行必须自己 pad）", scaffold.contains("Column(modifier.fillMaxSize()) { content() }"))
    }

    // ---- ④ 硬约束：顶栏一个字都不许动 ----

    @Test
    fun `shell topbar still owns the back button and the deck detail title`() {
        val nav = fileCode("src/main/java/com/gigi/tcg/ui/navigation/GigiNavHost.kt")
        assertTrue("TopAppBar 块还在", nav.contains("TopAppBar("))
        assertTrue("返回入口仍在顶栏 navigationIcon", nav.contains("navigationIcon = {"))
        assertTrue("返回箭头图标未被删", nav.contains("Icons.AutoMirrored.Outlined.ArrowBack"))
        assertTrue("返回仍走 navigateUp", nav.contains("navController.navigateUp()"))
        // 🔴 V39-G2 语义反转：主标题不再显示牌组名，改固定「卡组详情」；牌组名下沉到页内小标题。
        assertTrue("顶栏主标题改走固定「卡组详情」（用户要求）", nav.contains("my_deck_detail_title"))
        assertFalse("顶栏标题不再消费牌组名（牌组名只留在页内动作行）", nav.contains("deckDetailTitle ?:"))
    }

    @Test
    fun `detail page does not grow its own header again`() {
        val code = myDecksCode()
        assertFalse("页内自绘标题行会与主壳顶栏叠成双标题（V37-F 已删，别再长回来）", code.contains("TopAppBar"))
        assertFalse("页内不许出现返回按钮", code.contains("ArrowBack") || code.contains("navigateUp()"))
        assertFalse("页内不许出现 navigationIcon 槽", code.contains("navigationIcon"))
    }

    // ---- helpers（口径照 MyDeckActionCardTotalTest） ----

    private companion object {
        const val MY_DECKS = "src/main/java/com/gigi/tcg/ui/screens/my/MyDecksPage.kt"
        const val MY_SUBPAGES = "src/main/java/com/gigi/tcg/ui/screens/my/MySubpages.kt"
    }

    private fun myDecksCode(): String = fileCode(MY_DECKS)

    private fun fileCode(path: String): String {
        val file = File(path)
        assertTrue("源码文件不存在: ${file.absolutePath}", file.exists())
        return file.readText().lines()
            .filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }
            .joinToString("\n")
    }

    /** 取该 key 之后 30 行，用于断言同一 Row 内的子元素顺序。 */
    private fun String.blockAfter(key: String, lines: Int = 30): String {
        val index = this.lines().indexOfFirst { it.contains(key) }
        if (index < 0) return ""
        return this.lines().subList(index, minOf(index + lines, this.lines().size)).joinToString("\n")
    }

    private fun String.countOccurrences(needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val index = this.indexOf(needle, from)
            if (index < 0) return count
            count++
            from = index + needle.length
        }
    }
}
