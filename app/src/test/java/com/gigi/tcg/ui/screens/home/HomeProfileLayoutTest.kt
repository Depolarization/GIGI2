// 首页个人信息卡 / 对局卡的版式口径锁（V28 立规，V36/3 任务 B 迁移到共享 PlayerInfoHeader，
// V37-AD 任务 B/C/D 按用户澄清口径重排）。
//
// V37 变更（🔴 本版断言与 V36 相反，逐条理由写在这里，防止"看起来像回退"）：
//   任务 B：昵称与段位**合并成单个 Text**（段位走 SpanStyle 行内染色，用户方案原话
//     「使用单个 textview，为段位部分设置 spannablestring+foregroundspan」）。
//     旧断言「昵称/段位两段 Text 都挂 alignByBaseline()」作废——单 Text 内基线由文本排版保证，
//     而且真机 bounds 实测段位 LEFT=467 vs UID LEFT=297（差 170px）证明"两个 Text 各自成块"
//     正是对不齐的结构性原因；合并后整行左缘 == UID 左缘。
//   任务 C：积分区从「缩进在头像右的文本列里」提升为与 PlayerInfoHeader **平级**的整行
//     （用户澄清：天梯/巅峰最左侧要和头像左缘对齐，头像 LEFT=88 vs 旧积分区 LEFT=297）。
//     旧断言「积分区 padding(start = 头像直径 + 列间距)」随之反转为「不得有 start padding」。
//   任务 D：对局卡天梯/巅峰两行共用列宽（width(IntrinsicSize.Max)，按内容实测不写死 dp）并居中
//     （旧 horizontalAlignment = Alignment.End 只右贴齐，实测两行 LEFT=700 / 843）。
//
// 保留的 V28 不变量（口径映射到新版式）：
//   1) 四层留白单调（组内 4dp、跨组 12dp，跨组 ≥3× 组内）；
//   2) UID 与「昵称+段位」同左缘（同一 Column 的同一 start）；
//   3) 字号层级 昵称行 > UID ≈ 表头，积分数值 > 昵称行；
//   4) 表头样式区别于数值（labelMedium + SemiBold + letterSpacing vs titleLarge + Bold）；
//   5) 卡片内不出现硬编码颜色；
//   6) 对局卡右缘组与昵称同基线。
// 另加「最近对局」标题行与刷新按钮的回归锁、对话框信息头同构锁。

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

    /**
     * 去掉 `//` 行注释的源码。**结构闸门一律跑在这上面**：本次重构的注释里大量引用被废弃的旧特征串
     * （`alignByBaseline()` / `PLAYER_INFO_NICK_TIER_GAP_DP` / `Alignment.End` 等，为了讲清"为什么反过来"），
     * 直接对原文断言"不得包含"会被注释自己判挂（V37-AD 实测踩过 4 次）。
     */
    private fun codeOnly(source: String): String =
        source.lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")

    /** 玩家详情弹窗源码（V37-3 任务 B：用户要求「同上重构」，与主页同构） */
    private val dialogSrc: String by lazy { readSource("ui", "dialogs", "playerdetail", "PlayerDetailDialog.kt") }

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

    /** 信息头里「昵称+段位」那一行的范围（截到 UID 行的 Spacer 之前） */
    private val nickLineChunk by lazy {
        val marker = "Spacer(ComposeModifier.height(PLAYER_INFO_UID_LINE_GAP_DP.dp))"
        headerBody.substring(0, headerBody.indexOf(marker))
    }

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

    /** 不变量 1b：头像↔文本列 ≥8dp，首页与统计页共用同一组常量 */
    @Test
    fun `头像与昵称段的横向间距落在 8 到 16dp`() {
        val columnGap = constDp(statsSrc, "PLAYER_INFO_TEXT_COLUMN_GAP_DP")
        assertTrue("头像↔文本列应 8~16dp，实际 $columnGap", columnGap in 8..16)
        assertTrue(headerBody.contains("padding(start = PLAYER_INFO_TEXT_COLUMN_GAP_DP.dp)"))
        assertTrue("头像直径应为 64dp", constDp(statsSrc, "PLAYER_INFO_AVATAR_DP") == 64)
        val statsCode = codeOnly(statsSrc)
        val homeCode = codeOnly(src)
        // V37-3 任务 B：昵称↔段位的 8dp Spacer 常量随"合并成单 Text"一起删除（只剩一个空格字符）
        assertFalse(
            "段位与昵称已合并成单个 Text，PLAYER_INFO_NICK_TIER_GAP_DP 的声明应删除",
            Regex("const val PLAYER_INFO_NICK_TIER_GAP_DP").containsMatchIn(statsCode),
        )
        // V37-3 任务 C：首页不再按「头像直径 + 列间距」缩进积分区，这两个常量在 HomeRoute 已无引用
        assertFalse("首页三段式后不再需要头像直径常量", homeCode.contains("PLAYER_INFO_AVATAR_DP"))
        assertFalse("首页三段式后不再需要列间距常量", homeCode.contains("PLAYER_INFO_TEXT_COLUMN_GAP_DP"))
        // V36/3 任务 B：首页不再自带平行的一套 PROFILE_AVATAR/TEXT_COLUMN/NICK_TIER/IDENTITY 常量
        listOf("PROFILE_AVATAR_SIZE_DP", "PROFILE_TEXT_COLUMN_GAP_DP", "PROFILE_NICK_TIER_GAP_DP", "PROFILE_IDENTITY_LINE_GAP_DP").forEach {
            assertFalse("首页版式常量已收敛到 PlayerInfoHeader，HomeRoute 不应残留 $it", homeCode.contains(it))
        }
    }

    /**
     * 不变量 2a（V37-3 任务 B 改版）：昵称与段位是**同一个 Text**，段位是行内一段染色 span。
     * 旧口径"两段 Text + alignByBaseline"作废：真机实测段位 LEFT=467、UID LEFT=297，
     * 分块才是错位的根因；合并后单 Text 内部基线由文本排版保证，整行左缘即 UID 左缘。
     */
    @Test
    fun `昵称与段位合并成单个 Text（段位走 SpanStyle 染色）`() {
        assertTrue("外层头像 Row 应垂直居中", nickLineChunk.contains("verticalAlignment = Alignment.CenterVertically"))
        assertFalse("V36 红线 1 的 Bottom+baseline 互斥形态不得出现", nickLineChunk.contains("Row(verticalAlignment = Alignment.Bottom)"))
        assertTrue("昵称+段位应为单个 Text + buildAnnotatedString", nickLineChunk.contains("text = buildAnnotatedString {"))
        assertTrue("段位部分用 withStyle 定向染色", nickLineChunk.contains("withStyle(tierSpan) { append(tier) }"))
        assertTrue("段位色来自 tierColor（不硬编码）", nickLineChunk.contains("SpanStyle(color = tierColor(tier))"))
        assertEquals("昵称行只剩一个 Text 节点（段位不再是第二个）", 1, Regex("Text\\(").findAll(nickLineChunk).count())
        assertFalse("合并后不再需要 alignByBaseline hack", nickLineChunk.contains("alignByBaseline()"))
        // 🔴 buildAnnotatedString 的 lambda 不是组合上下文，@Composable 的 tierColor 必须在体内先取好，
        // lambda 里只能用取好的 SpanStyle（写成 tierColor(tier) 会直接编译不过，这里做源码闸门）
        val lambdaChunk = nickLineChunk.substringAfter("buildAnnotatedString {")
            .substringBefore("modifier = ComposeModifier.fillMaxWidth()")
        assertFalse("lambda 内不得调 @Composable tierColor：\n$lambdaChunk", lambdaChunk.contains("tierColor("))
        // 段位为空 ⇒ 不渲染、也不留尾随空格（空格和 span 一起被 if 包住）
        assertTrue("空格只在有段位时才 append", lambdaChunk.contains("append(' ')"))
        assertTrue(lambdaChunk.contains("if (tierSpan != null)"))
    }

    /** 不变量 2b：UID 独占一行、左对齐于「昵称+段位」整行的左缘（禁止居中/右对齐/额外缩进） */
    @Test
    fun `UID 独占一行且左对齐于昵称左缘`() {
        val marker = "Spacer(ComposeModifier.height(PLAYER_INFO_UID_LINE_GAP_DP.dp))"
        val uidChunk = headerBody.substringAfter(marker)
        assertTrue("UID 段不应出现 TextAlign（居中/右对齐都是回归）", !uidChunk.contains("TextAlign"))
        assertTrue("UID 段不应自带横向 padding（左缘必须贴昵称左缘）", !uidChunk.contains("padding("))
        assertTrue("UID 用 onSurfaceVariant 弱化，不用自定义灰", uidChunk.contains("MaterialTheme.colorScheme.onSurfaceVariant"))
    }

    /**
     * 不变量 2c（V37-3 任务 C）：个人信息卡为**竖向三段**，天梯/巅峰两栏与 PlayerInfoHeader 平级、
     * 另起一整行 fillMaxWidth ⇒ 左缘 = 卡片内容左缘 = 头像左缘（用户澄清口径，实测旧值差 209px）。
     */
    @Test
    fun `积分区另起一整行且左缘等于头像左缘（竖向三段式）`() {
        val scoresRow = codeOnly(profileBody).substringAfter("Spacer(Modifier.height(PROFILE_SCORES_GAP_DP.dp))")
        assertTrue(
            "积分区应是 fillMaxWidth 的整行（参照 PlayerDetailDialog.ScoresRow），与 HeaderRow 平级",
            scoresRow.contains("Row(Modifier.fillMaxWidth())"),
        )
        assertFalse("积分区不得再缩进「头像直径 + 列间距」——用户点名要与头像左缘对齐", scoresRow.contains("padding(start"))
        // 左缘算式：卡片内边距就是头像与积分区共同的起点
        assertEquals("卡片内边距 16dp = 头像左缘 = 积分区左缘", 16, constDp(src, "PROFILE_CARD_PADDING_DP"))
        val headerCall = profileBody.substringAfter("PlayerInfoHeader(").substringBefore(")")
        assertFalse("信息头调用处不得补 start padding（否则头像左缘就不是内容左缘）", headerCall.contains("padding("))
        val weights = Regex("Modifier\\.weight\\(1f\\)").findAll(scoresRow).count()
        assertTrue("两列都应 weight(1f) 等宽，实际 $weights", weights == 2)
        assertTrue("天梯列取 semantic.win", scoresRow.contains("color = semantic.win"))
        assertTrue("巅峰列取 semantic.gold", scoresRow.contains("color = semantic.gold"))
        // 两栏**内部**维持原样：ScoreItem 是起始对位的 Column，不出现居中/右对齐（用户确认这点本来就是对的）
        assertTrue(src.contains("private fun ScoreItem("))
        assertFalse(scoreBody.contains("TextAlign"))
    }

    /** 不变量 3：昵称行（含段位 span）> UID ≈ 表头；积分数值是全卡最重的一档 */
    @Test
    fun `字号层级：昵称行大于UID与表头、积分数值最重`() {
        val nick = styleOf(headerBody, "text = buildAnnotatedString {")
        val uid = styleOf(headerBody, "uid,")
        val label = styleOf(scoreBody, "text = label,")
        val value = styleOf(scoreBody, "text = value.toString()")
        assertTrue("昵称行($nick ${sizeSp(nick)}sp) 应大于 UID($uid ${sizeSp(uid)}sp)", sizeSp(nick) > sizeSp(uid))
        assertTrue("UID($uid) 与表头($label) 同属最小档，应相等", sizeSp(uid) == sizeSp(label))
        assertTrue("数值($value ${sizeSp(value)}sp) 应明显大于表头", sizeSp(value) > sizeSp(label))
        assertTrue(
            "数值($value) 应 ≥ 昵称行($nick)——数值是全卡最重的一档",
            sizeSp(value) >= sizeSp(nick),
        )
        // V37-3 任务 B：段位与昵称同字号（旧结构段位是 titleSmall 14sp，合并进单 Text 后 span 只改色不改字号；
        // 同一行混排字号才是"看着不齐"的另一个来源，统一字号是这次方案的一部分）
        assertFalse("段位 span 不得覆写字号（只染色）", headerBody.contains("SpanStyle(fontSize"))
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
        assertTrue(
            "PD ScoreItem 应 spacedBy(4.dp) 与首页 SCORE_LABEL_VALUE_GAP_DP 同值",
            dialogSrc.contains("verticalArrangement = Arrangement.spacedBy(4.dp)"),
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

    /**
     * V37-3 任务 D（用户第 3 项「胜/负和天梯/巅峰积分变动的文本没有居中对齐」）：
     * 实测旧值 `天梯 2760 (10)` LEFT=700、`巅峰 -` LEFT=843（同右缘 931）⇒ 两行只右贴齐、左缘各浮一处。
     * 新口径：两行**共用同一个列宽**并在列内居中；列宽用 IntrinsicSize.Max 按内容实测，
     * 🔴 不写死 dp（长文案「天梯 2760 (+150)」与三语长短都要放得下）。
     */
    @Test
    fun `对局卡两行积分共用列宽且居中`() {
        val code = codeOnly(recordBody)
        assertTrue("积分列宽按内容实测（IntrinsicSize.Max = 本卡两行里较长那行的自然宽）", code.contains(".width(IntrinsicSize.Max)"))
        assertTrue("列内水平居中", code.contains("horizontalAlignment = Alignment.CenterHorizontally"))
        assertEquals("天梯/巅峰两行都要 textAlign = TextAlign.Center", 2, Regex("textAlign = TextAlign\\.Center").findAll(code).count())
        assertFalse("旧的 End 贴右（用户报的'没居中'就是它）不得回来", code.contains("horizontalAlignment = Alignment.End"))
        assertFalse("列宽不得改成写死 dp（三语长短不一，写死必截）", Regex("width\\(\\d+\\.dp\\)").containsMatchIn(code))
    }

    /**
     * V37-3 任务 B：玩家详情弹窗信息头与主页/统计页同构（用户「对话框中的段位和ID仍不对齐，建议同上重构」）。
     * 旧结构靠 widthIn(max = 88/148dp) 给段位让位，合并成单 Text 后省略号按整行可用宽截断，补丁作废。
     */
    @Test
    fun `玩家详情弹窗信息头同样合并成单个 Text`() {
        val body = codeOnly(bodyOf(dialogSrc, "private fun HeaderRow("))
        assertTrue(body.contains("text = buildAnnotatedString {"))
        assertTrue("段位色在 Composable 体内取好", body.contains("SpanStyle(color = tierColor(it))"))
        assertTrue(body.contains("withStyle(tierSpan) { append(tierText) }"))
        assertFalse("不再需要基线 hack", body.contains("alignByBaseline()"))
        assertFalse("88/148dp 昵称上限补丁随合并作废", body.contains("widthIn"))
        assertFalse("昵称↔段位 8dp Spacer 已删", body.contains("Spacer(Modifier.width(8.dp))"))
        assertTrue("文本列吃满剩余宽度", body.contains("Column(Modifier.weight(1f))"))
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
        // V36/3 任务 B：首页个人信息区照抄统计页 = ProfileCard 直接复用 PlayerInfoHeader
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
