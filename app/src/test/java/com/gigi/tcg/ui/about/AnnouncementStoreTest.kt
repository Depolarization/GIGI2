// 公告「已读」判定与裁剪单测（V9-G）。
// 只测 ui/about/AnnouncementStore.kt 里的顶层纯函数：SharedPreferences 需要 Android 环境，
// 工程不引 Robolectric，故 AnnouncementStore 本身只做存取，判定全在纯函数里（与数据层同一分工）。
// 版本区间不在此重算一套——断言全部走 data.github.shouldShowAnnouncement 的语义，
// 数据层改了口径，这里必须一起红。

package com.gigi.tcg.ui.about

import com.gigi.tcg.data.github.Announcement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementStoreTest {

    private fun announcement(
        id: String,
        min: String? = null,
        max: String? = null,
    ): Announcement = Announcement(id = id, title = "标题$id", body = "正文$id", minVersion = min, maxVersion = max)

    // ===== shouldShowAnnouncementEntry =====

    @Test
    fun `未读且无版本限制的公告应展示`() {
        assertTrue(shouldShowAnnouncementEntry(announcement("a1"), "1.0.0", emptySet()))
    }

    @Test
    fun `已读过的同一 id 不再展示`() {
        assertFalse(shouldShowAnnouncementEntry(announcement("a1"), "1.0.0", setOf("a1")))
    }

    @Test
    fun `版本区间外的公告不展示`() {
        assertFalse(shouldShowAnnouncementEntry(announcement("a1", min = "2.0.0"), "1.0.0", emptySet()))
        assertFalse(shouldShowAnnouncementEntry(announcement("a1", max = "0.9.0"), "1.0.0", emptySet()))
        assertTrue(shouldShowAnnouncementEntry(announcement("a1", min = "1.0.0", max = "1.0.0"), "1.0.0", emptySet()))
    }

    @Test
    fun `本机版本判不出来时按数据层口径照样展示`() {
        // 漏一条「接口已变更」的公告比多弹一条严重，故兜底方向是 true
        assertTrue(shouldShowAnnouncementEntry(announcement("a1", min = "2.0.0"), "next", emptySet()))
        assertTrue(shouldShowAnnouncementEntry(announcement("a1", min = "2.0.0"), null, emptySet()))
    }

    @Test
    fun `空 id 的公告不展示`() {
        // 记不了已读：要么每次进页面都弹，要么与别的空 id 撞成同一条已读记录
        assertFalse(shouldShowAnnouncementEntry(announcement(""), "1.0.0", emptySet()))
        assertFalse(shouldShowAnnouncementEntry(announcement("   "), "1.0.0", emptySet()))
    }

    // ===== pendingAnnouncements =====

    @Test
    fun `重复 id 只留一条`() {
        val list = pendingAnnouncements(
            listOf(announcement("dup"), announcement("dup"), announcement("other")),
            "1.0.0",
            emptySet(),
        )
        assertEquals(listOf("dup", "other"), list.map { it.id })
    }

    @Test
    fun `已读与区间外的条目被滤掉且保持原顺序`() {
        val list = pendingAnnouncements(
            listOf(
                announcement("read"),
                announcement("old", max = "0.5.0"),
                announcement("keep", min = "1.0.0"),
                announcement(""),
            ),
            "1.0.0",
            setOf("read"),
        )
        assertEquals(listOf("keep"), list.map { it.id })
    }

    @Test
    fun `全部已读时队列为空`() {
        assertTrue(pendingAnnouncements(listOf(announcement("a"), announcement("b")), "1.0.0", setOf("a", "b")).isEmpty())
    }

    // ===== pruneReadIds =====

    @Test
    fun `超出上限时只保留最近的 id`() {
        val ids = (1..150).map { "a$it" }
        val pruned = pruneReadIds(ids, keep = 100)
        assertEquals(100, pruned.size)
        assertEquals("a51", pruned.first())
        assertEquals("a150", pruned.last())
    }

    @Test
    fun `未超上限时原样保留且不丢顺序`() {
        assertEquals(listOf("a", "b"), pruneReadIds(listOf("a", "b"), keep = 100))
    }

    @Test
    fun `prune 顺带去重与丢弃空白 id`() {
        assertEquals(listOf("a", "b"), pruneReadIds(listOf("a", "", "b", "a"), keep = 100))
    }

    @Test
    fun `keep 非正数时清空记录`() {
        assertTrue(pruneReadIds(listOf("a", "b"), keep = 0).isEmpty())
        assertTrue(pruneReadIds(listOf("a", "b"), keep = -1).isEmpty())
    }

    @Test
    fun `空列表裁剪仍是空`() {
        assertTrue(pruneReadIds(emptyList(), keep = 100).isEmpty())
    }
}
