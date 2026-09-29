// V38-A：卡组详情页「行动牌张数」判据 + 源码闸门。
// 🔴 钉死的实测事实（真机 gcg/deckList，11 副牌组；出处 .task/v36-probe/basicinfo/raw/ctrl-deckList.json 与 -2nd.json）：
//   行动牌 num 会出现 2（同一张行动牌携带 2 份）；角色牌 num 实测都是 1。
//   V37-F 曾把「角色牌 num 唯一」错误推广成「num 恒为 1」并整个删掉张数 ⇒ 行动牌带 2 份时信息丢失（本轮 bug）。
// 本类只做字符串与算术，不 import android.graphics / Compose（工程铁律）。
package com.gigi.tcg.ui.screens.my

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyDeckCardTileTest {

    // ---- 纯判据：什么时候画张数 ----

    @Test
    fun `count badge only appears when num greater than 1`() {
        assertFalse(shouldShowCardCount(null))
        assertFalse(shouldShowCardCount(0))
        assertFalse(shouldShowCardCount(1))
        assertTrue(shouldShowCardCount(2))
        assertTrue(shouldShowCardCount(3))
    }

    @Test
    fun `negative num is treated as missing instead of drawing a nonsense badge`() {
        assertFalse(shouldShowCardCount(-1))
        assertNull(cardCountLabel(-1, "×"))
    }

    @Test
    fun `label is null when hidden and prefix-plus-number when shown`() {
        assertNull(cardCountLabel(null, "×"))
        assertNull(cardCountLabel(1, "×"))
        assertEquals("×2", cardCountLabel(2, "×"))
        assertEquals("×24", cardCountLabel(24, "×"))
    }

    @Test
    fun `number goes after the localized prefix, never hardcoded in code`() {
        // 前缀来自 stringResource：换语言只换这一串（en/zh-rTW 同一形态，避开英文复数变形）
        assertEquals("2", cardCountLabel(2, ""))
        assertEquals("x2", cardCountLabel(2, "x"))
    }

    // ---- 实测数据回归：一副牌组里到底该画出几个徽标 ----

    @Test
    fun `measured action-card num arrays do contain 2 so badges are non-empty`() {
        // 实测两副牌组的 action_cards.num（原样抄自 raw JSON）
        val deck1 = listOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 1, 1, 2, 1, 2, 2, 2)
        val deck2 = listOf(1, 1, 1, 2, 1, 1, 1, 2, 2, 2, 1, 2, 1, 1, 1, 1, 1, 1, 2, 1, 1, 1, 1, 1)
        assertEquals(8, deck1.count { shouldShowCardCount(it) })
        assertEquals(6, deck2.count { shouldShowCardCount(it) })
        // 11 副牌组逐副的徽标数（原样抄自 raw JSON 复算结果，合计 70）：
        // 删掉张数就是把这 70 个「这张牌带了几份」的信息点全丢了
        val perDeck = listOf(8, 6, 6, 6, 6, 7, 6, 5, 6, 6, 8)
        assertEquals(70, perDeck.sum())
        // 角色牌：11 副 × 3 张实测全部 num == 1 ⇒ 一张都不画
        assertEquals(0, listOf(1, 1, 1).count { shouldShowCardCount(it) })
    }

    // ---- 源码闸门：调用点必须按分组类型传对 showCount ----

    @Test
    fun `avatar group passes showCount false and action group passes showCount true`() {
        val src = myDecksSource()
        val avatarCall = src.lineContaining("my_deck_card_group_avatar") +
            src.blockAfter("my_deck_card_group_avatar")
        val actionCall = src.lineContaining("my_deck_card_group_action") +
            src.blockAfter("my_deck_card_group_action")
        assertTrue("角色牌组调用点必须不画张数：$avatarCall", avatarCall.contains("showCount = false"))
        assertFalse("角色牌组调用点不得画张数：$avatarCall", avatarCall.contains("showCount = true"))
        assertTrue("行动牌组调用点必须画张数：$actionCall", actionCall.contains("showCount = true"))
        assertFalse("行动牌组调用点不得关掉张数：$actionCall", actionCall.contains("showCount = false"))
    }

    @Test
    fun `card group forwards showCount down to the tile instead of dropping it`() {
        val src = myDecksSource()
        assertTrue(src.contains("private fun CardGroup(title: String, cards: List<GcgDeckCard>, showCount: Boolean)"))
        assertTrue(src.contains("DeckCardTile(card = card, showCount = showCount)"))
        assertTrue(src.contains("private fun DeckCardTile(card: GcgDeckCard, showCount: Boolean)"))
    }

    @Test
    fun `wrong kdoc claiming num is always 1 stays deleted`() {
        // 这条注释是本轮 bug 的根源，留着等于给下一个读代码的人埋雷
        val src = myDecksSource()
        assertFalse(src.contains("恒为 1"))
        assertFalse(src.contains("恒为1"))
        assertFalse(src.contains("不再显示张数"))
    }

    @Test
    fun `name text is centered and the badge sticks to the card face top-start`() {
        val src = myDecksSource()
        assertTrue("牌名需要 textAlign 居中", src.contains("textAlign = TextAlign.Center"))
        // textAlign 只在 Text 自己铺满时才生效
        assertTrue(src.contains("contentAlignment = Alignment.Center"))
        assertTrue(src.contains(".align(Alignment.TopStart)"))
    }

    @Test
    fun `count prefix string exists in all three locales`() {
        for (dir in listOf("values", "values-zh-rTW", "values-en")) {
            val text = File("src/main/res/$dir/strings.xml").readText()
            assertTrue("$dir 缺 my_deck_card_count_prefix", text.contains("name=\"my_deck_card_count_prefix\""))
        }
    }

    private fun myDecksSource(): String =
        File("src/main/java/com/gigi/tcg/ui/screens/my/MyDecksPage.kt").readText()

    private fun String.lineContaining(key: String): String {
        val line = lines().firstOrNull { it.contains(key) }
        return line ?: ""
    }

    /** 取该 key 之后若干行：CardGroup 调用点参数换行后要覆盖到 showCount 那一行。 */
    private fun String.blockAfter(key: String, lines: Int = 5): String {
        val index = this.lines().indexOfFirst { it.contains(key) }
        if (index < 0) return ""
        return this.lines().subList(index, minOf(index + lines, this.lines().size)).joinToString("\n")
    }
}
