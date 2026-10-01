// 系统下载器通道的判据单测（V40-D，纯 JVM）。
// 只测**判据**（是不是文件直链、落盘文件名是否合法）——enqueue 需要真机 DownloadManager，不进单测。

package com.gigi.tcg.data.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDownloaderTest {

    /** GitHub release 资产直链：这是真实存在的形态（实测 v1.0.0 的 app-release.apk） */
    @Test
    fun releaseAssetIsDirectApk() {
        assertTrue(
            UpdateDownloader.isDirectApk(
                "https://github.com/Depolarization/GIGI2/releases/download/v1.0.0/app-release.apk",
            ),
        )
    }

    /** 大小写与 query 不参与后缀判定，但都不能误判 */
    @Test
    fun suffixCheckIgnoresCaseAndQuery() {
        assertTrue(UpdateDownloader.isDirectApk("https://example.com/GIGI.APK"))
        assertTrue(UpdateDownloader.isDirectApk("https://example.com/a.apk?download=1#x"))
        assertFalse("zip 不是安装包", UpdateDownloader.isDirectApk("https://example.com/a.apk.zip"))
    }

    /** 页面型 URL 必须交给浏览器，不能假装是直链（否则下载到的是 HTML） */
    @Test
    fun htmlPagesAreNotDirectApk() {
        assertFalse(UpdateDownloader.isDirectApk("https://github.com/Depolarization/GIGI2/releases"))
        assertFalse(UpdateDownloader.isDirectApk("https://github.com/Depolarization/GIGI2/releases/tag/v1.0.0"))
        assertFalse(UpdateDownloader.isDirectApk(GITHUB_RELEASES_URL))
        assertFalse(UpdateDownloader.isDirectApk(null))
        assertFalse(UpdateDownloader.isDirectApk(""))
    }

    /** 文件名带版本号：连点几次更新不会互相覆盖；版本缺失时不凭空造版本 */
    @Test
    fun fileNameCarriesVersion() {
        assertEquals("GIGI-1.0.1.apk", UpdateDownloader.fileNameFor("1.0.1"))
        assertEquals("GIGI.apk", UpdateDownloader.fileNameFor(null))
        assertEquals("GIGI.apk", UpdateDownloader.fileNameFor("  "))
    }

    /** 版本号来自远端 JSON，可能是脏字符串 ⇒ 文件名必须只剩合法字符（脏文件名会让通知点不开） */
    @Test
    fun fileNameIsAlwaysLegal() {
        val name = UpdateDownloader.fileNameFor("v1.0.0 (beta)/测试")
        assertTrue("文件名只允许 A-Za-z0-9._-，实际 $name", name.matches(Regex("[A-Za-z0-9._-]+\\.apk")))
        assertTrue(name.startsWith("GIGI-"))
    }
}
