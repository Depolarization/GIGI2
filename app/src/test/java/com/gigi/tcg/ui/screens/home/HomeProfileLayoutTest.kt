// 首页个人信息卡 / 对局卡的版式口径锁（V28 立规，V36/3 任务 B 迁移到共享 PlayerInfoHeader）。
//
// V36 变更：首页 ProfileCard 的「头像 + 昵称/段位 + UID」区照抄卡牌统计页版式并收敛为
// cardstats/PlayerInfoHeader（PLAYER_INFO_* 几何常量是单一事实源），本测试的断言随之
// 分读两个源码文件；🔴 昵称/段位所在 Row 必须**只靠 alignByBaseline()** 对齐——
// V36 红线 1 把 Row(verticalAlignment = Alignment.Bottom) + 子项 alignByBaseline() 定为缺陷模式
// （Bottom 会二次下压基线 parentData，小字号段位反而更低），原断言的 "Alignment.Bottom" 已反转。
//
// 保留的 V28 不变量（口径映射到新版式）：
//   1) 四层留白单调（组内 4dp、跨组 12dp，跨组 ≥3× 组内）；
//   2) 昵称/段位同行基线对齐（禁 Bottom）、UID 与积分表头共用一条竖向对齐轴（左对齐）；
//   3) 字号层级 昵称 > 段位 > UID ≈ 表头，数值 > 段位；
//   4) 表头样式区别于数值（labelMedium + SemiBold + letterSpacing vs titleLarge + Bold）；
//   5) 卡片内不出现硬编码颜色；
//   6) 对局卡右缘组（两行积分 + 胜负）与昵称同基线。
// 另加「最近对局」标题行与刷新按钮的回归锁。

