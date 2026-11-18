// V39-H 更新链路纯逻辑单测：
// 1) 多镜像都成功时按 publishedAt/versionCode 择优（不能信「第一个 200」）；
// 2) 清单下载地址白名单（防 github.com.evil.com / user@host 之类伪装）。

package com.gigi.tcg.data.github

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateSelectionTest {

    private fun mirrored(
        source: String,
        versionName: String,
        versionCode: Int,
        publishedAt: String?,
    ) = MirroredFetch(
        UpdateInfo(
            versionName = versionName,
            versionCode = versionCode,
            publishedAt = publishedAt,
        ),
        source,
    )

    @Test
    fun `publishedAt 最新者优先 即使 versionCode 更低`() {
        val jsDelivr = mirrored("mirror:jsDelivr", "1.0.3", 3, "2026-10-14T12:52:10Z")
        val ghfast = mirrored("mirror:ghfast.top", "1.0.2", 2, "2026-10-15T00:00:00Z")

        assertEquals("mirror:ghfast.top", pickNewestUpdate(listOf(jsDelivr, ghfast)).source)
    }

    @Test
    fun `publishedAt 相同或缺省时按 versionCode 取大`() {
        val sameTime = mirrored("mirror:jsDelivr", "1.0.2", 2, "2026-10-14T12:52:10Z")
        val newerCode = mirrored("mirror:ghfast.top", "1.0.3", 3, "2026-10-14T12:52:10Z")
        assertEquals(
            "mirror:ghfast.top",
            pickNewestUpdate(listOf(sameTime, newerCode)).source,
        )

        val noTimeOld = mirrored("mirror:jsDelivr", "1.0.2", 2, null)
        val noTimeNew = mirrored("mirror:ghfast.top", "1.0.3", 3, null)
        assertEquals(
            "mirror:ghfast.top",
            pickNewestUpdate(listOf(noTimeOld, noTimeNew)).source,
        )
    }

    @Test
    fun `全相同时保持候选原顺序`() {
        val first = mirrored("mirror:jsDelivr", "1.0.2", 3, "2026-10-14T12:52:10Z")
        val second = mirrored("mirror:ghfast.top", "1.0.2", 3, "2026-10-14T12:52:10Z")
        assertEquals("mirror:jsDelivr", pickNewestUpdate(listOf(first, second)).source)
    }

    @Test
    fun `有 publishedAt 的候选优先于完全缺省时间戳的候选`() {
        val noTime = mirrored("mirror:jsDelivr", "9.9.9", 999, null)
        val withTime = mirrored("mirror:ghfast.top", "1.0.2", 2, "2026-10-14T12:52:10Z")
        assertEquals(
            "mirror:ghfast.top",
            pickNewestUpdate(listOf(noTime, withTime)).source,
        )
    }

    // ===== 下载地址白名单 =====

    @Test
    fun `官方 GitHub 与 jsDelivr 下载地址放行`() {
        assertTrue(
            UpdateUrlPolicy.isTrustedDownloadUrl(
                "https://github.com/Depolarization/GIGI2/releases/download/v1.0.2/app-release.apk",
            ),
        )
        assertTrue(
            UpdateUrlPolicy.isTrustedDownloadUrl(
                "https://objects.githubusercontent.com/github-production-release-asset/x.apk",
            ),
        )
        assertTrue(
            UpdateUrlPolicy.isTrustedDownloadUrl(
                "https://release-assets.githubusercontent.com/github-production-release-asset/x.apk",
            ),
        )
        assertTrue(
            UpdateUrlPolicy.isTrustedDownloadUrl(
                "https://cdn.jsdelivr.net/gh/Depolarization/GIGI2@v1.0.2/dist/app-release.apk",
            ),
        )
        // 显式端口也应通过（HttpUrl 会保留 :443）
        assertTrue(
            UpdateUrlPolicy.isTrustedDownloadUrl(
                "https://github.com:443/Depolarization/GIGI2/releases/download/v1.0.2/app-release.apk",
            ),
        )
    }

    @Test
    fun `后缀伪装 userinfo 伪装与 http 一律拒绝`() {
        assertFalse(UpdateUrlPolicy.isTrustedDownloadUrl("https://github.com.evil.com/x.apk"))
        assertFalse(UpdateUrlPolicy.isTrustedDownloadUrl("https://github.com@evil.com/x.apk"))
        assertFalse(UpdateUrlPolicy.isTrustedDownloadUrl("https://evil.com/github.com/x.apk"))
        assertFalse(UpdateUrlPolicy.isTrustedDownloadUrl("http://github.com/x.apk"))
        assertFalse(UpdateUrlPolicy.isTrustedDownloadUrl("https://ghfast.top/https://github.com/x.apk"))
        assertFalse(UpdateUrlPolicy.isTrustedDownloadUrl(""))
        assertFalse(UpdateUrlPolicy.isTrustedDownloadUrl(null))
        assertFalse(UpdateUrlPolicy.isTrustedDownloadUrl("not a url"))
    }

    // ===== 仓库内清单文件的契约 =====
    // Gradle 单测工作目录是 app/，仓库根目录在上一级

    private val manifestJson = Json { ignoreUnknownKeys = true }

    @Test
    fun `仓库根 update_json 可被客户端解析且字段自洽`() {
        val file = File("../update.json")
        assertTrue("清单文件不存在: ${file.absolutePath}", file.exists())
        val info = manifestJson.decodeFromString(UpdateInfo.serializer(), file.readText())

        assertTrue("versionName 不能为空", info.versionName.isNotBlank())
        assertTrue("versionCode 必须为正", info.versionCode > 0)
        assertTrue("publishedAt 必填（多镜像择优依赖它）", !info.publishedAt.isNullOrBlank())
        assertTrue("downloadUrl 必须过白名单", UpdateUrlPolicy.isTrustedDownloadUrl(info.downloadUrl))
        assertTrue("sha256 必须是 64 位十六进制", info.sha256?.matches(Regex("[0-9a-f]{64}")) == true)
        assertTrue("size 必须为正", (info.size ?: 0) > 0)
    }

    @Test
    fun `仓库根 announcement_json 缺失时公告镜像路径会 404 所以必须存在`() {
        val file = File("../announcement.json")
        assertTrue("公告文件不存在: ${file.absolutePath}", file.exists())
        val list = manifestJson.decodeFromString(AnnouncementList.serializer(), file.readText())
        assertTrue("当前无公告，应为空列表", list.announcements.isEmpty())
    }
}
