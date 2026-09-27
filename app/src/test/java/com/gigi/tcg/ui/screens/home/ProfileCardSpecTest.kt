// 个人信息卡规格（V27 段位文本口径 + V28 版式口径）。
//
// 一、段位文本：tierDisplayText 必须与 ui/components/tierLabel（StateViews，组合上下文版）
// 逐字同口径——段位名走当前语言资源、★ 星缀同 domain formatTier（0 星不显示）、
// 无段位画 home_tier_none。纯 JVM 无 resolver 时断中文默认值
// （🔴 与 values/strings.xml 同 id 文案逐字一致）；注入 resolver 验证三语通道。
//
// 二、V28 版式：首页 ProfileCard/ScoreItem/RecordItem 的留白节奏、对齐轴、字号层级
// 以导出图渲染器 RecentRecordsCardRenderer 的几何为参照标准。工程无 Robolectric/Compose UI 测试，
// 故沿用 RankRowSpacingTest 的口径——对真实源码做文本断言，锁死用户拍板的六条不变量：
//   1) 四层留白单调（组内 4dp、跨组 12dp，跨组 ≥3× 组内）；
//   2) 昵称/段位同行基线对齐、UID 与积分表头共用一条竖向对齐轴（左对齐，无居中/右对齐）；
//   3) 字号层级 昵称 > 段位 > UID ≈ 表头，数值 > 段位（层级靠字号+字重，颜色只做辅助）；
//   4) 表头样式区别于数值（labelMedium + SemiBold + letterSpacing vs titleLarge + Bold）；
//   5) 卡片内不出现硬编码颜色（深色模式一律走主题语义色 / tierColor）；
//   6) 对局卡右缘组（两行积分 + 胜负）与昵称同基线（导出图「同顶」口径）。
// 另加「最近对局」标题行与导出/刷新两个按钮的回归锁（上轮曾误删，导出与刷新入口依赖它们）。

package com.gigi.tcg.ui.screens.home

