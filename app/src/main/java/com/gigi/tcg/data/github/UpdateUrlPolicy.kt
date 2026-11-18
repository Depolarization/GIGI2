// 更新下载地址策略（V39-H / V52）。
// update.json 会经第三方镜像分发，不能把清单里的任意 URL 直接交给系统下载器。
// 主机校验一律用 HttpUrl 解析，而不是 startsWith/contains，
// 否则 https://github.com.evil.com 或 https://github.com@evil.com 会骗过字符串判断。
//
// V52 起允许下载走公共镜像（国内直连 GitHub 实测 16KB/s，4MB 包要 4 分钟），
// 但镜像只能承载 **GitHub Release 的 .apk 路径**，且必须有清单 sha256 在下载完成后校验：
// 镜像能改的只有传输通道，内容正确性由 sha256 兜底。

package com.gigi.tcg.data.github

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object UpdateUrlPolicy {
    /**
     * 允许的下载域名：
     * - GitHub Release 两种资产域名（github.com 会 302 到 objects/release-assets）
     * - jsDelivr 官方 CDN（为后续 @tag 分发 APK 留口）
     */
    private val TRUSTED_HOSTS = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "codeload.github.com",
        "cdn.jsdelivr.net",
    )

    /** 允许承载 GitHub Release 资产的下载镜像（顺序即候选优先级） */
    private val MIRROR_HOSTS = listOf("ghfast.top", "gh-proxy.com")

    /** `/owner/repo/releases/download/<tag>/<file>.apk`：只认这类路径，不给镜像开放整域 */
    private val GITHUB_RELEASE_APK = Regex(
        "^/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+/releases/download/.+\\.apk$",
        RegexOption.IGNORE_CASE,
    )

    /** 仅接受 https + 精确主机名；端口不限（GitHub 默认 443 会显式出现在 HttpUrl 中） */
    fun isTrustedDownloadUrl(raw: String?): Boolean {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return false
        val url = text.toHttpUrlOrNull() ?: return false
        if (url.scheme != "https") return false
        if (url.host in TRUSTED_HOSTS) return true
        return isMirroredGithubReleaseApk(text)
    }

    /**
     * 下载候选（顺序即尝试顺序）：镜像优先，官方直链兜底。
     *
     * 没有 sha256 时**不套镜像**——无法校验的第三方传输没有意义，只给官方直链。
     * 清单里已经是镜像地址（维护者手写）时原样返回，不再二次套壳。
     */
    fun downloadCandidates(canonicalUrl: String?, sha256: String?): List<String> {
        val text = canonicalUrl?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        if (!isTrustedDownloadUrl(text)) return listOf(text)
        if (sha256.isNullOrBlank()) return listOf(text)
        if (!isGitHubReleaseApk(text)) return listOf(text)
        return MIRROR_HOSTS.map { "https://$it/$text" } + text
    }

    private fun isMirroredGithubReleaseApk(text: String): Boolean =
        MIRROR_HOSTS.any { host ->
            text.startsWith("https://$host/") &&
                isGitHubReleaseApk(text.removePrefix("https://$host/"))
        }

    private fun isGitHubReleaseApk(text: String): Boolean {
        val url = text.toHttpUrlOrNull() ?: return false
        return url.scheme == "https" &&
            url.host == "github.com" &&
            GITHUB_RELEASE_APK.matches(url.encodedPath)
    }
}