package com.gigi.tcg.ui.screens.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeProfileLayoutTest {

    private fun readSource(vararg parts: String): String {
        val file = File(arrayOf("src", "main", "java", "com", "gigi", "tcg", *parts).joinToString("/"))
        assertTrue("源码文件不存在: ${file.absolutePath}", file.exists())
        return file.readText()
    }

    /** 首页源码（ProfileCard/ScoreItem/RecordItem 所在） */
    private val src: String by lazy { readSource("ui", "screens", "home", "HomeRoute.kt") }

    /** 共享信息头源码（V36/3 起 PlayerInfoHeader 与 PLAYER_INFO_* 常量所在） */
    private val statsSrc: String by lazy { readSource("ui", "screens", "cardstats", "CardStatsRoute.kt") }

    /** 取某个 Composable 的函数体：从签名到列 0 的第一个 "\n}"（体内闭括号均有缩进，不会误截断） */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("找不到 $signature", start >= 0)
        val end = source.indexOf("\n}", start)
        assertTrue("$signature 函数体未闭合", end > start)
        return source.substring(start, end)
    }

    /** 解析 `private/internal const val NAME = <Int>` 版式常量 */
    private fun constDp(source: String, name: String): Int {
        val m = Regex("""(?:private|internal) const val $name = (\d+)""").find(source)
        assertTrue("源码应把版式尺寸常量化为 `const val $name = <dp值>`", m != null)
        return m!!.groupValues[1].toInt()
    }

    /**
     * [marker]（某段 Text 的文本参数起始）之后第一个 `MaterialTheme.typography.X` 的 X。
     * Text 的参数顺序是 text → style，所以"标记后的第一个 style"就是这段 Text 自己的样式。
     */
    private fun styleOf(body: String, marker: String): String {
        val m = Regex("""style = MaterialTheme\.typography\.(\w+)""").find(body.substringAfter(marker))
        assertTrue("「$marker」之后应紧跟 style = MaterialTheme.typography.<样式>", m != null)
        return m!!.groupValues[1]
    }

    /** Material 3 默认字号（Type.kt 用无参 Typography()，故取 M3 基准值） */
    private fun sizeSp(style: String): Float = when (style) {
        "labelSmall" -> 11f
        "labelMedium", "labelLarge", "bodySmall" -> 12f
        "bodyMedium", "titleSmall" -> 14f
        "bodyLarge", "titleMedium" -> 16f
        "headlineSmall" -> 24f
        "titleLarge" -> 22f
        else -> error("未登记的 M3 样式 $style，请补进 sizeSp 映射")
    }

    private val headerBody by lazy { bodyOf(statsSrc, "internal fun PlayerInfoHeader(") }
    private val profileBody by lazy { bodyOf(src, "private fun ProfileCard(") }
    private val scoreBody by lazy { bodyOf(src, "private fun ScoreItem(") }
    private val recordBody by lazy { bodyOf(src, "private fun RecordItem(") }

    /** 不变量 1a：跨组留白（身份块→积分区）明显大于组内留白（昵称行→UID 行），四层节奏单调 */
    @Test
    fun `跨组留白明显大于组内留白（四层节奏单调）`() {
        val withinGroup = constDp(statsSrc, "PLAYER_INFO_UID_LINE_GAP_DP")
        val betweenGroups = constDp(src, "PROFILE_SCORES_GAP_DP")
        assertTrue("组内留白应在 2~8dp（太挤/太散），实际 $withinGroup", withinGroup in 2..8)
        assertTrue("跨组留白应在 8~20dp，实际 $betweenGroups", betweenGroups in 8..20)
        assertTrue(
            "跨组留白 $betweenGroups dp 应 ≥3× 组内留白 $withinGroup dp，否则四层糊成一片",
            betweenGroups >= 3 * withinGroup,
        )
        // 两处都必须是显式 Spacer 引用常量（不是靠行高余量"凑"出来的间距）。
        // 🔴 CardStatsRoute 把 Modifier 导入为 ComposeModifier，源码字面量按该别名书写
        assertTrue(headerBody.contains("Spacer(ComposeModifier.height(PLAYER_INFO_UID_LINE_GAP_DP.dp))"))
        assertTrue(profileBody.contains("Spacer(Modifier.height(PROFILE_SCORES_GAP_DP.dp))"))
    }

    /** 不变量 1b：头像↔文本列 ≥8dp、昵称↔段位 ≥8dp，但都不越过 16dp；首页与统计页共用同一组常量 */
    @Test
    fun `头像与昵称段的横向间距落在 8 到 16dp`() {
        val columnGap = constDp(statsSrc, "PLAYER_INFO_TEXT_COLUMN_GAP_DP")
        val nickTierGap = constDp(statsSrc, "PLAYER_INFO_NICK_TIER_GAP_DP")
        assertTrue("头像↔文本列应 8~16dp，实际 $columnGap", columnGap in 8..16)
        assertTrue("昵称↔段位应 8~16dp，实际 $nickTierGap", nickTierGap in 8..16)
        assertTrue("段位应紧跟 PLAYER_INFO_NICK_TIER_GAP_DP 间距", headerBody.contains("Spacer(ComposeModifier.width(PLAYER_INFO_NICK_TIER_GAP_DP.dp))"))
        assertTrue(headerBody.contains("padding(start = PLAYER_INFO_TEXT_COLUMN_GAP_DP.dp)"))
        assertTrue("头像直径应为 64dp", constDp(statsSrc, "PLAYER_INFO_AVATAR_DP") == 64)
        // V36/3 任务 B：首页不再自带平行的一套 PROFILE_AVATAR/TEXT_COLUMN/NICK_TIER/IDENTITY 常量
        listOf("PROFILE_AVATAR_SIZE_DP", "PROFILE_TEXT_COLUMN_GAP_DP", "PROFILE_NICK_TIER_GAP_DP", "PROFILE_IDENTITY_LINE_GAP_DP").forEach {
            assertFalse("首页版式常量已收敛到 PlayerInfoHeader，HomeRoute 不应残留 $it", src.contains(it))
        }
    }

    /** 不变量 2a（V36 反转）：昵称与段位同行且**只**按基线对齐，Row 禁设 Alignment.Bottom（红线 1） */
    @Test
    fun `昵称与段位同行且基线对齐（禁 Bottom 互斥形态）`() {
        // 截到 UID 行 Spacer 之前 = 头像 Row（含昵称/段位行）的范围
        val marker = "Spacer(ComposeModifier.height(PLAYER_INFO_UID_LINE_GAP_DP.dp))"
        val nickTierRow = headerBody.substring(0, headerBody.indexOf(marker))
        assertFalse(
            "V36 红线 1：Row(verticalAlignment = Alignment.Bottom) 与 alignByBaseline() 互斥，信息头不得出现 Bottom",
            nickTierRow.contains("Row(verticalAlignment = Alignment.Bottom)"),
        )
        // 头像行用 CenterVertically（整列在头像里垂直居中），昵称/段位所在 Row 不传 verticalAlignment
        assertTrue("外层头像 Row 应垂直居中", nickTierRow.contains("verticalAlignment = Alignment.CenterVertically"))
        val baselines = Regex("alignByBaseline\\(\\)").findAll(nickTierRow).count()
        assertTrue("昵称与段位两段 Text 都应挂 alignByBaseline()，实际 $baselines 处", baselines >= 2)
        assertTrue("段位色走 C 路 tierColor（不硬编码）", nickTierRow.contains("color = tierColor(tier)"))
    }

    /** 不变量 2b：UID 独占一行、左对齐于昵称左缘（禁止居中/右对齐/额外缩进） */
    @Test
    fun `UID 独占一行且左对齐于昵称左缘`() {
        val marker = "Spacer(ComposeModifier.height(PLAYER_INFO_UID_LINE_GAP_DP.dp))"
        val uidChunk = headerBody.substringAfter(marker)
        assertTrue("UID 段不应出现 TextAlign（居中/右对齐都是回归）", !uidChunk.contains("TextAlign"))
        assertTrue("UID 段不应自带横向 padding（左缘必须贴昵称左缘）", !uidChunk.contains("padding("))
        assertTrue("UID 用 onSurfaceVariant 弱化，不用自定义灰", uidChunk.contains("MaterialTheme.colorScheme.onSurfaceVariant"))
    }

    /** 不变量 2c：积分区两列等宽（各 weight(1f)），整块缩进对齐到昵称/UID 那条竖向轴（共享常量口径） */
    @Test
    fun `积分区等宽两列且左缘对齐昵称`() {
        val scoresRow = profileBody.substringAfter("Spacer(Modifier.height(PROFILE_SCORES_GAP_DP.dp))")
        assertTrue(
            "积分区应缩进「头像直径 + 列间距」（与 PlayerInfoHeader 同一组常量）以共用竖向对齐轴",
            scoresRow.contains("padding(start = (PLAYER_INFO_AVATAR_DP + PLAYER_INFO_TEXT_COLUMN_GAP_DP).dp)"),
        )
        val weights = Regex("Modifier\\.weight\\(1f\\)").findAll(scoresRow).count()
        assertTrue("两列都应 weight(1f) 等宽，实际 $weights", weights == 2)
        assertTrue("天梯列取 semantic.win", scoresRow.contains("color = semantic.win"))
        assertTrue("巅峰列取 semantic.gold", scoresRow.contains("color = semantic.gold"))
        // 用户第 2 项：两栏左对齐（ScoreItem 是起始对位的 Column，不出现 center/end 排布）
        val scoreItemSig = "private fun ScoreItem("
        assertTrue(src.contains(scoreItemSig))
    }

    /** 不变量 3：身份层内 昵称 > 段位 > UID ≈ 表头；积分数值是全卡最重的一档 */
    @Test
    fun `字号层级：身份层内昵称最大、积分数值最重`() {
        val nick = styleOf(headerBody, "nickname,")
        val tier = styleOf(headerBody, "tier,")
        val uid = styleOf(headerBody, "uid,")
        val label = styleOf(scoreBody, "text = label,")
        val value = styleOf(scoreBody, "text = value.toString()")
        assertTrue("昵称($nick ${sizeSp(nick)}sp) 应是最大的一档", sizeSp(nick) > sizeSp(tier))
        assertTrue(
            "段位($tier ${sizeSp(tier)}sp) 应大于 UID($uid ${sizeSp(uid)}sp) 与表头($label ${sizeSp(label)}sp)",
            sizeSp(tier) > sizeSp(uid) && sizeSp(tier) > sizeSp(label),
        )
        assertTrue("UID($uid) 与表头($label) 同属最小档，应相等", sizeSp(uid) == sizeSp(label))
        assertTrue("数值($value ${sizeSp(value)}sp) 应明显大于表头", sizeSp(value) > sizeSp(label))
        assertTrue(
            "数值($value) 应 ≥ 昵称($nick)——数值是全卡最重的一档，昵称只到 titleMedium",
            sizeSp(value) >= sizeSp(nick),
        )
    }

    /** 不变量 4：表头做成"小标题"（更小字号 + SemiBold + 拉开字距），与数值视觉分工明确 */
    @Test
    fun `积分表头样式区别于数值`() {
        assertTrue("表头应加粗到 SemiBold", scoreBody.contains("fontWeight = FontWeight.SemiBold"))
        assertTrue("表头应拉开字距", Regex("letterSpacing = \\d+(\\.\\d+)?\\.sp").containsMatchIn(scoreBody))
        assertTrue("数值仍是 titleLarge + Bold", scoreBody.contains("style = MaterialTheme.typography.titleLarge"))
        assertTrue(scoreBody.contains("fontWeight = FontWeight.Bold"))
        assertTrue(
            "表头↔数值间距走常量（两列共用同一节奏）",
            scoreBody.contains("verticalArrangement = Arrangement.spacedBy(SCORE_LABEL_VALUE_GAP_DP.dp)"),
        )
        val gap = constDp(src, "SCORE_LABEL_VALUE_GAP_DP")
        assertTrue("表头↔数值应 2~8dp，实际 $gap", gap in 2..8)
        assertEquals("表头↔数值与身份组内留白同值（同层同一节奏）", constDp(statsSrc, "PLAYER_INFO_UID_LINE_GAP_DP"), gap)
        // V36 任务 H：PlayerDetailDialog 的 ScoreItem 也补齐同一档间距（口径一致，不再是注释空头支票）
        val pdSrc = readSource("ui", "dialogs", "playerdetail", "PlayerDetailDialog.kt")
        assertTrue(
            "PD ScoreItem 应 spacedBy(4.dp) 与首页 SCORE_LABEL_VALUE_GAP_DP 同值",
            pdSrc.contains("verticalArrangement = Arrangement.spacedBy(4.dp)"),
        )
    }

    /** 不变量 5：卡片内零硬编码色 —— 深色模式全靠主题语义色 / tierColor */
    @Test
    fun `卡片内不出现硬编码颜色`() {
        listOf(headerBody, profileBody, scoreBody, recordBody).forEach { body ->
            val name = body.substringAfter("fun ").substringBefore("(")
            assertTrue("$name 不应出现 Color(0x 硬编码", !body.contains("Color(0x"))
            assertTrue("$name 不应出现 Color.valueOf / android.graphics.Color", !body.contains("valueOf"))
        }
    }

    /** 不变量 6：对局卡右缘组（两行积分 + 胜负）基线对齐到昵称行 */
    @Test
    fun `对局卡右缘组与昵称同基线`() {
        assertTrue("Row 应改为贴顶（垂直居中会把右缘组推到 UID 行）", recordBody.contains("verticalAlignment = Alignment.Top"))
        val baselines = Regex("alignByBaseline\\(\\)").findAll(recordBody).count()
        assertTrue("昵称列 / 积分列 / 胜负都应挂 alignByBaseline()，实际 $baselines 处", baselines >= 3)
        assertTrue("对局卡头像 56dp", constDp(src, "RECORD_AVATAR_SIZE_DP") == 56)
    }

    /** 回归锁：「最近对局」标题行 + 刷新按钮仍在（V29-B 移除导出按钮，刷新入口是首页唯一的对局区动作） */
    @Test
    fun `首页最近对局标题行与刷新按钮仍在`() {
        val start = src.indexOf("fun HomeRoute(")
        // 结束点用 ProfileCard 的签名（🔴 不能跨行匹配 "@Composable\nprivate fun"——
        // 源码在 Windows 上可能是 CRLF，含 \n 的字面量会整条断言失效）
        val end = src.indexOf("private fun ProfileCard(")
        assertTrue("HomeRoute 函数体定位失败", start >= 0 && end > start)
        val home = src.substring(start, end)
        assertTrue("「最近对局」标题必须保留", home.contains("R.string.home_recent_games"))
        assertTrue("刷新按钮必须保留", home.contains("Icons.Outlined.Refresh"))
        assertTrue("刷新按钮仍接 viewModel::refresh", home.contains("onClick = viewModel::refresh"))
        // V36 任务 B：首页个人信息区照抄统计页 = ProfileCard 直接复用 PlayerInfoHeader
        // （断言落在 ProfileCard 体内：HomeRoute 段止于 ProfileCard 签名，不含卡片实现）
        assertTrue("首页资料卡应复用共享 PlayerInfoHeader", profileBody.contains("PlayerInfoHeader("))
    }

    /** V29-B：图片导出的源码与测试都不应再回来（列表展示保留、导出能力移除） */
    @Test
    fun `最近对局图片导出的实现与测试已不存在`() {
        listOf(
            "src/main/java/com/gigi/tcg/ui/screens/home/RecentRecordsExport.kt",
            "src/main/java/com/gigi/tcg/ui/export/RecentRecordsCardRenderer.kt",
            "src/test/java/com/gigi/tcg/ui/screens/home/RecentRecordsExportTest.kt",
        ).forEach { path ->
            assertTrue("V29-B 已移除导出能力，$path 不应存在", !File(path).exists())
        }
    }
}
