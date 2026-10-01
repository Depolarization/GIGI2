// V39-H2 收口锁（G2 审计清单④ D-2「半成品收口」最典型的一处）：
// CardWikiRoute 筛选菜单选中项的 `MenuDefaults.itemColors(...)`。
//
// 病灶不是对比度、是**通道成对**：`itemColors` 的 textColor / leadingIconColor / trailingIconColor
// 是三个独立槽（M3 1.3.2 MenuItemColors 另有 disabled 三槽，但 DropdownMenuItem 没有 enabled 入参
// ⇒ 那三槽在本工程不可达，不在本闸门口径内）。
// 改前只填 textColor 一槽：现在这行没图标所以看不出问题，一旦有人加对勾，没填的两槽去吃 M3 默认值
// （leadingIcon→onSurfaceVariant、trailingIcon→primary）⇒「块换了、图标没换」，同一容器两条通道不同源。
// 改后三槽同值补齐（onSecondaryContainer × secondaryContainer，夜 7.19 / 白 13.24，改前后数值不变）。
//
// 本测试把「**要么三槽全给、要么一个都不给**」升成仓内规约：对 ui/ 全树做静态检查，
// 任何 itemColors(...) 只填了启用三槽中的一部分即判失败。
// 对比度口径复用 com.gigi.tcg.ui.theme.ContrastUtils。

package com.gigi.tcg.ui.screens.cardwiki

import com.gigi.tcg.ui.theme.ContrastUtils
import com.gigi.tcg.ui.theme.DarkColors
import com.gigi.tcg.ui.theme.LightColors
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CardWikiMenuColorsTest {

    private companion object {
        const val BODY_LEVEL = 4.5
        val EnabledSlots = listOf("textColor", "leadingIconColor", "trailingIconColor")

        /** 剥块注释 + `//` 单行注释（口径注释里会引用被禁的旧写法，不剥会误报） */
        fun stripComments(text: String): String =
            Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
                .replace(text, "")
                .lineSequence()
                .map { it.substringBefore("//") }
                .joinToString("\n")

        /**
         * 该槽在调用里显式给了值吗？取它吃的那个色槽名（形如 `textColor = MaterialTheme.colorScheme.X`）；
         * 负向回顾排除 `disabledTextColor` 里的 "textColor" 子串。
         */
        fun slotValue(args: String, slot: String): String? =
            Regex("""(?<![A-Za-z])$slot\s*=\s*MaterialTheme\.colorScheme\.([A-Za-z]+)""")
                .find(args)?.groupValues?.get(1)
    }

    /** 取 `MenuDefaults.itemColors(` 之后括号配平的那一段实参 */
    private fun itemColorsArguments(src: String): List<String> {
        val needle = "MenuDefaults.itemColors("
        val out = mutableListOf<String>()
        var from = 0
        while (true) {
            val hit = src.indexOf(needle, from)
            if (hit < 0) return out
            var i = hit + needle.length
            var depth = 1
            while (i < src.length && depth > 0) {
                when (src[i]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                i++
            }
            assertTrue("itemColors( 括号未配平", depth == 0)
            out += src.substring(hit + needle.length, i - 1)
            from = i
        }
    }

    /** 数值口径：onSecondaryContainer × secondaryContainer 两主题都过正文级（配对没被改坏） */
    @Test
    fun selectedItemText_meetsWcagAA_onSecondaryContainer() {
        listOf("夜" to DarkColors, "白" to LightColors).forEach { (label, scheme) ->
            val ratio = ContrastUtils.wcagContrast(scheme.onSecondaryContainer, scheme.secondaryContainer)
            assertTrue(
                "$label onSecondaryContainer/secondaryContainer = ${"%.2f".format(ratio)}:1, " +
                    "need >= $BODY_LEVEL",
                ratio >= BODY_LEVEL,
            )
        }
    }

    /** 选中项这一处必须三槽同值补齐（改前只给 textColor = 半成品） */
    @Test
    fun filterOption_fillsEveryEnabledSlot_fromTheSameContainer() {
        val file = File("src/main/java/com/gigi/tcg/ui/screens/cardwiki/CardWikiRoute.kt")
        assertTrue("源码文件不存在: ${file.absolutePath}", file.exists())
        val calls = itemColorsArguments(stripComments(file.readText()))
        assertTrue("CardWikiRoute 应有 itemColors 调用", calls.isNotEmpty())
        val selected = calls
            .map { args -> EnabledSlots.mapNotNull { slotValue(args, it) } }
            .firstOrNull { it.isNotEmpty() }
            ?: error("选中项分支必须给 textColor（改前只给这一槽，本轮补齐三槽）")
        assertTrue("三槽必须同时给，实际只给了 $selected", selected.size == EnabledSlots.size)
        assertTrue("三槽必须同值（同源同槽），实际取到 $selected",
            selected.distinct() == listOf("onSecondaryContainer"))
    }

    /**
     * 仓内规约（G2 D-2 收口）：ui/ 全树任何 `itemColors(` 调用，启用三槽要么全给、要么一个都不给。
     * 只填一半就是"块换了图标没换"的分叉伏笔。
     */
    @Test
    fun repoWide_noHalfFilledItemColorsCall() {
        val root = File("src/main/java/com/gigi/tcg/ui")
        assertTrue("源码目录不存在: ${root.absolutePath}", root.exists())
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                itemColorsArguments(stripComments(file.readText())).any { args ->
                    val given = EnabledSlots.count { slotValue(args, it) != null }
                    given in 1..(EnabledSlots.size - 1)
                }
            }
            .map { it.relativeTo(root).path }
            .toList()
        assertTrue("存在只填部分通道的 itemColors 调用（三槽必须同时给）: $offenders", offenders.isEmpty())
    }
}
