// V39-H3 收藏对局「终调」验收锁（承接 V39-H1/H2，本轮只动尺寸，判据 A/B 原样保留不许回退）：
//   ① 头像从 28 → 32dp（1.16× 官方 27.6，仍在「略微调大」的 1.0~1.25 带内）；
//   ② 重叠从 9 → 8dp，**绝对叠压量与比率双降**（32.1% → 25.0%）——用户「重叠是为了确保信息清晰」，
//     所以重叠区缩小必须伴随单张可见弧变大：28/9 时每张只露 19dp，32/8 露 24dp，净信息量上升；
//   ③ 3v3 是主场景（用户「大多数收藏对局是 3v3，其他配比较为少见」），故 3 人簇 66 → 80dp。
//     代价是 3 人簇占内容宽 19.6% → 23.8%，**R4 占比上限相应从 20% 放宽到 24%**——这是按用户
//     「优先考虑 3v3」指示主动做的口径调整，不是数值失控：再往上 33/8 的 3 人簇 83dp = 24.7%
//     破 24%，而 4v4 中间列只剩 104dp 逼近时间串实测 101.8dp，余量太薄。
//   ④ 4v4 不截断是硬底线：内容宽 336 − 2×104 − 2×8 = 112dp ≥ 110（时间行实测 101.8dp + 余量）。
//
// 🔴 判据 A（中轴恒定，H1 成果不许回退）——两侧列必须**等宽**（各取两侧簇宽的较大值），
//   等宽 ⇒ 中间列剩余空间左右对称 ⇒ 胜负/模式/时间的中轴恒等于卡内几何中心。
// 🔴 判据 B（H2 成果不许回退，簇贴边）——两侧列内容的 horizontalAlignment 不许再是
//   CenterHorizontally：人数不等时小簇在等宽列里居中会漂离该侧卡内边距（真机 2v4 卡
//   我方簇左缘 x=137，4v4 卡 x=84）。必须己方 Start / 对手 End 按侧靠边；列宽定柱只
//   负责给中间列留对称空间（A），列内靠边不改变列几何 ⇒ B 成立不伤 A。
//
// 官方实测基准（用户截图 1080×2376，密度 2.75，屏宽 393dp）：
//   头像 27.6dp、3 张簇重叠 ≈18.8dp（重叠率 ≈68%，是小头像下的深叠观感）。
// 数值断言一律走**算式**（step = size − overlap；簇宽(n) = size + (n−1)×step），
// 不硬编码单张表的数字——常量再调时闸门跟着算，不会反过来把实现钉死在旧值上。
// 布局没法在纯 JVM 单测里跑（不引 Robolectric/Compose UI 测试），所以钉两层：
//   ① 常量算术 —— 步距/簇宽表/重叠率带/占比/中间列预算/头像下限；
//   ② 源码结构 —— 两侧等宽的 sideWidthDp 单一来源（判据 A）、侧列按侧靠边且昵称同享对齐
//     （判据 B）、中间列居中三行、旧写法清零。
// V39-H2 的「语义色只作前景」红线在本类顺带复钉一条（前景表达式），配色对比仍归
// MyFavoritesContrastTest，本类不重复。
package com.gigi.tcg.ui.screens.my

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MyFavoritesOfficialLayoutTest {

    private companion object {
        /** 官方实测头像直径（dp，用户截图量出）——「略微调大」必须严格大于它 */
        const val OFFICIAL_AVATAR_DIAMETER_DP = 27.6

        /** 与 MyFavoritesPage 里 LazyColumn 的 contentPadding 同源，改动本类要一起对齐 */
        const val LAZY_CONTENT_PADDING_DP = 16

        /**
         * 3 张簇占卡片内容宽的上限。
         * 🔴 V39-H3 由 20f 放宽到 24f：用户明确「大多数收藏对局是 3v3，优先考虑 3v3」，
         * 为把头像从 28 加大到 32dp，3 人簇必然从 66dp 涨到 80dp = 23.8%。这是有意的口径
         * 调整（换头像尺寸买信息清晰度），不是数值失控——24% 仍守住「堆叠区不喧宾夺主」。
         */
        const val MAX_CONTENT_RATIO = 24f

        /**
         * 中间列横向预算下限：时间串 "2026-09-28 21:30" 真机 uiautomator 实测 280px = 101.8dp，
         * 110dp = 实测值 + 约 8dp 余量（H3 由 120 收敛到 110：120 是估值，实测后按证据下调）。
         */
        const val MIN_CENTER_COLUMN_DP = 110

        /** Row 三段间距（与源码 spacedBy(8.dp) 同源），最坏情况要从内容宽里扣 2 个 */
        const val ROW_GAP_DP = 8

        /**
         * 重叠率新带 20%~35%：官方 68% 配的是 27.6dp 小头像，头像放大后同比例深叠会白边互切
         * 糊成一团（V39-H1 真机），必须大幅下调；H2 用户又要求「再适当减少」，40% 旧带整体压低
         * 到 35% 封顶。下限 20% 防退化成平铺无叠压（叠压是官方的分层观感来源）。
         */
        const val MIN_OVERLAP_RATIO = 20f
        const val MAX_OVERLAP_RATIO = 35f

        fun codeOnly(): String {
            val file = File("src/main/java/com/gigi/tcg/ui/screens/my/MyFavoritesPage.kt")
            assertTrue("源码文件不存在: ${file.absolutePath}", file.exists())
            // 剥块注释 + 行注释：口径注释里会引用被禁的旧写法（徽标/RoundedCornerShape），
            // 拿原文匹配结构闸门必然自己误判自己。
            return Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
                .replace(file.readText(), "")
                .lineSequence()
                .map { it.substringBefore("//") }
                .joinToString("\n")
        }

        /** 取某个顶层函数的函数体（到该函数第一个顶格 `}` 为止） */
        fun bodyOf(src: String, signature: String): String {
            val start = src.indexOf(signature)
            assertTrue("找不到函数: $signature", start >= 0)
            val end = src.indexOf("\n}", start)
            assertTrue("$signature 函数体封口没找到", end > start)
            return src.substring(start, end)
        }

        /** 从 `start`（指向一个 `(`）起做括号配平，返回整段源码（含首尾括号）；
         *  若配平后的 `)` 后面紧跟 `{`（尾随 lambda 函数体），一并吃进——
         *  `Column(...) { ... }` 的内容全在花括号里，只截圆括号段会拿到一个空壳 */
        fun balanced(src: String, start: Int): String {
            var depth = 0
            for (i in start until src.length) {
                when (src[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            var j = i + 1
                            while (j < src.length && src[j].isWhitespace()) j++
                            if (j < src.length && src[j] == '{') {
                                var braces = 0
                                var k = j
                                while (k < src.length) {
                                    when (src[k]) {
                                        '{' -> braces++
                                        '}' -> {
                                            braces--
                                            if (braces == 0) return src.substring(start, k + 1)
                                        }
                                    }
                                    k++
                                }
                                throw AssertionError("位置 $j 起的花括号不配平")
                            }
                            return src.substring(start, i + 1)
                        }
                    }
                }
            }
            throw AssertionError("位置 $start 起括号不配平，源码结构被改坏了")
        }

        /** 中间列：weight(1f) 且 horizontalAlignment=CenterHorizontally 的那个 Column 整段 */
        fun centerColumn(src: String): String {
            val m = Regex("""Column\(\s*modifier = Modifier\.weight\(1f\),\s*horizontalAlignment = Alignment\.CenterHorizontally""")
                .find(src)
            assertTrue("找不到「weight(1f) + CenterHorizontally」的中间列——本轮第一要求是胜负/模式/时间居中", m != null)
            return balanced(src, src.indexOf('(', m!!.range.first))
        }

        /** 两侧列：挂同一个定宽 sideWidthDp、horizontalAlignment 按侧靠边（Start/End）的 Column
         *  （各含一个簇 + 昵称）。「两侧等宽」是中轴恒定（判据 A）的前提，
         *  「按侧靠边」是簇贴齐卡内边距（判据 B）的前提，正则把两者一起钉进匹配式 */
        fun sideColumns(src: String): List<String> =
            Regex("""Column\(\s*modifier = Modifier\.width\(sideWidthDp\.dp\),\s*horizontalAlignment = Alignment\.(Start|End),\s*verticalArrangement""")
                .findAll(src).map { balanced(src, src.indexOf('(', it.range.first)) }.toList()
    }

    // ─────────── ① 常量算术：放大口径 + 1~4 张簇宽表（全走算式） ───────────

    @Test
    fun `avatar is slightly larger than official 27 6dp`() {
        assertTrue(
            "用户要求「官方的头像仍较小，可以再略微调大」：官方 27.6dp，实现必须取整 ≥28dp，" +
                "实际 LINEUP_AVATAR_SIZE_DP=$LINEUP_AVATAR_SIZE_DP",
            LINEUP_AVATAR_SIZE_DP >= 28,
        )
        assertTrue(
            "「略微调大」不是翻倍：官方 ${OFFICIAL_AVATAR_DIAMETER_DP}dp 的放大率应在 1.0~1.25 之间，" +
                "实际 ${"%.2f".format(LINEUP_AVATAR_SIZE_DP / OFFICIAL_AVATAR_DIAMETER_DP)} 倍",
            LINEUP_AVATAR_SIZE_DP / OFFICIAL_AVATAR_DIAMETER_DP in 1.0..1.25,
        )
    }

    @Test
    fun `overlap ratio sits in the readability band and step stays same-sourced`() {
        val step = LINEUP_AVATAR_SIZE_DP - LINEUP_AVATAR_OVERLAP_DP
        assertEquals(
            "重叠量必须 0 < overlap < size（等于 0 就没叠、≥size 会把后一张完全藏起来），" +
                "实际 size=$LINEUP_AVATAR_SIZE_DP overlap=$LINEUP_AVATAR_OVERLAP_DP",
            true,
            LINEUP_AVATAR_OVERLAP_DP > 0 && LINEUP_AVATAR_OVERLAP_DP < LINEUP_AVATAR_SIZE_DP,
        )
        assertEquals(
            "步距 = size - overlap 必须与簇宽算式同源：簇宽(2) - 簇宽(1) 就是步距，" +
                "实际 step=$step",
            step,
            lineupClusterWidth(2) - lineupClusterWidth(1),
        )
        val overlapPercent = LINEUP_AVATAR_OVERLAP_DP * 100f / LINEUP_AVATAR_SIZE_DP
        assertTrue(
            "重叠率必须落在 ${MIN_OVERLAP_RATIO}~${MAX_OVERLAP_RATIO}% 新带——官方 68% 配的是 27.6dp " +
                "小头像，头像放大后深叠白边互切糊成一团，必须大幅下调；H2 用户再要求「适当减少」，" +
                "旧 30~45% 带压低到 35% 封顶，实际 size=$LINEUP_AVATAR_SIZE_DP overlap=$LINEUP_AVATAR_OVERLAP_DP = ${"%.1f".format(overlapPercent)}%",
            overlapPercent in MIN_OVERLAP_RATIO..MAX_OVERLAP_RATIO,
        )
    }

    @Test
    fun `cluster width table covers 1 to 4 players`() {
        // 官方 1~4 人不等（用户明确），封顶必须放开到 4
        assertEquals("簇宽封顶必须是 4 张（旧版 3 张会吞掉四人局的末张）", 4, LINEUP_MAX_AVATARS)
        val step = LINEUP_AVATAR_SIZE_DP - LINEUP_AVATAR_OVERLAP_DP
        for (n in 1..LINEUP_MAX_AVATARS) {
            val expected = LINEUP_AVATAR_SIZE_DP + (n - 1) * step
            assertEquals(
                "簇宽($n) = size + (n-1)*step = $LINEUP_AVATAR_SIZE_DP + ${n - 1}×$step",
                expected, lineupClusterWidth(n),
            )
        }
        assertEquals("0 张不渲染整簇 ⇒ 簇宽 0", 0, lineupClusterWidth(0))
        assertEquals(
            "超出封顶按 LINEUP_MAX_AVATARS 截断，簇宽不越界",
            lineupClusterWidth(LINEUP_MAX_AVATARS), lineupClusterWidth(9),
        )
        assertEquals(
            "封顶簇宽常量必须等于 lineupClusterWidth(4)（两处算式必须同源）",
            lineupClusterWidth(LINEUP_MAX_AVATARS), LINEUP_CLUSTER_WIDTH_DP,
        )
    }

    @Test
    fun `3-avatar cluster stays within the content-width ratio budget`() {
        val cardWidth = SCREEN_WIDTH_DP - 2 * LAZY_CONTENT_PADDING_DP
        val contentWidth = cardWidth - 2 * CARD_SIDE_PADDING_DP
        assertEquals("卡片宽 = $SCREEN_WIDTH_DP - $LAZY_CONTENT_PADDING_DP*2", 360, cardWidth)
        assertEquals("内容宽 = 360 - 12*2", 336, contentWidth)

        val cluster3 = lineupClusterWidth(3)
        val contentRatio = cluster3 * 100.0 / contentWidth
        assertTrue(
            "3 张簇宽 ${cluster3}dp 占内容宽 ${"%.1f".format(contentRatio)}%，必须 ≤$MAX_CONTENT_RATIO%——" +
                "R4 上限在 H3 已由 20% 放宽到 24%（用户「优先考虑 3v3」换头像尺寸），但不许再往上漂",
            contentRatio <= MAX_CONTENT_RATIO.toDouble(),
        )
        // 🔴 4v4 是硬底线：中间列装不下时间串就会截断/换行，那是最刺眼的破相。
        // 侧列取两侧 max 后最坏各占封顶簇宽 ⇒ 中间列预算 = 内容宽 − 2×封顶簇宽 − 2×行间距
        val centerBudget = contentWidth - 2 * LINEUP_CLUSTER_WIDTH_DP - 2 * ROW_GAP_DP
        assertTrue(
            "最坏情况（两侧各 4 人）中间列只剩 ${centerBudget}dp，必须 ≥${MIN_CENTER_COLUMN_DP}dp " +
                "才放得下时间整行（\"2026-09-28 21:30\" 真机实测 101.8dp + 余量）",
            centerBudget >= MIN_CENTER_COLUMN_DP,
        )
    }

    /**
     * V39-H3 新增：重叠区缩小的同时**单张可见弧必须变大**。
     * 用户说「重叠是为了确保信息清晰」——那缩小重叠就不能是净损失：
     * 28/9 时每张只露 step=19dp，32/8 露 24dp，净信息量 +26%。
     * 钉死 step 必须 ≥ 20dp：等于 20 就退回 H2 的信息量水平，等于 19 是净退化。
     */
    @Test
    fun `shrinking the overlap must not shrink the visible arc per avatar`() {
        val step = LINEUP_AVATAR_SIZE_DP - LINEUP_AVATAR_OVERLAP_DP
        assertTrue(
            "每张头像的可见弧（步距）= size − overlap = ${step}dp，必须 ≥ 20dp——" +
                "H2 的 28/9 只有 19dp，用户要求重叠缩小必须换来更大的可见面，不能是净损失",
            step >= 20,
        )
        assertTrue(
            "重叠绝对量必须真的变小：当前 ${LINEUP_AVATAR_OVERLAP_DP}dp，H2 口径是 9dp（H3 要「适当缩小」）",
            LINEUP_AVATAR_OVERLAP_DP <= 9,
        )
        assertTrue(
            "重叠比率必须真的变小：当前 ${"%.1f".format(LINEUP_AVATAR_OVERLAP_DP * 100f / LINEUP_AVATAR_SIZE_DP)}%，" +
                "H2 口径是 32.1%（不得回退到 H1 的 40%）",
            LINEUP_AVATAR_OVERLAP_DP * 100f / LINEUP_AVATAR_SIZE_DP <= 32.2f,
        )
    }

    /**
     * 整卡高度有上界 —— 补 V39-F6 旧测试退役时丢掉的「行高预算」闸门。
     * 官方卡段实测 67~95dp（含卡面纹理/阴影取样误差），本实现 84dp 落在区间内。
     * 中间列一旦加第四行、侧列一旦换成两行昵称，卡高会静默膨胀，必须在这里挡住。
     */
    @Test
    fun `card height stays within the official three-band budget`() {
        // 侧列 = 头像簇 $LINEUP_AVATAR_SIZE_DP + 间距 2 + 昵称 labelMedium 行高 ≈18
        val sideBand = LINEUP_AVATAR_SIZE_DP + 2 + 18
        // 中间列 = titleMedium 24 + 2*2 间距 + labelMedium 16 + bodySmall 16 = 60
        val centerBand = 24 + 2 * 2 + 16 + 16
        val cardHeight = maxOf(sideBand, centerBand) + 2 * CARD_SIDE_PADDING_DP
        assertEquals("整卡高 = max(侧列 $sideBand, 中间列 $centerBand) + 上下 padding 各 $CARD_SIDE_PADDING_DP", 84, cardHeight)
        assertTrue(
            "整卡高 ${cardHeight}dp 必须落在官方实测的 67~95dp 区间内（超了就不是三段式单行卡片了）",
            cardHeight in 67..95,
        )
    }

    // ─────────── ② 源码结构闸门 ───────────

    /**
     * 🔴 V39-H1 核心闸门：中轴恒定。前提在数学上是「两侧列等宽」——
     * 两侧各占同宽 W ⇒ 中间列（唯一 weight(1f) 承载者）剩余 = 内容宽 − 2W − 2×间距，
     * 其中线到卡内左右边界距离相等 ⇒ 中轴 ≡ 几何中心，与每张卡的人数无关。
     * 两侧各拿本侧簇宽直接摆列（G1 写法）⇒ W左 ≠ W右 ⇒ 中轴随 1v1/3v3/4v4 逐档漂移。
     */
    @Test
    fun `both side columns share one equal width so the center axis cannot drift`() {
        val body = bodyOf(codeOnly(), "private fun MatchRow(")
        val defs = Regex("""val sideWidthDp = max\(""").findAll(body).count()
        assertEquals(
            "必须恰有一处 `val sideWidthDp = max(...)` 定义：两侧列宽要来自同一个「两侧簇宽取大者」的计算，" +
                "实际 $defs 处",
            1, defs,
        )
        val defStart = body.indexOf("val sideWidthDp = max(")
        val maxExpr = balanced(body, body.indexOf('(', defStart))
        assertEquals(
            "max(...) 的两个被比较项必须都是 lineupClusterWidth(...)（两侧簇宽），实际: $maxExpr",
            2, Regex("""lineupClusterWidth\(""").findAll(maxExpr).count(),
        )
        assertTrue(
            "两侧必须恰好一侧 self 一侧 opposite，缺任何一侧都不叫「取两侧较大者」⇒ 两侧不等宽 ⇒ 中轴随人数漂移",
            maxExpr.contains("match.self") && maxExpr.contains("match.opposite"),
        )
        val equalWidthUses = Regex("""Modifier\.width\(sideWidthDp\.dp\)""").findAll(body).count()
        assertEquals(
            "两侧列必须都挂同一个 Modifier.width(sideWidthDp.dp)——两侧不等宽时中间列剩余空间左右不对称，" +
                "「每张卡各自居中」就退化成「每张卡中轴各漂各的」，实际 $equalWidthUses 处",
            2, equalWidthUses,
        )
        assertTrue(
            "禁止回退：任何一侧列不许直接拿本侧簇宽当列宽（G1 的漂移根因就是这个写法）",
            !Regex("""width\(\s*lineupClusterWidth\(""").containsMatchIn(body),
        )
        assertTrue(
            "禁止回退：昵称不许再各开 widthIn 封顶预算——预算不与列宽同源时长昵称会把两侧列撑成不等宽",
            !Regex("""widthIn\(""").containsMatchIn(body),
        )
    }

    @Test
    fun `center column holds win-mode-time all centered - the core demand`() {
        val body = bodyOf(codeOnly(), "private fun MatchRow(")
        val center = centerColumn(body)
        val textCount = Regex("""\bText\(""").findAll(center).count()
        assertEquals("中间列必须恰好 3 个 Text（胜负/模式/时间三行，官方拆行口径），实际 $textCount 个", 3, textCount)
        assertTrue("第一行必须是胜负：复用现有 home_result_win/lose 键（不新增字符串）",
            Regex("""stringResource\(if \(isWin\) R\.string\.home_result_win else R\.string\.home_result_lose\)""")
                .containsMatchIn(center))
        assertTrue("第二行必须是模式 matchType", center.contains("match.matchType"))
        assertTrue("第三行必须是时间 formatGcgDateTime(match.matchTime)",
            center.contains("formatGcgDateTime(match.matchTime)"))
        assertTrue("三行都必须 maxLines=1 截断（不为全名/长时间换行加高）",
            Regex("""maxLines\s*=\s*1""").findAll(center).count() == 3)
        assertTrue(
            "V39-H2 口径原样保留：胜负语义色只作前景 `color = if (isWin) semantic.win else semantic.lose`",
            Regex("""color\s*=\s*if \(isWin\) semantic\.win else semantic\.lose""").containsMatchIn(center),
        )
        assertTrue("整文件不得出现 background(semantic.*)（语义色不作背景块）",
            !Regex("""background\([^)]*semantic\.(win|lose|gold)""").containsMatchIn(body))
    }

    /**
     * 中轴恒定的另一半：能影响中轴的权重只许给中间列一处（方案 C 定宽侧列下 G1 口径仍成立）。
     * 两侧列若也吃 weight，剩余空间就不再是「内容宽 − 2W − 2×间距」的对称式，中轴重新开漂。
     */
    @Test
    fun `middle column is the only weight carrier and both clusters match spec`() {
        val body = bodyOf(codeOnly(), "private fun MatchRow(")
        val weights = Regex("""\.weight\(""").findAll(body).count()
        assertEquals("行内只允许一处 weight，且必须归中间列（两侧列定宽 sideWidthDp、不吃 weight），实际 $weights 处", 1, weights)
        centerColumn(body) // 顺带钉死：唯一的 weight 必须挂在 CenterHorizontally 的 Column 上

        // 叠压方向一致闸门（上轮已有，别弄丢）：两簇调用参数归一化后必须逐字相等
        val calls = Regex("""(?<!fun )LineupCluster\(\s*([^()]*)\)""")
            .findAll(body).map { it.groupValues[1].trim() }.toList()
        assertEquals("必须恰好两处 LineupCluster 调用（己方 + 对手），实际: $calls", 2, calls.size)
        val normalized = calls.map { it.replace(Regex("""match\.(self|opposite)"""), "side") }
        assertEquals(
            "两簇参数必须逐字相同，唯一允许的差异是数据源 self/opposite——任何按侧入参都是给对手簇开反向后门",
            normalized[0], normalized[1],
        )
        val flipArgs = Regex("""(?i)(reverse|mirrors?|rtl|direction|align(Start|End)|fromEnd)""")
        assertTrue("两簇调用不得携带翻转方向的入参，实际: $calls", calls.none { flipArgs.containsMatchIn(it) })
    }

    @Test
    fun `nickname sits under its own cluster sharing its side alignment`() {
        val body = bodyOf(codeOnly(), "private fun MatchRow(")
        val sides = sideColumns(body)
        assertEquals("必须恰好两个「定宽 sideWidthDp + 按侧靠边 horizontalAlignment + verticalArrangement」侧列（己方 + 对手），实际 ${sides.size} 个", 2, sides.size)
        for ((i, col) in sides.withIndex()) {
            val clusterAt = col.indexOf("LineupCluster(")
            assertTrue("侧列 ${i + 1} 必须包含头像簇", clusterAt >= 0)
            val nameAt = col.indexOf(".name", clusterAt)
            assertTrue("昵称 Text 必须出现在头像簇**之后**且同属这个 Column（官方：昵称在簇正下方）",
                nameAt > clusterAt)
            // 判据 B 的「不许两张皮」半边：Column 只有一处 horizontalAlignment，簇和昵称都吃它；
            // 多出来的一处就是昵称另开对齐（簇贴边、昵称居中会重新漂）
            val alignCount = Regex("""horizontalAlignment""").findAll(col).count()
            assertEquals("侧列 ${i + 1} 只允许一处 horizontalAlignment——昵称必须与簇共享同一个列对齐，实际 $alignCount 处", 1, alignCount)
            assertTrue("昵称必须 maxLines=1 截断", col.contains("maxLines = 1"))
        }
        // 昵称归属闸门：中间列里不得再出现任何一侧的 .name
        val center = centerColumn(body)
        assertTrue("昵称不许留在中间列（本轮要求：昵称归各侧簇下方，中间只有胜负/模式/时间）",
            !center.contains(".name"))
    }

    /**
     * 🔴 V39-H2 新增判据 B 闸门：两侧簇贴齐各自的卡内边距。
     * sideWidthDp 取两侧 max 只为给中间列留对称空间（判据 A）；代价是人数不等时小簇窄于列宽。
     * H1 用 CenterHorizontally 让小簇在等宽列里居中 ⇒ 整簇漂离该侧内边距（真机 2v4 卡我方簇
     * 左缘 x=137，4v4 卡 x=84，肉眼可见「我方头像离左边框远一截」）。
     * 修法：己方列 Start、对手列 End——贴边只改列内容在定宽柱内的摆放，柱宽与柱位不变
     * ⇒ 中间列剩余依旧左右对称 ⇒ B 成立不伤 A。
     */
    @Test
    fun `side columns align per side so the smaller cluster hugs the card edge`() {
        val body = bodyOf(codeOnly(), "private fun MatchRow(")
        val sides = sideColumns(body)
        assertEquals("必须恰有两个按侧靠边的侧列（Start/End），实际 ${sides.size} 个", 2, sides.size)
        assertTrue(
            "两侧列禁止回退 CenterHorizontally：簇在等宽列里居中会让小簇漂离卡内边距——" +
                "列宽取两侧 max 保住了中轴，但人数不等时小簇（如 2 人 47dp）会在 85dp 定宽柱里" +
                "向中间漂 ~(W−簇宽)/2，视觉上离该侧边框凭空远一截",
            sides.none { it.contains("horizontalAlignment = Alignment.CenterHorizontally") },
        )
        val selfCol = sides.single { it.contains("match.self") }
        val oppCol = sides.single { it.contains("match.opposite") }
        assertTrue(
            "己方侧列必须 Alignment.Start：簇与昵称贴卡左内边距（判据 B 左半边）",
            Regex("""horizontalAlignment = Alignment\.Start""").containsMatchIn(selfCol),
        )
        assertTrue(
            "对手侧列必须 Alignment.End：簇与昵称贴卡右内边距（判据 B 右半边，留白倒向内侧）",
            Regex("""horizontalAlignment = Alignment\.End""").containsMatchIn(oppCol),
        )
        // 靠边不许顺手改成不吃定宽柱：判据 A 的等宽前提不能被 Start/End 改造时丢掉
        val equalWidthUses = Regex("""Modifier\.width\(sideWidthDp\.dp\)""").findAll(body).count()
        assertEquals("两侧列仍必须都挂同一个 Modifier.width(sideWidthDp.dp)（等宽定柱是中轴恒定的前提），实际 $equalWidthUses 处", 2, equalWidthUses)
    }

    @Test
    fun `legacy f6 badge and joined mode-time line are gone`() {
        val body = bodyOf(codeOnly(), "private fun MatchRow(")
        assertTrue("左侧 28dp 徽标方块必须删除（胜负移到中间列顶部）——H3 已连死常量 MATCH_BADGE_SIZE_DP 一并清除",
            !Regex("""MATCH_BADGE_SIZE_DP""").containsMatchIn(body))
        assertTrue("徽标的 RoundedCornerShape 圆角块写法不得残留（本文件唯一圆角是 CircleShape 头像）",
            !Regex("""RoundedCornerShape""").containsMatchIn(body))
        assertTrue("徽标中性容器底（surfaceContainerHigh 方块）整块退场，本轮不再钉它",
            !Regex("""background\(""").containsMatchIn(body))
        assertTrue("模式与时间必须拆成两行，不得退回「模式 · 时间」joinToString 合并写法",
            !Regex("""joinToString\(" · "\)""").containsMatchIn(body))
        assertTrue("簇内叠压仍必须 offset + zIndex(-index)（左侧压右侧，方向唯一）",
            Regex("""offset\(\s*x\s*=\s*\(step \* index\)""").containsMatchIn(bodyOf(codeOnly(), "private fun LineupCluster(")) &&
                Regex("""zIndex\(\s*-""").containsMatchIn(bodyOf(codeOnly(), "private fun LineupCluster(")))
    }
}
