// 更新下载通道的口径锁（V40-D，源码文本级）。
//
// 为什么要锁：国内访问 GitHub 不稳定（本机实测 api.github.com 200、github.com 直链 000），
// 「点去下载什么都没发生」是最容易被忽略的缺陷。这里锁死三条纪律：
//   1) 优先交给**系统下载器**（不是一律丢浏览器）；
//   2) 非直链 / 系统下载器不可用 ⇒ 必须回落浏览器，**绝不静默**；
//   3) 提示文案三语齐全（缺语种 ⇒ 直接拿到未翻译的英文或空串）。

package com.gigi.tcg.ui.dialogs.about

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDownloadFallbackTest {

    private val resRoot = "src/main/res/"
    private val dialogSrc: String = File("src/main/java/com/gigi/tcg/ui/dialogs/about/AboutDialog.kt").readText()

    @Test
    fun downloadUsesSystemDownloaderFirst() {
        assertTrue("应优先交给系统下载器", dialogSrc.contains("UpdateDownloader.enqueue("))
        assertTrue("只有文件直链才走下载器", dialogSrc.contains("UpdateDownloader.isDirectApk(target)"))
    }

    @Test
    fun nonDirectOrUnavailableFallsBackToBrowser() {
        assertTrue("非直链回落浏览器", dialogSrc.contains("!UpdateDownloader.isDirectApk(target) -> onOpenUrl(target)"))
        assertTrue("入队失败也要回落浏览器", dialogSrc.contains("if (!startSystemDownload()) onOpenUrl(target)"))
    }

    /** Q- 写公共 Download 目录要运行时权限；被拒也必须回落浏览器，不能卡死在"点了没反应" */
    @Test
    fun legacyStoragePermissionPathFallsBack() {
        assertTrue(dialogSrc.contains("Manifest.permission.WRITE_EXTERNAL_STORAGE"))
        assertTrue(dialogSrc.contains("requiresWriteExternalPermission()"))
        assertTrue(dialogSrc.contains("hasWriteExternalPermission(context)"))
    }

    /**
     * 不让 APK 直链自动套第三方镜像前缀（中间人可替换安装包），镜像只用于只读静态文本。
     * 🔴 只查**代码行**：本文件的 KDoc 必然要写清「为什么不套镜像」，注释里出现这些域名是正常的。
     */
    @Test
    fun apkDownloadMustNotBeMirrored() {
        val updater = File("src/main/java/com/gigi/tcg/data/github/UpdateDownloader.kt").readText()
        val code = updater.lineSequence().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        listOf("gh-proxy", "ghfast", "ghproxy", "jsdelivr", "GitHubMirror").forEach { host ->
            assertTrue("APK 下载不许自动套镜像前缀 $host", !code.contains(host))
        }
    }

    @Test
    fun downloadStartedToastExistsInAllLocales() {
        listOf("values", "values-en", "values-zh-rTW").forEach { dir ->
            val text = File("$resRoot/$dir/strings.xml").readText()
            assertTrue("$dir 缺少 about_update_download_started", text.contains("name=\"about_update_download_started\""))
        }
    }
}
