// GitHubModels 纯逻辑与序列化容错单测（V9-D）。

package com.gigi.tcg.data.github

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubModelsTest {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    // ===== parseVersion =====

    @Test
    fun `parseVersion 接受 v 前缀与缺位补零`() {
        assertEquals(VersionTriple(1, 2, 3), parseVersion("v1.2.3"))
        assertEquals(VersionTriple(1, 2, 3), parseVersion("V1.2.3"))
        assertEquals(VersionTriple(1, 2, 0), parseVersion("1.2"))
        assertEquals(VersionTriple(1, 0, 0), parseVersion("1"))
        assertEquals(VersionTriple(0, 0, 0), parseVersion("0.0.0"))
    }

    @Test
    fun `parseVersion 截断预发布与 build 元数据`() {
        assertEquals(VersionTriple(1, 2, 3), parseVersion("1.2.3-beta"))
        assertEquals(VersionTriple(1, 2, 3), parseVersion("1.2.3-beta.1"))
        assertEquals(VersionTriple(1, 2, 3), parseVersion("1.2.3+build.9"))
        assertEquals(VersionTriple(1, 2, 3), parseVersion(" 1.2.3 "))
    }

    @Test
    fun `parseVersion 第四段起丢弃而非整体判失败`() {
        assertEquals(VersionTriple(1, 2, 3), parseVersion("1.2.3.4"))
    }

    @Test
    fun `parseVersion 无法解析时返回 null`() {
        assertNull(parseVersion(null))
        assertNull(parseVersion(""))
        assertNull(parseVersion("   "))
        assertNull(parseVersion("abc"))
        assertNull(parseVersion("1.2.x"))
        assertNull(parseVersion("1..3"))
        assertNull(parseVersion("v"))
        assertNull(parseVersion("-beta"))
        assertNull(parseVersion("1.-2.3"))
        assertNull(parseVersion("1.2.99999999999"))
    }

    // ===== isNewerVersion =====

    @Test
    fun `isNewerVersion 只在严格更大时为真`() {
        assertTrue(isNewerVersion("1.0.1", "1.0.0"))
        assertTrue(isNewerVersion("1.1.0", "1.0.9"))
        assertTrue(isNewerVersion("2.0.0", "1.9.9"))
        assertTrue(isNewerVersion("v1.2.3", "1.2.2"))
        assertTrue(isNewerVersion("1.2.3-beta", "1.2.2"))
        assertFalse(isNewerVersion("1.0.0", "1.0.0"))
        assertFalse(isNewerVersion("1.0.0", "1.0.1"))
    }

    @Test
    fun `isNewerVersion 任一侧不可解析时不误报`() {
        assertFalse(isNewerVersion(null, "1.0.0"))
        assertFalse(isNewerVersion("1.0.1", null))
        assertFalse(isNewerVersion("", "1.0.0"))
        assertFalse(isNewerVersion("next", "1.0.0"))
        assertFalse(isNewerVersion("1.0.1", "unknown"))
    }

    @Test
    fun `isNewerVersion 预发布标记不参与比较`() {
        // 已知取舍：1.2.3-beta 与 1.2.3 视为同版本，宁可不提示也不把预发布判成正式版更新
        assertFalse(isNewerVersion("1.2.3-beta", "1.2.3"))
        assertFalse(isNewerVersion("1.2.3", "1.2.3-beta"))
    }

    // ===== shouldShowAnnouncement =====

    private fun announcement(min: String? = null, max: String? = null) =
        Announcement(id = "a1", title = "t", body = "b", minVersion = min, maxVersion = max)

    @Test
    fun `无版本区间的公告恒显示`() {
        assertTrue(shouldShowAnnouncement(announcement(), "1.0.0"))
        assertTrue(shouldShowAnnouncement(announcement(), null))
        assertTrue(shouldShowAnnouncement(announcement(min = " ", max = ""), "1.0.0"))
    }

    @Test
    fun `公告按 min max 版本区间过滤`() {
        assertTrue(shouldShowAnnouncement(announcement(min = "1.0.0"), "1.0.0"))
        assertTrue(shouldShowAnnouncement(announcement(min = "1.0.0"), "1.2.0"))
        assertFalse(shouldShowAnnouncement(announcement(min = "1.1.0"), "1.0.9"))
        assertTrue(shouldShowAnnouncement(announcement(max = "1.5.0"), "1.5.0"))
        assertFalse(shouldShowAnnouncement(announcement(max = "1.4.9"), "1.5.0"))
        assertTrue(shouldShowAnnouncement(announcement(min = "1.0.0", max = "1.5.0"), "1.2.0"))
        assertFalse(shouldShowAnnouncement(announcement(min = "1.0.0", max = "1.5.0"), "1.6.0"))
    }

    @Test
    fun `版本判不出来时宁多显示也不漏公告`() {
        // 与 isNewerVersion 的兜底方向相反：漏掉「接口已变更」公告的代价更高
        assertTrue(shouldShowAnnouncement(announcement(min = "1.1.0"), null))
        assertTrue(shouldShowAnnouncement(announcement(min = "1.1.0"), "garbage"))
        assertTrue(shouldShowAnnouncement(announcement(min = "garbage"), "1.0.0"))
        assertTrue(shouldShowAnnouncement(announcement(max = "garbage"), "9.9.9"))
    }

    // ===== 序列化容错：服务端加字段不能崩 =====

    @Test
    fun `未知字段与类型漂移不导致解析崩溃`() {
        val release = json.decodeFromString(
            GitHubRelease.serializer(),
            """{"tag_name":"v1.2.3","body":"说明","html_url":"https://x","draft":false,
               "prerelease":false,"assets":[{"name":"a.apk"}],"new_field":{"nested":[1,2]}}""",
        )
        assertEquals("v1.2.3", release.tagName)
        assertEquals("说明", release.body)
        assertFalse(release.prerelease)

        val info = json.decodeFromString(
            UpdateInfo.serializer(),
            """{"versionName":"1.3.0","versionCode":2,"body":null,"downloadUrl":"https://y","extra":true}""",
        )
        assertEquals("1.3.0", info.versionName)
        assertEquals(2, info.versionCode)
        assertNull(info.body)
    }

    @Test
    fun `字段缺失时取默认值`() {
        val info = json.decodeFromString(UpdateInfo.serializer(), "{}")
        assertEquals("", info.versionName)
        assertEquals(0, info.versionCode)
        assertNull(info.downloadUrl)

        val announcement = json.decodeFromString(Announcement.serializer(), """{"id":"x","title":"标题"}""")
        assertEquals("x", announcement.id)
        assertEquals("", announcement.body)
        assertNull(announcement.minVersion)
    }

    @Test
    fun `公告列表两种顶层写法都能解`() {
        val wrapped = json.decodeFromString(
            AnnouncementList.serializer(),
            """{"announcements":[{"id":"a","title":"t","body":"b"}]}""",
        )
        assertEquals(1, wrapped.announcements.size)
        assertEquals("a", wrapped.announcements.first().id)

        val bare = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(Announcement.serializer()),
            """[{"id":"b","title":"t","body":"b"}]""",
        )
        assertEquals("b", bare.first().id)
    }

    @Test
    fun `compareVersion 给出三态`() {
        assertTrue((compareVersion("1.0.0", "1.0.1") ?: 0) < 0)
        assertEquals(0, compareVersion("v1.2.3", "1.2.3"))
        assertTrue((compareVersion("1.2.4", "1.2.3") ?: 0) > 0)
        assertNull(compareVersion("abc", "1.0.0"))
        assertNull(compareVersion("1.0.0", null))
    }
}
