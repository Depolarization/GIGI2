// 新版本 APK 的**系统下载器**通道（V40-D / V52）。
//
// 为什么要单独做一条通道：原先「去下载」只做 `ACTION_VIEW`（交给浏览器打开 URL）。
// 维护者在 update.json / release 资产里给的是 GitHub 直链，本机实测（2026-09-30，curl -m 20）：
//   api.github.com                     200（1.4s）  ⇒ 检查更新可用
//   cdn.jsdelivr.net / ghfast / gh-proxy 200        ⇒ 静态文件镜像可用
//   raw.githubusercontent.com          000          ⇒ 直连不可用（故检查走镜像）
//   github.com/.../releases/download/  000（超时）  ⇒ **APK 直链本身也不可达**
// 浏览器只是把同一个 URL 换个进程去连，**不会改变可达性**；换成系统下载器同样如此。
// 所以本文件的职责是「把下载这件事交给系统（通知栏进度、断点续传、装完可点开安装）」，
// 而**不是**「让不可达的源变可达」。
//
// V52 供应链口径：
//   · 允许对 GitHub Release 资产套 ghfast.top / gh-proxy.com 前缀（国内直连实测 16KB/s）；
//   · 但前提是清单带 sha256，下载完成后**逐字节校验**，不匹配立即删除文件与通知；
//   · 清单缺 sha256 时绝不套镜像（见 UpdateUrlPolicy.downloadCandidates）。
//   镜像因此只能替换传输通道，不能替换安装包内容。

package com.gigi.tcg.data.github

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import com.gigi.tcg.R
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** APK 的 MIME：给对了，下载完成后点通知才会走安装器而不是「没有应用可打开」 */
private const val APK_MIME = "application/vnd.android.package-archive"

/** 文件名里的非法字符（DownloadManager 不校验，脏文件名会让通知点不开） */
private val ILLEGAL_FILE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")

object UpdateDownloader {
    /** 一次下载任务：保留候选链，失败时自动换下一个镜像 */
    private data class PendingDownload(
        val candidates: List<String>,
        val index: Int,
        val versionName: String?,
        val expectedSha256: String?,
    )

    private val pending = ConcurrentHashMap<Long, PendingDownload>()
    // 懒初始化：单测只跑 isDirectApk / fileNameFor，不能因为 object 初始化就去摸 Looper（JVM 上直接抛）
    private val worker: ExecutorService by lazy { Executors.newSingleThreadExecutor() }
    private val main: Handler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var receiverRegistered = false

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
     * @param candidates 候选下载地址，按顺序尝试（见 [UpdateUrlPolicy.downloadCandidates]）；
     *                   只接受过白名单的 https 地址，其余一律丢弃。
     * @param expectedSha256 清单里的 sha256；为空则不做校验，也不会套镜像。
     * @return 下载任务 id；**返回 null 一律表示没交给系统下载器**，调用方必须回落到浏览器，
     *         绝不能把它当成「已开始下载」而静默（DownloadManager 被停用、URI 非法、
     *         系统服务拿不到等都会走到这里，已 Log.w 留证）。
     */
    fun enqueue(
        context: Context,
        candidates: List<String>,
        versionName: String?,
        expectedSha256: String?,
    ): Long? {
        val usable = candidates
            .map { it.trim() }
            .filter { UpdateUrlPolicy.isTrustedDownloadUrl(it) }
            .distinct()
        if (usable.isEmpty()) {
            Log.w(TAG, "没有可用的下载地址（候选=${candidates.size}）")
            return null
        }
        val app = context.applicationContext
        ensureReceiver(app)
        return enqueueAt(app, usable, 0, versionName, expectedSha256)
    }

    private fun enqueueAt(
        context: Context,
        candidates: List<String>,
        index: Int,
        versionName: String?,
        expectedSha256: String?,
    ): Long? {
        val manager = context.getSystemService(DownloadManager::class.java)
        if (manager == null) {
            Log.w(TAG, "系统下载器不可用（getSystemService 返回 null）")
            return null
        }
        val url = candidates[index]
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
            pending[id] = PendingDownload(candidates, index, versionName, expectedSha256)
            Log.i(TAG, "已交给系统下载器 id=$id 候选=${index + 1}/${candidates.size} url=$url")
            id
        } catch (error: Exception) {
            Log.w(TAG, "系统下载器入队失败：${error.javaClass.simpleName}: ${error.message}")
            null
        }
    }

    private fun ensureReceiver(context: Context) {
        if (receiverRegistered) return
        synchronized(this) {
            if (receiverRegistered) return
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            // Android 13+ 注册非系统广播必须显式声明导出性；下载完成广播来自系统下载器进程
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(completionReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(completionReceiver, filter)
            }
            receiverRegistered = true
        }
    }

    private val completionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val job = pending.remove(id) ?: return
            val manager = context.getSystemService(DownloadManager::class.java) ?: return
            val status = queryStatus(manager, id)
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                retryOrFail(context, job, "status=$status")
                return
            }
            verify(context, manager, id, job)
        }
    }

    private fun queryStatus(manager: DownloadManager, id: Long): Int =
        try {
            manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                } else {
                    -1
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "查询下载状态失败：${error.javaClass.simpleName}")
            -1
        }

    /**
     * 下载完成后校验 sha256。
     * 清单没给 sha256 时不做校验（此时候选链只有官方直链，见 UpdateUrlPolicy）；
     * 校验失败必须删除文件与通知，避免用户从通知栏把被替换的包装上。
     */
    private fun verify(
        context: Context,
        manager: DownloadManager,
        id: Long,
        job: PendingDownload,
    ) {
        val expected = job.expectedSha256?.trim()?.lowercase()
        if (expected.isNullOrEmpty()) {
            Log.w(TAG, "清单无 sha256，跳过校验（未套镜像）")
            return
        }
        worker.execute {
            val actual = try {
                manager.openDownloadedFile(id)?.use(::sha256Of)
            } catch (error: Exception) {
                Log.w(TAG, "读取下载文件失败：${error.javaClass.simpleName}")
                null
            }
            if (actual != null && actual.equals(expected, ignoreCase = true)) {
                Log.i(TAG, "安装包校验通过 sha256=$actual")
                toast(context, R.string.about_update_download_verified)
                return@execute
            }
            Log.w(TAG, "安装包校验失败 expected=$expected actual=$actual，删除下载")
            manager.remove(id)
            toast(context, R.string.about_update_download_hash_failed)
        }
    }

    private fun retryOrFail(context: Context, job: PendingDownload, reason: String) {
        val next = job.index + 1
        if (next >= job.candidates.size) {
            Log.w(TAG, "全部下载候选失败（$reason）")
            toast(context, R.string.about_update_download_failed)
            return
        }
        Log.w(TAG, "候选 ${job.index + 1} 失败（$reason），改用 ${job.candidates[next]}")
        if (enqueueAt(context, job.candidates, next, job.versionName, job.expectedSha256) == null) {
            toast(context, R.string.about_update_download_failed)
        }
    }

    private fun sha256Of(descriptor: ParcelFileDescriptor): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(descriptor.fileDescriptor).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun toast(context: Context, resId: Int) {
        main.post { Toast.makeText(context, context.getString(resId), Toast.LENGTH_LONG).show() }
    }

    private val TAG: String = "GIGI.UpdateDownloader"
}