import com.gigi.tcg.R
import com.gigi.tcg.data.model.PageInfo
import com.gigi.tcg.domain.TierStars
import com.gigi.tcg.i18n.LocaleStrings
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCardSpecTest {

    @After
    fun tearDown() {
        LocaleStrings.installResolverForTest(null)
    }

    private fun tierTextOf(score: Int): String =
        buildProfileCardSpec(PageInfo(ladderScore = score), "1", 0).tierText

    @Test
    fun `段位文本等于 tierLabel getTierStars 口径（多处分数抽查）`() {
        assertEquals("无段位", tierTextOf(0))          // getTierStars(score<1)→无段位
        assertEquals("黄铜★", tierTextOf(1))           // 下边界
        assertEquals("黄铜★", tierTextOf(1199))        // <1200 仍是黄铜一星
        assertEquals("黄铜★★", tierTextOf(1200))       // 1200 落入下一档（<1400 黄铜二星）
        assertEquals("黄铜★★★★★", tierTextOf(1999))    // <2000 黄铜五星
        assertEquals("星银★", tierTextOf(2050))        // <2100 星银一星
        assertEquals("星银★★", tierTextOf(2100))
        assertEquals("赤金★", tierTextOf(2550))        // <2600 赤金一星
        assertEquals("赤金★★", tierTextOf(2600))
        assertEquals("赤金★★★", tierTextOf(2760))
        assertEquals("影幻", tierTextOf(3000))         // ≥3000，0 星不带 ★
        assertEquals("影幻", tierTextOf(9999))
    }

    @Test
    fun `段位名按当前语言资源映射（resolver 注入即三语通道生效）`() {
        LocaleStrings.installResolverForTest { id ->
            when (id) {
                R.string.tier_silver -> "Silver"
                R.string.home_tier_none -> "Unranked"
                else -> null
            }
        }
        assertEquals("Silver★", tierTextOf(2050))
        assertEquals("Unranked", tierTextOf(0))
    }

    @Test
    fun `未知段位名回落原始值本身（不显示成空白）`() {
        // tierDisplayText 的映射表只认四档；domain 若新增段位，旧客户端也要能读出来
        assertEquals("钻石★★", tierDisplayText(TierStars("钻石", 2)))
        assertEquals("钻石", tierDisplayText(TierStars("钻石", 0)))
    }

    // ---- V28 版式口径 ----

    private val src: String by lazy {
        val file = File("src/main/java/com/gigi/tcg/ui/screens/home/HomeRoute.kt")
        assertTrue("源码文件不存在: ${file.absolutePath}", file.exists())
        file.readText()
    }

    /** 取某个私有 Composable 的函数体：从签名到列 0 的第一个 "\n}"（体内闭括号均有缩进，不会误截断） */
    private fun bodyOf(funName: String): String {
        val start = src.indexOf("private fun $funName(")
        assertTrue("找不到 $funName", start >= 0)
        val end = src.indexOf("\n}", start)
        assertTrue("$funName 函数体未闭合", end > start)
        return src.substring(start, end)
    }

    /** 解析文件顶部的 `private const val NAME = <Int>` 版式常量 */
    private fun constDp(name: String): Int {
        val m = Regex("""private const val $name = (\d+)""").find(src)
        assertTrue("源码应把版式尺寸常量化为 `private const val $name = <dp值>`", m != null)
        return m!!.groupValues[1].toInt()
    }

    /**
     * [marker]（某段 Text 的 text= 起始）之后第一个 `MaterialTheme.typography.X` 的 X。
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

    private val profileBody by lazy { bodyOf("ProfileCard") }
    private val scoreBody by lazy { bodyOf("ScoreItem") }
    private val recordBody by lazy { bodyOf("RecordItem") }

    /** 不变量 1a：跨组留白（身份块→积分区）明显大于组内留白（昵称→UID），四层节奏单调 */
    @Test
    fun `跨组留白明显大于组内留白（四层节奏单调）`() {
        val withinGroup = constDp("PROFILE_IDENTITY_LINE_GAP_DP")
        val betweenGroups = constDp("PROFILE_SCORES_GAP_DP")
        assertTrue("组内留白应在 2~8dp（太挤/太散），实际 $withinGroup", withinGroup in 2..8)
        assertTrue("跨组留白应在 8~20dp，实际 $betweenGroups", betweenGroups in 8..20)
        assertTrue(
            "跨组留白 $betweenGroups dp 应 ≥3× 组内留白 $withinGroup dp，否则四层糊成一片",
            betweenGroups >= 3 * withinGroup,
        )
        // 两处都必须是显式 Spacer 引用常量（不是靠行高余量"凑"出来的间距）
        assertTrue(profileBody.contains("Spacer(Modifier.height(PROFILE_IDENTITY_LINE_GAP_DP.dp))"))
        assertTrue(profileBody.contains("Spacer(Modifier.height(PROFILE_SCORES_GAP_DP.dp))"))
    }

    /** 不变量 1b：头像↔文本列 ≥8dp、昵称↔段位 ≥8dp，但都不越过 16dp（避免卡片空得散架） */
    @Test
    fun `头像与昵称段的横向间距落在 8 到 16dp`() {
        val columnGap = constDp("PROFILE_TEXT_COLUMN_GAP_DP")
        val nickTierGap = constDp("PROFILE_NICK_TIER_GAP_DP")
        assertTrue("头像↔文本列应 8~16dp，实际 $columnGap", columnGap in 8..16)
        assertTrue("昵称↔段位应 8~16dp，实际 $nickTierGap", nickTierGap in 8..16)
        assertTrue(profileBody.contains("Spacer(Modifier.width(PROFILE_NICK_TIER_GAP_DP.dp))"))
        assertTrue(profileBody.contains("padding(start = PROFILE_TEXT_COLUMN_GAP_DP.dp)"))
        assertTrue("头像直径应为 64dp（导出图 PROFILE_AVATAR_PX = 64×3）", constDp("PROFILE_AVATAR_SIZE_DP") == 64)
    }

    /** 不变量 2a：昵称与段位同行且基线对齐（字号不同，顶端对齐会看着不齐） */
    @Test
    fun `昵称与段位同行且基线对齐`() {
        val nickTierRow = profileBody.substringAfter("Row(verticalAlignment = Alignment.Bottom)")
            .substringBefore("Spacer(Modifier.height(PROFILE_IDENTITY_LINE_GAP_DP.dp))")
        val baselines = Regex("alignByBaseline\\(\\)").findAll(nickTierRow).count()
        assertTrue("昵称与段位两段 Text 都应挂 alignByBaseline()，实际 $baselines 处", baselines >= 2)
        assertTrue("段位应紧跟 8dp 间距", nickTierRow.contains("Spacer(Modifier.width(PROFILE_NICK_TIER_GAP_DP.dp))"))
        assertTrue("段位色走 C 路 tierColor（不硬编码）", nickTierRow.contains("color = tierColor(tier)"))
    }

    /** 不变量 2b：UID 独占一行、左对齐于昵称左缘（禁止居中/右对齐） */
    @Test
    fun `UID 独占一行且左对齐于昵称左缘`() {
        val uidChunk = profileBody
            .substringAfter("text = uid,")
            .substringBefore("Spacer(Modifier.height(PROFILE_SCORES_GAP_DP.dp))")
        assertTrue("UID 段不应出现 TextAlign（居中/右对齐都是回归）", !uidChunk.contains("TextAlign"))
        assertTrue("UID 段不应自带横向 padding（左缘必须贴昵称左缘）", !uidChunk.contains("padding("))
        assertTrue("UID 用 onSurfaceVariant 弱化，不用自定义灰", uidChunk.contains("MaterialTheme.colorScheme.onSurfaceVariant"))
    }

    /** 不变量 2c：积分区两列等宽（各 weight(1f)），整块缩进对齐到昵称/UID 那条竖向轴 */
    @Test
    fun `积分区等宽两列且左缘对齐昵称`() {
        val scoresRow = profileBody.substringAfter("Spacer(Modifier.height(PROFILE_SCORES_GAP_DP.dp))")
        assertTrue(
            "积分区应缩进「头像直径 + 列间距」以共用竖向对齐轴（导出图同一个 colLeft）",
            scoresRow.contains("padding(start = (PROFILE_AVATAR_SIZE_DP + PROFILE_TEXT_COLUMN_GAP_DP).dp)"),
        )
        val weights = Regex("Modifier\\.weight\\(1f\\)").findAll(scoresRow).count()
        assertTrue("两列都应 weight(1f) 等宽，实际 $weights", weights == 2)
        assertTrue("天梯列取 semantic.win", scoresRow.contains("color = semantic.win"))
        assertTrue("巅峰列取 semantic.gold", scoresRow.contains("color = semantic.gold"))
    }

    /** 不变量 3：身份层内 昵称 > 段位 > UID ≈ 表头；积分数值是全卡最重的一档 */
    @Test
    fun `字号层级：身份层内昵称最大、积分数值最重`() {
        val nick = styleOf(profileBody, "text = profile.nickname")
        val tier = styleOf(profileBody, "text = tier,")
        val uid = styleOf(profileBody, "text = uid,")
        val label = styleOf(scoreBody, "text = label,")
        val value = styleOf(scoreBody, "text = value.toString()")
        assertTrue("昵称($nick ${sizeSp(nick)}sp) 应是最大的一档", sizeSp(nick) > sizeSp(tier))
        assertTrue(
            "段位($tier ${sizeSp(tier)}sp) 应大于 UID($uid ${sizeSp(uid)}sp) 与表头($label ${sizeSp(label)}sp)",
            sizeSp(tier) > sizeSp(uid) && sizeSp(tier) > sizeSp(label),
        )
        assertTrue("UID($uid) 与表头($label) 同属最小档，应相等", sizeSp(uid) == sizeSp(label))
        assertTrue(
            "数值($value ${sizeSp(value)}sp) 应明显大于表头",
            sizeSp(value) > sizeSp(label),
        )
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
        val gap = constDp("SCORE_LABEL_VALUE_GAP_DP")
        assertTrue("表头↔数值应 2~8dp，实际 $gap", gap in 2..8)
        assertEquals("表头↔数值与组内留白同值（同层同一节奏）", constDp("PROFILE_IDENTITY_LINE_GAP_DP"), gap)
    }

    /** 不变量 5：卡片内零硬编码色 —— 深色模式全靠主题语义色 / tierColor */
    @Test
    fun `卡片内不出现硬编码颜色`() {
        listOf(profileBody, scoreBody, recordBody).forEach { body ->
            val name = body.substringAfter("private fun ").substringBefore("(")
            assertTrue("$name 不应出现 Color(0x 硬编码", !body.contains("Color(0x"))
            assertTrue("$name 不应出现 Color.valueOf / android.graphics.Color", !body.contains("valueOf"))
        }
    }

    /** 不变量 6：对局卡右缘组（两行积分 + 胜负）基线对齐到昵称行（导出图「同顶」口径） */
    @Test
    fun `对局卡右缘组与昵称同基线`() {
        assertTrue("Row 应改为贴顶（垂直居中会把右缘组推到 UID 行）", recordBody.contains("verticalAlignment = Alignment.Top"))
        val baselines = Regex("alignByBaseline\\(\\)").findAll(recordBody).count()
        assertTrue("昵称列 / 积分列 / 胜负都应挂 alignByBaseline()，实际 $baselines 处", baselines >= 3)
        assertTrue("对局卡头像 56dp（导出图 AVATAR_SIZE_PX = 56×3）", constDp("RECORD_AVATAR_SIZE_DP") == 56)
    }

    /** 回归锁：「最近对局」标题行 + 导出/刷新两个按钮（上轮误删过，导出与刷新入口依赖它们） */
    @Test
    fun `首页最近对局标题行与两个按钮仍在`() {
        val start = src.indexOf("fun HomeRoute(")
        // 结束点用 ProfileCard 的签名（🔴 不能跨行匹配 "@Composable\nprivate fun"——
        // 源码在 Windows 上可能是 CRLF，含 \n 的字面量会整条断言失效）
        val end = src.indexOf("private fun ProfileCard(")
        assertTrue("HomeRoute 函数体定位失败", start >= 0 && end > start)
        val home = src.substring(start, end)
        assertTrue("「最近对局」标题必须保留", home.contains("R.string.home_recent_games"))
        assertTrue("导出按钮必须保留", home.contains("Icons.Outlined.Download"))
        assertTrue("刷新按钮必须保留", home.contains("Icons.Outlined.Refresh"))
        assertTrue("刷新按钮仍接 viewModel::refresh", home.contains("onClick = viewModel::refresh"))
        assertTrue("导出按钮仍接 startExport", home.contains("onClick = startExport"))
        assertTrue("导出按钮的禁用条件不变（导出中 / 空列表）", home.contains("enabled = !exporting && recordList.isNotEmpty()"))
    }
}
