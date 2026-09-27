// V9-H：相册路径纯函数单测。核心断言是「父目录名不写死」——换任何 parent 输入都原样出现在结果里，
// 系统目录名一律由 Environment.DIRECTORY_* 在 Android 侧传入（本棒要求禁止 "Pictures" 字面量）。
// V26：追加三参重载（类型子目录）与 exportDateText（yyyy-MM-dd）覆盖；日期用固定本地时刻断言，不依赖 UTC。

package com.gigi.tcg.ui.dialogs.cardcover

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumPathTest {

    @Test
    fun `标准路径为 Pictures 斜杠 GIGI`() {
        assertEquals("Pictures/GIGI", buildAlbumRelativePath("Pictures", "GIGI"))
    }

    @Test
    fun `父目录名来自入参而非写死 Pictures`() {
        assertEquals("Bilder/GIGI", buildAlbumRelativePath("Bilder", "GIGI"))
        assertEquals("DCIM/GIGI", buildAlbumRelativePath("DCIM", "GIGI"))
    }

    @Test
    fun `子目录名同样只来自入参`() {
        assertEquals("Pictures/GigiTCG", buildAlbumRelativePath("Pictures", "GigiTCG"))
    }

    @Test
    fun `末尾无多余斜杠且只有一个分隔符`() {
        val path = buildAlbumRelativePath("Pictures", "GIGI")
        assertFalse(path.endsWith("/"))
        assertEquals(1, path.count { it == '/' })
    }

    @Test
    fun `应用自有相册目录名为 GIGI`() {
        assertEquals("GIGI", GIGI_ALBUM_NAME)
    }

    @Test
    fun `三参重载在相册根下追加类型子目录`() {
        assertEquals("Pictures/GIGI/行动牌", buildAlbumRelativePath("Pictures", "GIGI", EXPORT_DIR_ACTION))
        assertEquals("Pictures/GIGI/角色牌", buildAlbumRelativePath("Pictures", "GIGI", EXPORT_DIR_CHAR))
        assertEquals("Bilder/GigiTCG/最近对局", buildAlbumRelativePath("Bilder", "GigiTCG", EXPORT_DIR_RECORDS))
    }

    @Test
    fun `三参重载空子目录不崩且为纯拼接`() {
        assertEquals("Pictures/GIGI/", buildAlbumRelativePath("Pictures", "GIGI", ""))
    }

    @Test
    fun `三参比二参恰好多一层分隔符`() {
        assertEquals(1, buildAlbumRelativePath("Pictures", "GIGI").count { it == '/' })
        assertEquals(2, buildAlbumRelativePath("Pictures", "GIGI", "行动牌").count { it == '/' })
    }

    @Test
    fun `导出分类目录名为固定中文`() {
        assertEquals("角色牌", EXPORT_DIR_CHAR)
        assertEquals("行动牌", EXPORT_DIR_ACTION)
        assertEquals("最近对局", EXPORT_DIR_RECORDS)
    }

    @Test
    fun `导出日期文本为 yyyy-MM-dd 形状`() {
        val text = exportDateText()
        assertEquals(10, text.length)
        assertTrue(text.matches(Regex("""\d{4}-\d{2}-\d{2}""")))
    }

    @Test
    fun `导出日期文本按本地时区对固定时刻精确取值`() {
        val fixedLocalMillis = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.SEPTEMBER, 27, 15, 30, 45)
        }.timeInMillis
        assertEquals("2026-09-27", exportDateText(fixedLocalMillis))
    }
}
