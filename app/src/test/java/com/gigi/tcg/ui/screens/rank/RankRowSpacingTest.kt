// V27 回归锁：排行榜行（RankRow）行内间距以文件顶部 private const val 常量为准，
// 本测试对真实源码做文本断言，锁死三条用户拍板的层次关系，防止后人改回去：
//   1) 头像 → 信息列 ≥ 12dp（跨语义组要拉开，V27 现值 16dp）；
//   2) 名次列左右边距各 4~8dp（贴左缘、收空隙，但 ≥4dp 不与头像粘连）；
//   3) 名次槽固定宽度且 ≥ 4 位数在 titleMedium 下的估宽（16sp × 0.6 × 4 ≈ 38.4dp），
//      杜绝 wrapContent 随位数抖动 / 千名级 4 位数换行省略。
// 历史缺陷：V7F 前 spacedBy(4.dp) 粘连；V27 前槽宽 32dp 对 4 位数已经偏紧。
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

    /** 解析文件顶部的 `private const val NAME = <Int>` 间距常量 */
    private fun constDp(name: String): Int {
        val m = Regex("""private const val $name = (\d+)""").find(src)
        assertTrue("源码应把间距常量化为 `private const val $name = <dp值>`", m != null)
        return m!!.groupValues[1].toInt()
    }

    /** titleMedium 字号（sp，Material 3 默认值）：4 位数估宽 = 16 × 0.6 × 4 的依据 */
    private val titleMediumSp: Float = 16f

    /** 不变量 1：不再用全 Row 统一的 spacedBy 小间距 */
    @Test
    fun rankRowHasNoUniformSpacedBy() {
        val body = rankRowBody()
        assertTrue(
            "RankRow 不应再出现 Arrangement.spacedBy(（统一小间距导致排名与信息粘连）",
            !body.contains("Arrangement.spacedBy("),
        )
    }

    /** 不变量 2：头像 → 信息列间距 ≥ 12dp（V27 由 12 提到 16，拉开跨语义组层次） */
    @Test
    fun avatarToInfoGapAtLeast12dp() {
        val gap = constDp("AVATAR_INFO_GAP_DP")
        assertTrue("头像↔信息列应 ≥12dp，实际 $gap", gap >= 12)
        val body = rankRowBody()
        assertTrue(
            "信息列应引用常量 padding(start = AVATAR_INFO_GAP_DP.dp)",
            body.contains("padding(start = AVATAR_INFO_GAP_DP.dp)"),
        )
    }

    /** 不变量 3：名次列左右边距各 ≤8dp 且 ≥4dp（贴左缘收空隙、但不与头像粘连） */
    @Test
    fun rankColumnMarginsWithinFourToEightDp() {
        val start = constDp("RANK_COLUMN_START_DP")
        val gap = constDp("RANK_AVATAR_GAP_DP")
        assertTrue("名次列左侧距列表左缘应 ≤8dp，实际 $start", start <= 8)
        assertTrue("名次列左侧不应 <4dp（数字顶到屏幕边），实际 $start", start >= 4)
        assertTrue("名次↔头像间距应 ≤8dp，实际 $gap", gap <= 8)
        assertTrue("名次↔头像间距不应 <4dp（与头像粘连），实际 $gap", gap >= 4)
    }

    /** 不变量 4：名次槽固定宽度且容纳 4 位数（估宽 = 字号 × 0.6 × 4，见文件头注释） */
    @Test
    fun rankSlotFixedWidthFitsFourDigits() {
        val slot = constDp("RANK_SLOT_WIDTH_DP")
        val fourDigitEstimateDp = titleMediumSp * 0.6f * 4f
        assertTrue(
            "名次槽宽 $slot dp 应 ≥ 4 位数估宽 ${fourDigitEstimateDp}f dp（16sp×0.6×4），否则千名级换行/省略",
            slot >= fourDigitEstimateDp,
        )
        val body = rankRowBody()
        assertTrue(
            "名次槽应为固定 Modifier.width(RANK_SLOT_WIDTH_DP.dp)",
            body.contains("Modifier.width(RANK_SLOT_WIDTH_DP.dp)"),
        )
        assertTrue(
            "不应再出现 widthIn(min = 28.dp)（间距随位数漂移、三位数撑破槽位）",
            !body.contains("widthIn(min = 28.dp)"),
        )
        assertTrue(
            "名次文本应 maxLines = 1（固定槽宽下禁止换行）",
            body.contains("maxLines = 1"),
        )
    }

    /** 不变量 5：名次↔头像用显式 Spacer 引用常量（非硬编码，便于统一调整） */
    @Test
    fun rankToAvatarGapUsesExplicitSpacer() {
        val body = rankRowBody()
        assertTrue(
            "RankRow 应存在 Spacer(Modifier.width(RANK_AVATAR_GAP_DP.dp))（名次↔头像显式间距）",
            body.contains("Spacer(Modifier.width(RANK_AVATAR_GAP_DP.dp))"),
        )
    }
}
