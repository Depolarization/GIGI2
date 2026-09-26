// V9-H：相册路径纯函数单测。核心断言是「父目录名不写死」——换任何 parent 输入都原样出现在结果里，
// 系统目录名一律由 Environment.DIRECTORY_* 在 Android 侧传入（本棒要求禁止 "Pictures" 字面量）。

package com.gigi.tcg.ui.dialogs.cardcover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
