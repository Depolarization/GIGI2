// V7F 回归锁：排行榜行（RankRow）的"排名 ↔ 玩家信息"必须是跨语义组的 16dp 显式间距，
// 排名槽位必须固定 32dp 宽（否则间距随位数浮动、信息列左边缘错位），
// 头像 ↔ 信息列保持 12dp（同组层次）。历史缺陷：spacedBy(4.dp) + widthIn(min=28.dp)
// 导致排名数字与玩家信息粘连、"排名和ID混淆"（用户真机反馈）。
// 本测试对真实源码做文本断言，防止后人把间距改回去。
package com.gigi.tcg.ui.screens.rank

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RankRowSpacingTest {

    private val src: String by lazy {
        val file = File("src/main/java/com/gigi/tcg/ui/screens/rank/RankRoute.kt")
        assertTrue("源码文件不存在: ${file.absolutePath}", file.exists())
        file.readText()
    }

    /** RankRow 函数体区间：从签名到列 0 的第一个 "\n}"（体内闭括号均有缩进，不会误截断） */
    private fun rankRowBody(): String {
        val start = src.indexOf("private fun RankRow(")
        assertTrue("找不到 RankRow 函数", start >= 0)
        val end = src.indexOf("\n}", start)
        assertTrue("RankRow 函数体未闭合", end > start)
        return src.substring(start, end)
    }

    /** 不变量 1：排名与玩家信息之间不再用全 Row 统一的 spacedBy 小间距 */
    @Test
    fun rankRowHasNoUniformSpacedBy() {
        val body = rankRowBody()
        assertTrue(
            "RankRow 不应再出现 Arrangement.spacedBy(（4dp 统一间距导致排名与信息粘连）",
            !body.contains("Arrangement.spacedBy("),
        )
    }

    /** 不变量 2：排名 ↔ 头像用显式 16dp Spacer（= 行左 padding，跨语义组留白对称） */
    @Test
    fun rankToAvatarGapIsExplicit16dpSpacer() {
        val body = rankRowBody()
        assertTrue(
            "RankRow 应存在 Spacer(Modifier.width(16.dp))（排名↔头像的显式组间距）",
            body.contains("Spacer(Modifier.width(16.dp))"),
        )
    }

    /** 不变量 3：排名槽位固定 32dp，不再用会随位数浮动的 widthIn(min=28) */
    @Test
    fun rankSlotIsFixed32dp() {
        val body = rankRowBody()
        assertTrue(
            "排名槽位应为固定 Modifier.width(32.dp)",
            body.contains("Modifier.width(32.dp)"),
        )
        assertTrue(
            "不应再出现 widthIn(min = 28.dp)（间距随位数漂移、三位数撑破槽位）",
            !body.contains("widthIn(min = 28.dp)"),
        )
    }

    /** 不变量 4：头像 ↔ 信息列保持 12dp 同组层次（小于 16dp 组间距） */
    @Test
    fun avatarToInfoColumnKeeps12dpHierarchy() {
        val body = rankRowBody()
        assertTrue(
            "玩家信息列应为 padding(start = 12.dp)（同组内层次：12dp < 跨组 16dp）",
            body.contains("padding(start = 12.dp)"),
        )
    }
}
