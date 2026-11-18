// 报告 §7.1 特例矩阵：对话框内容与「保存」写入由 DownloadOption 列表决定。
// 这些断言锁死「几种格式给几个选项」与图标扩展名嗅探；
// 格式选择只存在于下载对话框中，默认选中普通卡面。

package com.gigi.tcg.ui.screens.carddetail

import com.gigi.tcg.R
import com.gigi.tcg.data.model.CardBasicInfo
import com.gigi.tcg.domain.CardSkill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadOptionsTest {

    private fun info(common: String?, gold: String?) =
        CardBasicInfo(name = "浊水幻灵", commonImg = common, goldImg = gold)

    private fun skill(icon: String?) = CardSkill(
        name = "浊水轻击",
        type = "普通攻击",
        iconUrl = icon,
        costs = emptyList(),
        descLabel = "技能效果",
        description = "造成 1 点水元素伤害。",
        notes = emptyList(),
    )

    @Test
    fun `普通加动态两种格式给出两个选项`() {
        val options = CardDownloads.coverOptions(info("c.png", "g.gif"))

        assertEquals(2, options.size)
        assertEquals(R.string.download_option_common, options[0].labelRes)
        assertEquals("png", options[0].extension)
        assertEquals("image/png", options[0].mimeType)
        assertEquals(R.string.download_option_dynamic, options[1].labelRes)
        assertEquals("gif", options[1].extension)
        assertEquals("image/gif", options[1].mimeType)
    }

    @Test
    fun `只有普通卡面时只有一个选项`() {
        val options = CardDownloads.coverOptions(info("c.png", null))

        assertEquals(1, options.size)
        assertEquals(R.string.download_option_common, options.single().labelRes)
    }

    @Test
    fun `只有动态卡面时只有动态一个选项`() {
        val options = CardDownloads.coverOptions(info(null, "g.gif"))

        assertEquals(1, options.size)
        assertEquals(R.string.download_option_dynamic, options.single().labelRes)
    }

    @Test
    fun `空串视同缺失`() {
        assertEquals(0, CardDownloads.coverOptions(info("  ", "")).size)
        assertNull(CardDownloads.coverDialog(info("", "")))
    }

    @Test
    fun `两种格式都没有时不打开对话框`() {
        assertNull(CardDownloads.coverDialog(info(null, null)))
    }

    @Test
    fun `卡面对话框默认预览第一项`() {
        val dialog = CardDownloads.coverDialog(info("c.png", "g.gif"))!!

        assertEquals("c.png", dialog.previewUrl)
        assertEquals(DownloadPreview.Cover, dialog.preview)
    }

    @Test
    fun `技能图标永远只有一个选项且落技能图标目录`() {
        val option = CardDownloads.iconOption(skill("i.png"))!!

        assertEquals(R.string.download_option_icon, option.labelRes)
        assertEquals(DownloadDir.SkillIcon, option.dir)
        assertEquals("png", option.extension)

        val dialog = CardDownloads.iconDialog(skill("i.png"))!!
        assertEquals(DownloadPreview.Icon, dialog.preview)
        assertEquals(1, dialog.options.size)
    }

    @Test
    fun `技能无图标不打开对话框`() {
        assertNull(CardDownloads.iconOption(skill(null)))
        assertNull(CardDownloads.iconOption(skill(" ")))
        assertNull(CardDownloads.iconDialog(skill(null)))
    }

    @Test
    fun `图标扩展名按 URL 后缀嗅探`() {
        assertEquals("png", CardDownloads.iconOption(skill("https://a/b/icon.png"))!!.extension)
        assertEquals("jpg", CardDownloads.iconOption(skill("https://a/b/icon.jpg"))!!.extension)
        assertEquals("jpg", CardDownloads.iconOption(skill("https://a/b/icon.JPEG?x=1"))!!.extension)
        // 无后缀 / 未知后缀一律按 png（报告 §7.2）
        assertEquals("png", CardDownloads.iconOption(skill("https://a/b/icon"))!!.extension)
        assertEquals("png", CardDownloads.iconOption(skill("https://a/b/icon.webp"))!!.extension)
    }

    @Test
    fun `图标 MIME 与扩展名配对`() {
        assertEquals("image/jpeg", CardDownloads.iconOption(skill("a.jpg"))!!.mimeType)
        assertEquals("image/png", CardDownloads.iconOption(skill("a.png"))!!.mimeType)
    }

    @Test
    fun `卡面选项落相册根`() {
        assertTrue(
            CardDownloads.coverOptions(info("c.png", "g.gif")).all { it.dir == DownloadDir.AlbumRoot },
        )
    }
}
