// 首页个人信息卡 / 对局卡的版式口径锁（V28 立规，V36/3 任务 B 迁移到共享 PlayerInfoHeader，
// V37-AD 任务 B/C/D 按用户澄清口径重排，V40-C 对局卡反转 V28 基线口径改垂直居中 + 头像 44dp）。
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
// V41 变更（🔴 V37-3 任务 B 的「合并成单个 Text」在本文件**全线作废**，弹窗与共享头两处都改）：
//   玩家反馈「玩家名字太长时显示不全段位」。根因是单 Text + maxLines=1 ⇒ 昵称与段位共用一个省略号，
//   昵称一长段位就被截掉（V37-3 注释里预判过这个取舍，但"实测未触发"的判断是错的）。
//   改为昵称（maxLines=3）/ 段位（titleSmall + 档位色）/ UID 三行纵向分列，同一 Column 同一 start。
//   先改 PlayerDetailDialog.HeaderRow（弹窗，文本列 ≈230dp，最易触发），后改共享 PlayerInfoHeader
//   （首页资料卡 + 统计页信息卡，文本列 ≈284dp）—— 用户 2026-10-02 确认"要一起改"，两处口径现已一致。
//   字号层级随之恢复为 昵称 titleMedium > 段位 titleSmall > UID bodySmall（合并期把段位抬到 16sp 是败笔）。
//
// 保留的 V28 不变量（口径映射到新版式）：
//   1) 四层留白单调（组内 4dp、跨组 12dp，跨组 ≥3× 组内）；
//   2) 昵称/段位/UID 三行同左缘（同一 Column 的同一 start）；
//   3) 字号层级 昵称行 > 段位行 > UID ≈ 表头，积分数值 > 昵称行；
//   4) 表头样式区别于数值（labelMedium + SemiBold + letterSpacing vs titleLarge + Bold）；
//   5) 卡片内不出现硬编码颜色；
//   6) 对局卡四块内容（头像/昵称列/积分变化/胜负）垂直居中于卡片中轴、头像与排行榜同尺寸
//      ——V40-C 反转 V28「右缘组与昵称同基线」（旧口径原为对齐导出图，V29 已删导出）。
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
        // V41：昵称与段位改回纵向分列（不再合并成单 Text），但 8dp Spacer 常量**依旧不该回来**——
        // 身份块内部靠"字号 + 档位色"区分，不靠留白；间距只发生在段位↔UID 之间。
        assertFalse(
            "昵称↔段位 8dp Spacer 常量不应复活（身份块内不靠留白区分）",
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
     * 不变量 2a（V41 改版，V37-3 任务 B 的合并方案在本组件**作废**）：昵称与段位是**两段独立
     * Text、纵向分列**。旧口径"合并成单 Text"同样作废，理由是它自身的缺陷：
     * `maxLines = 1` ⇒ 整行共用一个省略号，昵称一长段位就被截掉（玩家侧实测复现）。
     * V37-3 真正要修的目标（左缘对齐）依然成立——三行同属一个 Column 的同一 start。
     */
    @Test
    fun `昵称与段位纵向分列且长昵称不吞段位`() {
        assertTrue("外层头像 Row 应垂直居中", nickLineChunk.contains("verticalAlignment = Alignment.CenterVertically"))
        assertFalse("V36 红线 1 的 Bottom+baseline 互斥形态不得出现", nickLineChunk.contains("Row(verticalAlignment = Alignment.Bottom)"))
        assertFalse("不得再合并成单个 Text（共用省略号会截掉段位）", nickLineChunk.contains("buildAnnotatedString"))
        assertFalse("SpanStyle 行内染色随分列作废", nickLineChunk.contains("SpanStyle"))
        assertFalse("withStyle 随分列作废", nickLineChunk.contains("withStyle"))
        assertEquals("昵称块内应是两个 Text 节点（昵称 + 段位）", 2, Regex("Text\\(").findAll(nickLineChunk).count())
        assertTrue("昵称允许多行以容纳长昵称", nickLineChunk.contains("maxLines = 3"))
        assertTrue("段位色来自 tierColor（不硬编码）", nickLineChunk.contains("color = tierColor(tier)"))
        assertTrue("段位不得折行", nickLineChunk.contains("maxLines = 1"))
        assertTrue("无段位时整段不渲染，不留空行占位", nickLineChunk.contains("if (tier.isNotEmpty())"))
        assertFalse("不再需要 alignByBaseline hack", nickLineChunk.contains("alignByBaseline()"))
        // 🔴 段位作为独立 Text 后，tierColor 可以在 Composable 调用点直接用；
        //    旧版"lambda 内不得调 @Composable"的约束随 buildAnnotatedString 一起消失。
        assertTrue(
            "段位 Text 应直接调 @Composable tierColor（已不在 lambda 里）",
            Regex("""Text\(\s*text = tier,""").containsMatchIn(codeOnly(nickLineChunk)),
        )
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

    /** 不变量 3：昵称行 > 段位行 > UID ≈ 表头；积分数值是全卡最重的一档 */
    @Test
    fun `字号层级：昵称行大于UID与表头、积分数值最重`() {
        val nick = styleOf(headerBody, "text = nickname,")
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
        // V41：段位改回独立 Text ⇒ 层级 titleMedium(昵称) > titleSmall(段位) > bodySmall(UID)，
        // 段位夹在中间当"身份注解"，正是分列前的老口径（合并期把它抬到 16sp 才是那次方案的败笔）。
        val tier = styleOf(headerBody, "text = tier,")
        assertTrue("段位($tier ${sizeSp(tier)}sp) 应小于昵称行($nick)", sizeSp(tier) < sizeSp(nick))
        assertTrue("段位($tier) 应 ≥ UID($uid)", sizeSp(tier) >= sizeSp(uid))
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

    /**
     * 不变量 6（V40-C，口径反转）：对局卡四块内容（头像 / 昵称列 / 积分变化 / 胜负）以卡片内容区
     * 中轴垂直居中，且头像 44dp 与排行榜页同尺寸。
     * 旧 V28 口径「Top + 三处 alignByBaseline（右缘组顶对齐到昵称行）」是配合导出图定的，
     * 导出渲染器 V29 已删，用户点名改为「与排行榜一致大小 + 一律居中于材料中轴线」。
     * 🔴 结构断言跑在 codeOnly 上：本版注释引用了 alignByBaseline 旧特征串（见其 KDoc）。
     */
    @Test
    fun `对局卡四块内容垂直居中且头像与排行榜同尺寸`() {
        val code = codeOnly(recordBody)
        assertTrue(
            "Row 应垂直居中（用户：头像居中，积分变化与胜负也居中于卡片中轴）",
            code.contains("verticalAlignment = Alignment.CenterVertically"),
        )
        assertFalse("旧的 Top 贴顶不得残留", code.contains("verticalAlignment = Alignment.Top"))
        assertFalse("旧的基线 hack（V28 为对齐导出图所定）作废", code.contains("alignByBaseline()"))
        assertEquals("对局卡头像应为 44dp", 44, constDp(src, "RECORD_AVATAR_SIZE_DP"))
        // 「和排行榜一页一致大小」锁到排行榜源码本身：两边各写一个数字必漂移，比对实参值
        val rankSrc = readSource("ui", "screens", "rank", "RankRoute.kt")
        assertTrue("排行榜页头像应为 44dp（用户点名的参照物）", rankSrc.contains("size = 44.dp"))
        val rankAvatarDp = Regex("""Avatar\([^)]*size = (\d+)\.dp""").find(rankSrc)
        assertTrue("应能从 RankRoute 解析出 Avatar 尺寸实参", rankAvatarDp != null)
        assertEquals(
            "首页对局卡头像尺寸必须与排行榜 Avatar 实参一致",
            rankAvatarDp!!.groupValues[1].toInt(),
            constDp(src, "RECORD_AVATAR_SIZE_DP"),
        )
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
        // V41 用户复核「右侧积分变动文本要对齐中轴线」时补的锁：
        // 上面四条锁的是**块内**两行左右缘一致；这条锁**垂直方向**——整个右侧块
        // （两行积分 + 胜负）必须居中于卡片内容中轴（V40-C 口径），否则整块会偏上/偏下。
        // 此前无任何断言覆盖，改版时很容易被"顺手加个 Spacer/改 padding"破坏。
        assertTrue(
            "记录卡外层 Row 应垂直居中（V40-C：头像/昵称列/积分变化/胜负 四块居中于中轴）",
            code.contains("verticalAlignment = Alignment.CenterVertically"),
        )
    }

    /**
     * V41（用户 2026-10-02 反馈「玩家名字太长时显示不全段位」）：玩家详情弹窗信息头改为**纵向分列**。
     * V37-3 任务 B 的合并单 Text 方案作废——maxLines = 1 意味着昵称与段位**共用一个省略号**，
     * 昵称一长段位就被截进「…」里（当时注释写的"实测未触发"是错的，玩家侧一上报即复现）。
     * 本测试断言随之反向，V37-3 真正要修的目标（段位与 UID 左缘不齐）依然成立：
     * 三段 Text 同属 `Column(Modifier.weight(1f))` 的同一 start，错位在结构上不可能出现。
     */
    @Test
    fun `玩家详情弹窗信息头昵称与段位纵向分列且长昵称不吞段位`() {
        val body = codeOnly(bodyOf(dialogSrc, "private fun HeaderRow("))
        assertFalse(
            "不得再把昵称与段位合成单个 Text：单 Text + maxLines=1 共用一个省略号，长昵称会把段位截掉（V41 玩家反馈）",
            body.contains("buildAnnotatedString"),
        )
        assertFalse("SpanStyle 行内染色随分列作废", body.contains("SpanStyle"))
        assertFalse("withStyle 随分列作废", body.contains("withStyle"))
        assertTrue("昵称行允许多行以容纳长昵称", body.contains("maxLines = 3"))
        assertTrue("昵称过 3 行才截断", body.contains("overflow = TextOverflow.Ellipsis"))
        assertTrue("段位是独立 Text 并按档位着色", body.contains("color = tierColor(tierText)"))
        assertTrue("段位不得折行（tier 名 + 至多 5 颗星，一行足够）", body.contains("maxLines = 1"))
        assertTrue("无段位时整段不渲染，不留空行占位", body.contains("if (tierText != null) {"))
        assertTrue("文本列吃满剩余宽度（三行同左缘的结构前提）", body.contains("Column(Modifier.weight(1f))"))
        assertFalse("不再需要基线 hack", body.contains("alignByBaseline()"))
        assertFalse("88/148dp 昵称上限补丁早已作废", body.contains("widthIn"))
        assertFalse("昵称↔段位 8dp Spacer 不得回来", body.contains("Spacer(Modifier.width(8.dp))"))
    }

    /**
     * V42（用户 2026-10-02 转达玩家建议「减分改成红色/橙色更直观」）：变化值**按符号**染色。
     * 旧写法天梯恒 `semantic.win`、巅峰恒 `semantic.gold` —— 颜色压根不携带符号信息，
     * 于是「(-7)」被染成绿色，语义反了。断言随之反向。
     * 统一口径见 `ui/theme/Theme.kt` 的 [scoreDeltaColor]：涨=win / 跌=lose / 平=onSurfaceVariant。
     */
    @Test
    fun `对局卡积分变化按符号染色而非恒定win或gold`() {
        val code = codeOnly(recordBody)
        assertFalse("天梯变化不得恒用 win 色（会把减分染绿）", code.contains("SpanStyle(color = semantic.win)"))
        assertFalse("巅峰变化不得恒用 gold 色（不携带符号信息，且与'跌=红'并排时读乱）", code.contains("SpanStyle(color = semantic.gold)"))
        assertEquals(
            "天梯/巅峰两处变化都要走 scoreDeltaColor",
            2,
            Regex("""SpanStyle\(color = (ladder|peak)DeltaColor\)""").findAll(code).count(),
        )
        // 🔴 buildAnnotatedString 的 lambda 不是组合上下文 ⇒ 色值必须先在 Composable 体内取好。
        assertTrue("色值先取成品值再进 lambda（天梯）", code.contains("val ladderDeltaColor = scoreDeltaColor(ladderChange)"))
        assertTrue("色值先取成品值再进 lambda（巅峰）", code.contains("val peakDeltaColor = scoreDeltaColor(peakChange)"))
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
