// V38-D：卡背下载纯逻辑层单测（纯 JVM，🔴 不碰 android.graphics / Context / MediaStore）。
// 钉死四件事：① 取图只认 image_v2 优先（image 实测 21/28 才有，不可当首选）；
// ② 未收集（has_obtained=false / 字段缺失=null）一律不给下载直链；
// ③ 文件名 = 本地化称谓 + id + 日期，清洗/截断走卡面那套同一口径（coverFileName）；
// ④ 落盘目录与卡面/卡组同源（buildAlbumRelativePath），且卡背不分 UID 时不产生空层级。

package com.gigi.tcg.ui.dialogs.cardback

import com.gigi.tcg.i18n.LocaleStrings
import com.gigi.tcg.data.model.GcgCardBack
import com.gigi.tcg.ui.dialogs.cardcover.EXPORT_DIR_ACTION
import com.gigi.tcg.ui.dialogs.cardcover.EXPORT_DIR_CHAR
import com.gigi.tcg.ui.dialogs.cardcover.EXPORT_DIR_QR
import com.gigi.tcg.ui.dialogs.cardcover.buildAlbumRelativePath
import com.gigi.tcg.ui.dialogs.cardcover.coverFileName
import com.gigi.tcg.ui.dialogs.cardcover.CoverFormat
import com.gigi.tcg.ui.dialogs.cardcover.exportDateText
import com.gigi.tcg.ui.dialogs.cardcover.exportDirName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val V2_URL = "https://act-webstatic.mihoyo.com/hk4e/e20200928calculate/item_icon/67c7f729/v2.png"
private const val OLD_URL = "https://act-webstatic.mihoyo.com/hk4e/e20200928calculate/item_gcg_item_icon_u8f59e/old.png"

private fun card(
    id: Int? = 101,
    image: String? = null,
    imageV2: String? = null,
    hasObtained: Boolean? = true,
) = GcgCardBack(id = id, image = image, imageV2 = imageV2, hasObtained = hasObtained)

class CardBackDownloadTest {

    @Test
    fun `image_v2 存在时优先取它`() {
        val url = resolveCardBackImageUrl(card(image = OLD_URL, imageV2 = V2_URL))
        assertEquals(V2_URL, url)
    }

    @Test
    fun `image_v2 缺失时回落旧 image`() {
        assertEquals(OLD_URL, resolveCardBackImageUrl(card(image = OLD_URL)))
    }

    @Test
    fun `image_v2 空白串按缺失处理`() {
        assertEquals(OLD_URL, resolveCardBackImageUrl(card(image = OLD_URL, imageV2 = "   ")))
    }

    @Test
    fun `两个图字段都缺失或空白时没有直链`() {
        assertNull(resolveCardBackImageUrl(card()))
        assertNull(resolveCardBackImageUrl(card(image = "", imageV2 = "")))
        assertNull(resolveCardBackImageUrl(null))
    }

    @Test
    fun `未收集的卡背不给下载直链`() {
        assertNull(resolveDownloadable(card(imageV2 = V2_URL, hasObtained = false)))
    }

    @Test
    fun `has_obtained 字段缺失按未收集处理`() {
        assertNull(resolveDownloadable(card(imageV2 = V2_URL, hasObtained = null)))
    }

    @Test
    fun `已收集且有图才给出直链`() {
        assertEquals(V2_URL, resolveDownloadable(card(imageV2 = V2_URL)))
    }

    @Test
    fun `已收集但无图仍然不给下载`() {
        assertNull(resolveDownloadable(card()))
    }

    @Test
    fun `基础名为 称谓 加 id 加日期`() {
        assertEquals("卡背_101_2026-09-29", cardBackBaseName(card(id = 101), "2026-09-29", "卡背"))
    }

    @Test
    fun `id 缺失时退化为称谓加日期且不出现空段`() {
        assertEquals("卡背_2026-09-29", cardBackBaseName(card(id = null), "2026-09-29", "卡背"))
        assertEquals("卡背_2026-09-29", cardBackBaseName(null, "2026-09-29", "卡背"))
    }

    @Test
    fun `默认卡背 id 零仍然进文件名`() {
        // id=0 是默认卡背，不能因为「0 是假值」被丢掉
        assertEquals("卡背_0_2026-09-29", cardBackBaseName(card(id = 0), "2026-09-29", "卡背"))
    }

    @Test
    fun `落盘名清洗非法字符并补 png 后缀`() {
        // / * ? 都在非法字符表里（各自换成下划线，与原有下划线叠成三个连续下划线）
        val name = coverFileName(cardBackBaseName(card(id = 101), "2026-09-29", "卡/背*?"), CoverFormat.Png)
        assertEquals("卡_背___101_2026-09-29.png", name)
    }

    @Test
    fun `落盘名超长截断到上限且不含路径分隔符`() {
        val longLabel = "背".repeat(120)
        val name = coverFileName(cardBackBaseName(card(id = 101), "2026-09-29", longLabel), CoverFormat.Png)
        assertTrue(name.length <= 60 + ".png".length)
        assertTrue(!name.contains('/'))
        assertTrue(name.endsWith(".png"))
    }

    @Test
    fun `落盘名为空白时回落卡面兜底文案不产出点开头文件`() {
        LocaleStrings.installResolverForTest { "卡面" }
        try {
            val name = coverFileName(cardBackBaseName(card(id = null), "", ""), CoverFormat.Png)
            assertEquals("卡面.png", name)
        } finally {
            LocaleStrings.installResolverForTest(null)
        }
    }

    @Test
    fun `卡背目录与卡面同源挂在相册根下`() {
        LocaleStrings.installResolverForTest { id -> if (id == EXPORT_DIR_CARDBACK) "卡背" else null }
        try {
            assertEquals(
                "Pictures/GIGI/卡背",
                buildAlbumRelativePath("Pictures", "GIGI", exportDirName(EXPORT_DIR_CARDBACK)),
            )
            // 🔴 卡背不分 UID：uid 传 null 时逐级省略，不产生 "//" 或尾斜杠（与二维码同口径）
            assertEquals(
                "Pictures/GIGI/卡背",
                buildAlbumRelativePath("Pictures", "GIGI", exportDirName(EXPORT_DIR_CARDBACK), ""),
            )
        } finally {
            LocaleStrings.installResolverForTest(null)
        }
    }

    @Test
    fun `日期口径与卡面导出同一个函数`() {
        assertTrue(exportDateText().matches(Regex("""\d{4}-\d{2}-\d{2}""")))
    }

    @Test
    fun `卡背目录资源id与既有导出目录互不相同`() {
        val ids = setOf(EXPORT_DIR_CHAR, EXPORT_DIR_ACTION, EXPORT_DIR_QR, EXPORT_DIR_CARDBACK)
        assertEquals(4, ids.size)
    }
}
