// GitHub 静态资源镜像回退链（V9-D / V39-H）。
// 设计要点：GitHub 的 Release/Issue 数据走 api.github.com（实测国内直连 200，无需镜像）；
// 只有「读仓库里的静态 JSON/文本文件」必须走镜像，因为 raw.githubusercontent.com 国内直连超时（实测 000）。
// V39-H 起改为**并行竞速**（见 GitHubApi.fetchJsonCandidates），这里的顺序只用于同分时的稳定兜底；
// 2026-11-18 实测 ghproxy.net 已明显变慢，移出候选，保留 jsDelivr / gh-proxy / ghfast.top 三个。

package com.gigi.tcg.data.github

/**
 * GitHub 静态资源的国内可达镜像。
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

    /** 全部候选（顺序即同分时的稳定优先级） */
    fun candidates(path: String, ref: String = DEFAULT_BRANCH): List<String> =
        listOf(jsDelivr(path, ref), ghProxy(path, ref), ghFastTop(path, ref))

    /**
     * 从候选 URL 反解镜像名，用于 UpdateCheckResult.source（例：`mirror:jsDelivr`）。
     * 排障时要知道数据究竟从哪个镜像拿到的——不同镜像的缓存刷新时机不一致，
     * 「用户说没更新」先看这一项。
     */
    fun labelOf(url: String): String = when {
        url.startsWith("https://cdn.jsdelivr.net/") -> "jsDelivr"
        url.startsWith("https://gh-proxy.com/") -> "gh-proxy"
        url.startsWith("https://ghfast.top/") -> "ghfast.top"
        else -> "unknown"
    }
}

/** 官方 GitHub API（实测国内直连 200，不加镜像） */
const val GITHUB_API_BASE = "https://api.github.com"

/** 仓库主页 / Releases 页：没有 release 资产直链时的兜底下载入口 */
const val GITHUB_REPO_URL = "https://github.com/${GitHubMirror.REPO_OWNER}/${GitHubMirror.REPO_NAME}"
const val GITHUB_RELEASES_URL = "$GITHUB_REPO_URL/releases"
const val GITHUB_ISSUES_URL = "$GITHUB_REPO_URL/issues"

/** 仓库内约定的静态数据文件（main 分支）：
 * - `update.json`：发 Release 时由 `.github/workflows/update-manifest.yml` 自动生成并提交；
 * - `announcement.json`：手工维护（无公告时保持 `{"announcements":[]}`），二者都必须存在，
 *   否则镜像路径会 404，更新检查只能落到 api.github.com（国内部分地区的慢路径）。 */
const val UPDATE_INFO_PATH = "update.json"
const val ANNOUNCEMENT_PATH = "announcement.json"
