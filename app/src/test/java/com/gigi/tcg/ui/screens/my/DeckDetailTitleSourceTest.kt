// V39-G2：卡组详情页标题体系反转闸门 —— 顶栏主标题改固定「卡组详情」、牌组名下沉页内小标题。
// 🔴 实测背景：V37-F 起顶栏主标题 = 导航参数里的牌组名（deckDetailTitle），用户本轮拍板反转：
//   顶栏要固定文案、页内动作行继续显示牌组名。GigiNavHost.kt 是 @Composable 壳，
//   JVM 单测跑不起 Compose runtime（工程没有 Robolectric / Compose UI 测试依赖），
//   照本目录先例 DeckDetailTitleIndentTest 做**源码结构断言**：只读源码文本 + 解析 strings.xml。
package com.gigi.tcg.ui.screens.my

import java.io.File
import org.junit.Assert.assertEquals
import java.io.FileNotFoundException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckDetailTitleSourceTest {

    // ---- ① 新增键在三份语言 strings.xml 里都在、非空，且 zh-TW 真的转了繁体 ----

    @Test
    fun `every language strings variant defines the fixed detail title key`() {
        val variants = stringBearingValueDirs()
        assertTrue("至少要找到默认 values/strings.xml，一个语言目录都不许漏", variants.isNotEmpty())
        variants.forEach { dir ->
            val xml = File(dir, "strings.xml").readText()
            val value = extractString(xml, KEY)
            assertTrue("${dir.nameWithoutExtension}/strings.xml 缺少键 $KEY", value != null)
            assertTrue("${dir.nameWithoutExtension}/strings.xml 的 $KEY 是空串", !value.isNullOrBlank())
        }
    }

    @Test
    fun `zh default says simplified deck details`() {
        val zh = extractString(File(RES_ROOT + "values/strings.xml").readText(), KEY)
        assertEquals("简中标题必须是「卡组详情」", "卡组详情", zh)
    }

    @Test
    fun `zh-rTW is genuinely traditional and differs from zh-CN`() {
        val twFile = File(RES_ROOT + "values-zh-rTW/strings.xml")
        assertTrue("values-zh-rTW/strings.xml 必须存在（工程有繁体变体）", twFile.exists())
        val tw = extractString(twFile.readText(), KEY)
        val zh = extractString(File(RES_ROOT + "values/strings.xml").readText(), KEY)
        assertEquals("繁中标题必须是「卡組詳情」", "卡組詳情", tw)
        // 硬约束：照抄简体（组/详未转换）就是本用例要拦的事故
        assertFalse("zh-TW 的值不许等于简体值（防忘转繁体）", tw == zh)
    }

    // ---- ② GigiNavHost：顶栏走新键、牌组名退出标题链、返回箭头判据不丢 ----

    @Test
    fun `topbar title chain no longer consumes the deck name`() {
        val code = navCode()
        assertFalse("标题链不许再有 deckDetailTitle ?:（牌组名已退出顶栏）", code.contains("deckDetailTitle ?:"))
        assertFalse("deckDetailTitle 变量本体已退役", code.contains("val deckDetailTitle"))
        assertTrue(
            "主标题回落链必须是 subpageTitleRes -> tabLabel -> GIGI",
            code.contains("subpageTitleRes?.let { stringResource(it) } ?: tabLabel ?: \"GIGI\","),
        )
    }

    @Test
    fun `deck detail route maps to the fixed title key`() {
        val code = navCode()
        val whenBlock = code.blockAfter("val subpageTitleRes = when {")
        assertTrue("when 里详情页必须显式挂 $KEY（主标题固定「卡组详情」）", whenBlock.contains("$KEY"))
        assertTrue("挂载走 isDeckDetailPage 判定", whenBlock.contains("isDeckDetailPage -> R.string.my_deck_detail_title"))
        assertFalse(
            "旧兜底「我的卡组」分支不许留在 when 里（那是顶栏显示牌组名时代的回落）",
            whenBlock.contains("currentRoute == ROUTE_MY_DECK_DETAIL -> R.string.my_deck_entry"),
        )
    }

    @Test
    fun `back button predicate still covers the deck detail route`() {
        val code = navCode()
        val line = code.lines().firstOrNull { it.contains("val showBackButton") }
        assertTrue("showBackButton 判据还在", line != null)
        assertTrue("判据认二级页标题", line!!.contains("subpageTitleRes != null"))
        // V39-F5 成果不许回退：返回按钮判据必须显式含 ROUTE_MY_DECK_DETAIL，
        // 不能再依赖标题来源（标题来源一变，箭头就跟着漂移——正是 V37-F 的事故机理）。
        assertTrue("判据显式认详情页路由", line.contains("currentRoute == ROUTE_MY_DECK_DETAIL"))
        assertTrue("navigationIcon 仍按 showBackButton 出箭头", code.blockAfter("navigationIcon = {", NAV_ICON_LINES).contains("if (showBackButton)"))
    }

    @Test
    fun `second-level subpage titles are untouched`() {
        val code = navCode()
        // 二级页（卡组列表/卡背/收藏/胜冠）仍走 MY_SUBPAGE_TITLES 映射，一行行为不变
        assertTrue("when 的 else 仍查 MY_SUBPAGE_TITLES", code.contains("else -> MY_SUBPAGE_TITLES[currentRoute]"))
        val mapBlock = code.blockAfter("private val MY_SUBPAGE_TITLES = mapOf(")
        listOf(ROUTE_MY_DECK, ROUTE_MY_CARDBACK, ROUTE_MY_FAVORITES, ROUTE_MY_CHALLENGE).forEach { route ->
            assertTrue("二级页 $route 的标题映射不许丢", mapBlock.contains(route))
        }
        // 详情页标题的单行 + 省略号版式保留
        val titleBlock = code.blockAfter("title = {", TITLE_BLOCK_LINES)
        assertTrue("标题仍单行", titleBlock.contains("maxLines = 1,"))
        assertTrue("标题仍省略号截断", titleBlock.contains("overflow = TextOverflow.Ellipsis,"))
    }

    // ---- ③ MyDecksPage：页内小标题继续显示牌组名、16dp 左缘不动 ----

    @Test
    fun `deck name still renders in the page action row with its inset`() {
        val code = fileCode(MY_DECKS)
        assertTrue("页内小标题仍渲染牌组名", code.contains("deckDisplayName(deck),"))
        assertTrue("上一轮的 16dp start 内距一行不许动", code.contains(".padding(start = MyRowHorizontalPadding)"))
        assertTrue("锚点 .padding(top = 4.dp, end = 4.dp) 原文还在（两段 padding 不许合并）", code.contains(".padding(top = 4.dp, end = 4.dp)"))
    }

    // ---- helpers（口径照 DeckDetailTitleIndentTest）----

    private companion object {
        const val KEY = "my_deck_detail_title"
        const val RES_ROOT = "src/main/res/"
        const val NAV_HOST = "src/main/java/com/gigi/tcg/ui/navigation/GigiNavHost.kt"
        const val MY_DECKS = "src/main/java/com/gigi/tcg/ui/screens/my/MyDecksPage.kt"

        const val ROUTE_MY_DECK = "ROUTE_MY_DECK"
        const val ROUTE_MY_CARDBACK = "ROUTE_MY_CARDBACK"
        const val ROUTE_MY_FAVORITES = "ROUTE_MY_FAVORITES"
        const val ROUTE_MY_CHALLENGE = "ROUTE_MY_CHALLENGE"

        /** navigationIcon 槽从声明到闭合正好 10 行（同 DetailTopBarBackButtonTest 口径）。 */
        const val NAV_ICON_LINES = 10
        const val TITLE_BLOCK_LINES = 12

        val STRING_KEY_REGEX = Regex("""<string\s+name="([^"]+)"\s*>""")
    }

    private fun navCode(): String = fileCode(NAV_HOST)

    /** 只读代码体：剔掉注释行，注释改写不该让闸门变红（同先例）。 */
    private fun fileCode(path: String): String {
        val file = File(path)
        assertTrue("源码文件不存在: ${file.absolutePath}", file.exists())
        return file.readText().lines()
            .filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }
            .joinToString("\n")
    }

    /** 枚举所有含 strings.xml 的 values* 目录（语言变体），night/v31 等非语言变体没 strings 文件自然被跳过。 */
    private fun stringBearingValueDirs(): List<File> {
        val root = File(RES_ROOT)
        assertTrue("资源目录不存在: ${root.absolutePath}", root.exists())
        return root.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("values") && File(it, "strings.xml").exists() }
            ?.sortedBy { it.name }
            ?: throw FileNotFoundException("$RES_ROOT 下没有任何 values* 目录")
    }

    /** 本测试兼做「各语言键集一致」验收的机器可读口径：抽出目录级键集合。 */
    private fun stringKeys(dir: File): Set<String> =
        STRING_KEY_REGEX.findAll(File(dir, "strings.xml").readText()).map { it.groupValues[1] }.toSet()

    @Test
    fun `all language variants share the same key set`() {
        val variants = stringBearingValueDirs()
        assertTrue("至少要有默认 values 加若干语言变体", variants.size >= 2)
        val baseline = stringKeys(variants.first())
        assertTrue("默认 $KEY 键集基线非空", baseline.isNotEmpty())
        variants.forEach { dir ->
            val keys = stringKeys(dir)
            val missing = baseline - keys
            val extra = keys - baseline
            assertTrue("${dir.name} 相对 ${variants.first().name} 缺键: $missing", missing.isEmpty())
            assertTrue("${dir.name} 相对 ${variants.first().name} 多键: $extra", extra.isEmpty())
            assertEquals("${dir.name} 键数须与基线一致", baseline.size, keys.size)
        }
    }

    private fun extractString(xml: String, key: String): String? =
        Regex("""<string\s+name="$key"[^>]*>([^<]*)</string>""").find(xml)?.groupValues?.get(1)?.trim()

    /** 取该 key 之后 N 行，用于断言同一 when / 槽位内部的内容（同先例）。 */
    private fun String.blockAfter(key: String, lines: Int = 25): String {
        val index = this.lines().indexOfFirst { it.contains(key) }
        if (index < 0) return ""
        return this.lines().subList(index, minOf(index + lines, this.lines().size)).joinToString("\n")
    }
}
