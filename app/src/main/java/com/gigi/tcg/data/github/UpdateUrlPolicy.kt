// 更新下载地址白名单（V39-H）。
// update.json 会经第三方镜像分发，不能把清单里的任意 URL 直接交给系统下载器。
// 这里只做「主机 + https」级别的精确校验：用 HttpUrl 解析，而不是 startsWith/contains，
// 否则 https://github.com.evil.com 或 https://github.com@evil.com 会骗过字符串判断。

package com.gigi.tcg.data.github

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object UpdateUrlPolicy {
    /**
     * 允许的下载域名：
     * - GitHub Release 两种资产域名（github.com 会 302 到 objects/release-assets）
     * - jsDelivr 官方 CDN（为后续 @tag 分发 APK 留口）
     *
     * 🔴 不把公共 GitHub 代理放进白名单：清单本身走镜像，下载地址必须由维护者显式写死；
     * 公共代理可用性和内容安全都不受控（详见 UpdateDownloader 的供应链说明）。
     */
    private val TRUSTED_HOSTS = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "codeload.github.com",
        "cdn.jsdelivr.net",
    )

    /** 仅接受 https + 精确主机名；端口不限（GitHub 默认 443 会显式出现在 HttpUrl 中） */
    fun isTrustedDownloadUrl(raw: String?): Boolean {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return false
        val url = text.toHttpUrlOrNull() ?: return false
        return url.scheme == "https" && url.host in TRUSTED_HOSTS
    }
}
