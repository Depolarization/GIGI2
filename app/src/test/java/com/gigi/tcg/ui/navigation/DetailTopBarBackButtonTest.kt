// V39-F5：主壳顶栏返回箭头判据闸门 —— 牌组详情页（三级页）也要有返回箭头。
// 🔴 实测事实：详情页顶栏此前没有返回箭头，只能靠系统手势。根因是 navigationIcon 只认
//   subpageTitleRes != null，而标题来源把 deckDetailTitle 命中时的 subpageTitleRes 置空（V37-F 语义），
//   两个关注点耦合在一个判据上 ⇒ 标题对了、箭头没了。修法是拆出独立判据 showBackButton。
//
// 🔴 V39-G2 语义反转（用户要求）：详情页顶栏主标题由「卡组名」改成固定「卡组详情」，
//   牌组名下沉到页内小标题（MyDecksPage 动作行）。于是详情页也走 subpageTitleRes，
//   判据的第二个来源从 `deckDetailTitle != null` 换成 `currentRoute == ROUTE_MY_DECK_DETAIL`
//   —— 后者不依赖标题怎么来，是「返回入口」的稳定依据。
//
// GigiNavHost.kt 是 @Composable 壳，JVM 单测跑不起 Compose runtime（工程没有 Robolectric /
// Compose UI 测试依赖），照本目录先例 DeckDetailTitleIndentTest 做**源码结构断言**：只读源码文本。
package com.gigi.tcg.ui.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailTopBarBackButtonTest {

    // ---- ① 判据本身：独立、且两个来源都认 ----

    @Test
    fun `back button has its own predicate covering both subpage and deck detail titles`() {
        val code = navCode()
        val line = code.lines().firstOrNull { it.contains("val showBackButton") }
        assertTrue("必须存在独立的 showBackButton 判据（否则箭头又会跟着标题一起没了）", line != null)
        assertTrue("判据要认二级页标题", line!!.contains("subpageTitleRes != null"))
        assertTrue(
            "判据要认牌组详情页：V39-G2 起详情页主标题改走 subpageTitleRes（固定「卡组详情」），" +
                "牌组名下沉到页内小标题，所以这里按 route 判定而不是按 deckDetailTitle",
            line.contains("ROUTE_MY_DECK_DETAIL"),
        )
        assertTrue(
            "两个来源是「或」关系：任一命中就出箭头，缺一个详情页又会没入口",
            Regex("""showBackButton\s*=\s*subpageTitleRes\s*!=\s*null\s*\|\|\s*currentRoute\s*==\s*ROUTE_MY_DECK_DETAIL""").containsMatchIn(code),
        )
    }

    @Test
    fun `showBackButton is computed next to the title sources, not inside the topbar lambda`() {
        // 判据要和 subpageTitleRes / deckDetailTitle 同一作用域（key{} 内），放进 navigationIcon 块里
        // 就只是局部变量，标题侧将来复用不到、也看不出这是一条独立规则。
        val code = navCode()
        val declIdx = code.indexOf("val showBackButton")
        val subpageDeclIdx = code.indexOf("val subpageTitleRes")
        val topBarIdx = code.indexOf("TopAppBar(")
        assertTrue("声明在 subpageTitleRes 之后（能读到它的值）", declIdx > subpageDeclIdx)
        assertTrue("声明在 TopAppBar 之前（顶栏只是消费方）", declIdx < topBarIdx)
    }

    // ---- ② 消费点：navigationIcon 块用的就是这个判据 ----

    @Test
    fun `navigationIcon block gates the back button on showBackButton`() {
        val block = navCode().blockAfter("navigationIcon = {", NAV_ICON_LINES)
        assertTrue("navigationIcon 槽还在", block.isNotEmpty())
        assertTrue("入口条件必须是 showBackButton（不能再写回 subpageTitleRes != null）", block.contains("if (showBackButton)"))
        assertFalse(
            "navigationIcon 里不许再直接判 subpageTitleRes（那正是 V37-F 把详情页箭头一并关掉的写法）",
            block.contains("subpageTitleRes"),
        )
        assertFalse("不许顺手再判 deckDetailTitle（判据已上提到 showBackButton）", block.contains("deckDetailTitle"))
    }

    @Test
    fun `back button keeps the existing icon and navigateUp handler`() {
        val block = navCode().blockAfter("navigationIcon = {", NAV_ICON_LINES)
        assertTrue("仍是 IconButton + navigateUp", block.contains("IconButton(onClick = { navController.navigateUp() })"))
        assertTrue("图标未换", block.contains("Icons.AutoMirrored.Outlined.ArrowBack"))
        assertTrue("无障碍文案仍用 R.string.action_back", block.contains("stringResource(R.string.action_back)"))
        assertEquals("箭头只有一个，别叠成双返回", 1, block.lines().count { it.trim().startsWith("IconButton(") })
    }

    // ---- ③ 标题来源：V39-G2 起详情页主标题为固定「卡组详情」，牌组名下沉到页内小标题 ----

    @Test
    fun `deck detail page maps to the fixed deck detail title`() {
        val code = navCode()
        val whenBlock = code.blockAfter("val subpageTitleRes = when {")
        assertTrue(
            "详情页主标题走固定「卡组详情」（V39-G2 用户要求：主标题不再显示牌组名）",
            whenBlock.contains("isDeckDetailPage -> R.string.my_deck_detail_title") ||
                whenBlock.contains("ROUTE_MY_DECK_DETAIL -> R.string.my_deck_detail_title"),
        )
        assertFalse(
            "顶栏标题取值链不再消费牌组名（牌组名只留在页内小标题）",
            code.contains("deckDetailTitle ?:"),
        )
    }

    @Test
    fun `deck name is still carried by nav args for the in-page subtitle`() {
        val code = navCode()
        // 牌组名仍随导航参数传入（页内小标题与深链要用），只是不再当顶栏标题
        assertTrue(
            "牌组名仍从导航参数 ARG_DECK_NAME 取（页内小标题/深链依赖它）",
            code.contains("ARG_DECK_NAME"),
        )
    }

    // ---- ④ 硬约束：页内小标题不许跟着右移（那两个文件本任务禁改）----

    @Test
    fun `page-level section titles keep their own 16dp inset and are not moved by the topbar`() {
        val subpages = File(SRC_ROOT + "ui/screens/my/MySubpages.kt").readText()
        val line = subpages.lines().firstOrNull { it.contains("val MyRowHorizontalPadding") }
        assertTrue("MyRowHorizontalPadding 定义还在（本任务不许改这个文件）", line != null)
        val rhs = line!!.substringAfter("=").trim()
        assertEquals("页内内距常量仍是 16dp（顶栏补箭头后下方小标题不许跟着右移）", 16f, rhs.substringBefore(".dp").trim().toFloatOrNull() ?: -1f, 0.001f)

        val decks = File(SRC_ROOT + "ui/screens/my/MyDecksPage.kt").readText()
        assertTrue("页内动作行仍自己 pad start（与顶栏箭头无关，未被动过）", decks.contains(".padding(start = MyRowHorizontalPadding)"))
    }

    // ---- helpers（口径照 DeckDetailTitleIndentTest）----

    private fun navCode(): String = fileCode(GIGI_NAV_HOST)

    private companion object {
        const val SRC_ROOT = "src/main/java/com/gigi/tcg/"
        const val GIGI_NAV_HOST = SRC_ROOT + "ui/navigation/GigiNavHost.kt"

        /** navigationIcon 槽从声明到闭合正好 10 行，截到此为止才不会把 actions 里的 IconButton 数进来。 */
        const val NAV_ICON_LINES = 10
    }

    /** 读源码并剔除注释行：断言只针对真实代码，注释改写不该让闸门变红。 */
    private fun fileCode(path: String): String {
        val file = File(path)
        assertTrue("源码文件不存在: ${file.absolutePath}", file.exists())
        return file.readText().lines()
            .filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }
            .joinToString("\n")
    }

    /** 取该 key 之后 N 行，用于断言同一个槽位内部的内容。 */
    private fun String.blockAfter(key: String, lines: Int = 25): String {
        val index = this.lines().indexOfFirst { it.contains(key) }
        if (index < 0) return ""
        return this.lines().subList(index, minOf(index + lines, this.lines().size)).joinToString("\n")
    }
}
