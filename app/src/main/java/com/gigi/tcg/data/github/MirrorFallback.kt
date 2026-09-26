// GitHub 静态资源镜像回退链（V9-D）。
// 设计要点：GitHub 的 Release/Issue 数据走 api.github.com（实测国内直连 200，无需镜像）；
// 只有「读仓库里的静态 JSON/文本文件」必须走镜像，因为 raw.githubusercontent.com 国内直连超时（实测 000）。
// 顺序即降级顺序，按 2026-09-26 `curl -m 15` 实测稳定性排：jsDelivr（官方 CDN，最稳）→ gh-proxy → ghfast.top → ghproxy.net。

package com.gigi.tcg.data.github

/**
 * GitHub 静态资源的国内可达镜像，按实测稳定性排序。
 * raw.githubusercontent.com 国内直连超时（实测 000），必须走镜像。
 */
object GitHubMirror {
    const val REPO_OWNER = "Depolarization"
    const val REPO_NAME = "GIGI2"
    const val DEFAULT_BRANCH = "main"

    /** jsDelivr 官方 CDN，实测 200，最稳，排第一 */
    fun jsDelivr(path: String, ref: String = DEFAULT_BRANCH): String =
        "https://cdn.jsdelivr.net/gh/$REPO_OWNER/$REPO_NAME@$ref/$path"

    /** gh-proxy 实测 200 */
    fun ghProxy(path: String, ref: String = DEFAULT_BRANCH): String =
        "https://gh-proxy.com/https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/$ref/$path"

    /** ghfast.top 实测 200 */
    fun ghFastTop(path: String, ref: String = DEFAULT_BRANCH): String =
        "https://ghfast.top/https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/$ref/$path"

    /** ghproxy.net 实测 200 */
    fun ghProxyNet(path: String, ref: String = DEFAULT_BRANCH): String =
        "https://ghproxy.net/https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/$ref/$path"

    /** 按优先级返回全部候选（顺序即尝试顺序） */
    fun candidates(path: String, ref: String = DEFAULT_BRANCH): List<String> =
        listOf(jsDelivr(path, ref), ghProxy(path, ref), ghFastTop(path, ref), ghProxyNet(path, ref))

    /**
     * 从候选 URL 反解镜像名，用于 UpdateCheckResult.source（例：`mirror:jsDelivr`）。
     * 排障时要知道数据究竟从哪个镜像拿到的——不同镜像的缓存刷新时机不一致，
     * 「用户说没更新」先看这一项。
     */
    fun labelOf(url: String): String = when {
        url.startsWith("https://cdn.jsdelivr.net/") -> "jsDelivr"
        url.startsWith("https://gh-proxy.com/") -> "gh-proxy"
        url.startsWith("https://ghfast.top/") -> "ghfast.top"
        url.startsWith("https://ghproxy.net/") -> "ghproxy.net"
        else -> "unknown"
    }
}

/** 官方 GitHub API（实测国内直连 200，不加镜像） */
const val GITHUB_API_BASE = "https://api.github.com"

/** 仓库主页 / Releases 页：没有 release 资产直链时的兜底下载入口 */
const val GITHUB_REPO_URL = "https://github.com/${GitHubMirror.REPO_OWNER}/${GitHubMirror.REPO_NAME}"
const val GITHUB_RELEASES_URL = "$GITHUB_REPO_URL/releases"
const val GITHUB_ISSUES_URL = "$GITHUB_REPO_URL/issues"

/** 仓库内约定的静态数据文件（main 分支），由维护者手工维护，见 README「仓库内维护文件」 */
const val UPDATE_INFO_PATH = "update.json"
const val ANNOUNCEMENT_PATH = "announcement.json"
