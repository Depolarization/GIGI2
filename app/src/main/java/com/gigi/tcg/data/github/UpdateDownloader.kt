// 新版本 APK 的**系统下载器**通道（V40-D）。
//
// 为什么要单独做一条通道：原先「去下载」只做 `ACTION_VIEW`（交给浏览器打开 URL）。
// 维护者在 update.json / release 资产里给的是 GitHub 直链，本机实测（2026-09-30，curl -m 20）：
//   api.github.com                     200（1.4s）  ⇒ 检查更新可用
//   cdn.jsdelivr.net / ghfast / gh-proxy 200        ⇒ 静态文件镜像可用
//   raw.githubusercontent.com          000          ⇒ 直连不可用（故检查走镜像）
//   github.com/.../releases/download/  000（超时）  ⇒ **APK 直链本身也不可达**
// 浏览器只是把同一个 URL 换个进程去连，**不会改变可达性**；换成系统下载器同样如此。
// 所以本文件的职责是「把下载这件事交给系统（通知栏进度、断点续传、装完可点开安装）」，
// 而**不是**「让不可达的源变可达」——可达性只能靠维护者在 update.json 里配一个国内可达的直链。
//
// 🔴 安全口径：绝不自动给 APK 直链套第三方镜像前缀（gh-proxy / ghfast 之类）。
// 那是让不可信中间人有机会替换安装包，属于供应链风险；镜像只用于**只读静态文本**（见 MirrorFallback.kt）。
// 需要镜像时，由维护者把**自己验证过的**完整直链写进 update.json 的 downloadUrl，本文件原样使用。

package com.gigi.tcg.data.github

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log

/** APK 的 MIME：给对了，下载完成后点通知才会走安装器而不是「没有应用可打开」 */
private const val APK_MIME = "application/vnd.android.package-archive"

/** 文件名里的非法字符（DownloadManager 不校验，脏文件名会让通知点不开） */
private val ILLEGAL_FILE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")

object UpdateDownloader {
    /**
     * 是否「可直接交给系统下载器的文件直链」。
     * 判据只看 path 的最后一段后缀：GitHub release 资产形如
     * `.../releases/download/v1.0.0/app-release.apk`，以 .apk 结尾 ⇒ 是文件；
     * `.../releases`（列表页）或 `.../releases/tag/v1.0.0`（详情页）不是文件 ⇒ 交给浏览器才对。
     * 大小写不敏感（.APK 同样算）；query / fragment 不参与判定。
     *
     * 🔴 刻意用纯字符串解析而**不用 android.net.Uri**：本工程单测是纯 JVM（无 Robolectric、
     * 未开 returnDefaultValues），Uri.parse 在 JVM 上直接抛 "not mocked"，这条判定就测不了了。
     */
    fun isDirectApk(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val withoutQuery = url.substringBefore('?').substringBefore('#')
        return withoutQuery.substringAfterLast('/').endsWith(".apk", ignoreCase = true)
    }

    /**
     * 落盘文件名 `GIGI-<version>.apk`：带版本号，用户连点几次更新也不会互相覆盖；
     * 版本号缺失时退回 `GIGI.apk`（不凭空造版本）。
     */
    fun fileNameFor(versionName: String?): String {
        val cleaned = ILLEGAL_FILE_NAME_CHARS.replace(versionName.orEmpty().trim(), "_").trim('_')
        return if (cleaned.isEmpty()) "GIGI.apk" else "GIGI-$cleaned.apk"
    }

    /**
     * 交给系统下载器（公共 Download 目录，完成后发通知，用户点通知即可安装）。
     *
     * @return 下载任务 id；**返回 null 一律表示没交给系统下载器**，调用方必须回落到浏览器，
     *         绝不能把它当成「已开始下载」而静默（DownloadManager 被停用、URI 非法、
     *         系统服务拿不到等都会走到这里，已 Log.w 留证）。
     */
    fun enqueue(context: Context, url: String, versionName: String?): Long? {
        if (!isDirectApk(url)) return null
        val manager = context.getSystemService(DownloadManager::class.java)
        if (manager == null) {
            Log.w(TAG, "系统下载器不可用（getSystemService 返回 null）")
            return null
        }
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(fileNameFor(versionName))
            setMimeType(APK_MIME)
            // 下载中显示通知、完成后保留通知（用户要点它安装）
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // 公共 Download 目录：Q+ 无需任何权限；Q- 需要 WRITE_EXTERNAL_STORAGE（调用方先申请）
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileNameFor(versionName))
        }
        return try {
            val id = manager.enqueue(request)
            Log.i(TAG, "已交给系统下载器 id=$id url=$url")
            id
        } catch (error: Exception) {
            Log.w(TAG, "系统下载器入队失败：${error.javaClass.simpleName}: ${error.message}")
            null
        }
    }

    private val TAG: String = "GIGI.UpdateDownloader"
}
