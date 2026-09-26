// 更新检查（V9-D，数据层预留，UI 接线由后续棒做）。
// 双路径设计：main 分支 update.json 走镜像（优先：jsDelivr 实测最快最稳，且能带 APK 直链），
// 失败再回落 api.github.com/releases/latest（直连实测 200，但只有真的发过 release 才有效）。
// 「无更新」是正常路径不抛异常；只有两条路径全失败才抛，由调用方决定静默还是提示。

package com.gigi.tcg.data.github

import android.util.Log
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/** 更新检查结果。source 用于排障：同样是「没更新」，镜像给的结论和 API 给的结论可信度不同 */
data class UpdateCheckResult(
    val hasUpdate: Boolean,
    val latestVersionName: String?,
    val latestVersionCode: Int?,
    val releaseNotes: String?,
    val downloadUrl: String?,
    val source: String,
)

class UpdateChecker(
    private val api: GitHubApi,
    private val currentVersionName: String,
    private val currentVersionCode: Int,
) {
    /** 与 MihoyoClient / AuthManager 一致的注入形态：容器里只有 OkHttpClient + Json */
    constructor(
        http: OkHttpClient,
        json: Json,
        currentVersionName: String,
        currentVersionCode: Int,
    ) : this(GitHubApi(http, json), currentVersionName, currentVersionCode)

    /** 走 api.github.com/repos/{owner}/{repo}/releases/latest（实测直连 200） */
    suspend fun checkGitHubRelease(): GitHubRelease = api.fetchLatestRelease()

    /** 走镜像读 main 分支 update.json（没发 release 时的备份路径） */
    suspend fun fetchUpdateInfoMirror(): UpdateInfo = api.fetchUpdateInfo().value

    /**
     * 综合判定：镜像优先，失败回落官方 API。
     * 任一路径给出结论即返回，不再打第二个请求（两条都问一遍只会加倍慢）。
     */
    suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) {
        val mirror = ioCatching { api.fetchUpdateInfo() }
        val mirrored = mirror.getOrNull()
        if (mirrored == null) {
            logFailure("update.json 镜像路径", mirror.exceptionOrNull())
        } else {
            val info = mirrored.value
            val hasUpdate = decideUpdate(info)
            Log.i(
                TAG,
                "${mirrored.source} 远端=${info.versionName}/${info.versionCode} 本机=$currentVersionName/$currentVersionCode → hasUpdate=$hasUpdate",
            )
            return@withContext UpdateCheckResult(
                hasUpdate = hasUpdate,
                latestVersionName = info.versionName.takeIf { it.isNotBlank() },
                latestVersionCode = info.versionCode.takeIf { it > 0 },
                releaseNotes = info.body,
                downloadUrl = info.downloadUrl?.takeIf { it.isNotBlank() } ?: GITHUB_RELEASES_URL,
                source = mirrored.source,
            )
        }

        val release = ioCatching { api.fetchLatestRelease() }
        // 仓库还没发过 release 时 GitHub 回 404，那是「暂无版本」的正常状态，不算故障
        if (release.exceptionOrNull()?.let(::isNotFound) == true) {
            Log.i(TAG, "仓库尚无 release，无更新可判")
            return@withContext UpdateCheckResult(false, null, null, null, GITHUB_RELEASES_URL, SOURCE_NONE)
        }
        val latest = release.getOrNull()
        if (latest != null) {
            val hasUpdate = !latest.draft && isNewerVersion(latest.tagName, currentVersionName)
            Log.i(TAG, "$SOURCE_API tag=${latest.tagName} 本机=$currentVersionName → hasUpdate=$hasUpdate")
            return@withContext UpdateCheckResult(
                hasUpdate = hasUpdate,
                latestVersionName = latest.tagName.takeIf { it.isNotBlank() },
                latestVersionCode = null,
                releaseNotes = latest.body,
                downloadUrl = latest.htmlUrl?.takeIf { it.isNotBlank() } ?: GITHUB_RELEASES_URL,
                source = SOURCE_API,
            )
        }
        logFailure("releases/latest 路径", release.exceptionOrNull())
        throw IOException(
            "更新检查失败：镜像与 API 两条路径均不可用（${mirror.exceptionOrNull()?.javaClass?.simpleName} / ${release.exceptionOrNull()?.javaClass?.simpleName}）",
        )
    }

    /**
     * 当前版本可见的公告（min/maxVersion 过滤）。
     * 「同 id 不再重复提示」的已读记录属于 UI/持久化职责，本方法不管，避免数据层藏状态。
     */
    suspend fun announcementsForCurrentVersion(): List<Announcement> =
        api.fetchAnnouncements().value.filter { shouldShowAnnouncement(it, currentVersionName) }

    /**
     * update.json 判定：versionName 可比时以它为准；判不出来（字段缺失或写成 "next" 之类）
     * 才退化到 versionCode。两条都给不出结论时返回 false（不误报）。
     */
    private fun decideUpdate(info: UpdateInfo): Boolean {
        if (info.versionName.isNotBlank()) {
            return isNewerVersion(info.versionName, currentVersionName)
        }
        return info.versionCode > 0 && info.versionCode > currentVersionCode
    }

    private fun isNotFound(error: Throwable): Boolean =
        error is IOException && error.message?.startsWith("HTTP 404") == true

    private fun logFailure(label: String, error: Throwable?) {
        if (error != null && error !is CancellationException) {
            Log.w(TAG, "$label 失败：${error.javaClass.simpleName}: ${error.message}")
        }
    }

    /**
     * 只兜网络/解析异常的 catch：runCatching 会连 CancellationException 一起吞掉，
     * 那样取消协程会被误判成「一次失败的请求」继续往下走，故必须原样上抛。
     */
    private inline fun <T> ioCatching(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            Result.failure(error)
        }

    companion object {
        const val TAG: String = "GIGI.UpdateChecker"
        const val SOURCE_API: String = "api:github"
        const val SOURCE_NONE: String = "none"
    }
}
