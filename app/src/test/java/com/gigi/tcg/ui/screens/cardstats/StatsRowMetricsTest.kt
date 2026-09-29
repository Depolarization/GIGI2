// V36 任务 C（用户第 6 项）：卡牌统计页列表左边距对称的度量锁。
// 不靠截图：直接引用源码里的列几何常量断言「牌名列左缘 = contentPadding + # 列宽」，
// 并对源码文本断言「# 列起始对齐、牌名列无额外缩进」，防止回退成右对齐 + 8dp 叠加。
package com.gigi.tcg.ui.screens.cardstats

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.unit.dp

class StatsRowMetricsTest {

    private val src: String by lazy {
        val file = File("src/main/java/com/gigi/tcg/ui/screens/cardstats/CardStatsRoute.kt")
        assertTrue("源码文件不存在: ${file.absolutePath}", file.exists())
        file.readText()
    }

    @Test
    fun `rank column is narrowed and left edge equals content padding plus rank width`() {
        // 收窄：不得回到 32dp 的右对齐宽度（那会把数字左缘推到 38–44dp）
        assertTrue("# 列应收窄到 ≤24dp，实际 $RankColumnWidth", RankColumnWidth <= 24.dp)
        assertTrue("# 列至少容得下三位数名次（≥20dp）", RankColumnWidth >= 20.dp)
        // 牌名列左缘 = 页边距 + # 列宽（牌名不再叠 padding(start)）
        assertEquals(
            "牌名列左缘 = ContentHorizontalPadding + RankColumnWidth",
            ContentHorizontalPadding + RankColumnWidth,
            16.dp + 24.dp,
        )
    }

    @Test
    fun `rank cells are start-aligned and name cells carry no start padding`() {
        // 四张 # 表头/名次 Text 都不得再挂 TextAlign.End（统计数值列保持右对齐，不受本锁约束）
        val rankTexts = Regex("rank\\.toString\\(\\)[^)]*\\)").findAll(src).toList() +
            Regex("stringResource\\(R\\.string\\.stat_rank\\)[^)]*\\)").findAll(src).toList()
        assertEquals("两页表头 + 两页行，共 4 处 # 列文本", 4, rankTexts.size)
        rankTexts.forEach { m ->
            assertFalse("# 列必须起始对齐（TextAlign.End 是本次修掉的病）：${m.value}", m.value.contains("TextAlign.End"))
        }
        assertFalse(
            "牌名列不得再补 padding(start = 8.dp)（左缘应直接落在 # 列右边界）",
            src.contains("weight(1f).padding(start = 8.dp)"),
        )
    }
}
