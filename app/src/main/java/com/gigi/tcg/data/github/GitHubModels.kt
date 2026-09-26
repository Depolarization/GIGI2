// GitHub 基础设施数据模型 + 版本/公告判定纯逻辑（V9-D）。
// 全部判定写成不依赖 Android 的纯函数：既可 JVM 单测覆盖边界，也让 UI 层「只渲染、不重算」
// （与本工程 domain/ 层的既有分工一致）。

package com.gigi.tcg.data.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GitHub Release（只用需要的字段，其余忽略） */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("name") val name: String? = null,
    @SerialName("body") val body: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("draft") val draft: Boolean = false,
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
)

/** 仓库内静态公告文件（main 分支 announcement.json），走镜像拉 */
@Serializable
data class Announcement(
    @SerialName("id") val id: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("body") val body: String = "",
    @SerialName("publishedAt") val publishedAt: String? = null,
    @SerialName("url") val url: String? = null,
    /** 同一 id 不重复提示；调用方负责记已读 */
    @SerialName("minVersion") val minVersion: String? = null,
    @SerialName("maxVersion") val maxVersion: String? = null,
)

/** 公告文件容器：announcement.json 允许写成 {"announcements":[...]}，数组形式由调用方直接解 List */
@Serializable
data class AnnouncementList(
    @SerialName("announcements") val announcements: List<Announcement> = emptyList(),
)

/** 仓库内静态版本文件（main 分支 update.json） */
@Serializable
data class UpdateInfo(
    @SerialName("versionName") val versionName: String = "",
    @SerialName("versionCode") val versionCode: Int = 0,
    @SerialName("body") val body: String? = null,
    @SerialName("downloadUrl") val downloadUrl: String? = null,
    @SerialName("publishedAt") val publishedAt: String? = null,
)

// ===== 纯逻辑判定 =====

/** 语义化版本三元组（自带序比较，避免裸 Triple 无 compareTo） */
data class VersionTriple(val major: Int, val minor: Int, val patch: Int) : Comparable<VersionTriple> {
    override fun compareTo(other: VersionTriple): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
}

/**
 * "v1.2.3" / "1.2.3-beta" → 版本号；无法解析返回 null。
 *
 * 宽松规则（宁可多解析成功，也别把正常版本判成 null）：忽略前缀 v/V；在 '-'（预发布标记）
 * 与 '+'（build metadata）处截断；缺位补 0（"1.2" == 1.2.0）；第四段起丢弃
 * （GitHub tag 偶有 1.2.3.4，不必为此放弃整条更新提示）。
 * 严格规则：主/次/修订任一段为空、非纯数字或溢出 Int → 整串判为不可解析，交由调用方兜底。
 */
fun parseVersion(raw: String?): VersionTriple? {
    var text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (text.startsWith("v") || text.startsWith("V")) {
        text = text.substring(1)
    }
    val core = text.substringBefore('-').substringBefore('+')
    if (core.isEmpty()) return null
    val parts = core.split('.')
    val segments = IntArray(3)
    for (index in 0 until 3) {
        val segment = parts.getOrNull(index) ?: "0"
        if (segment.isEmpty()) return null
        val value = segment.toIntOrNull() ?: return null
        if (value < 0) return null
        segments[index] = value
    }
    return VersionTriple(segments[0], segments[1], segments[2])
}

/** 版本序比较：a<b 负、a==b 零、a>b 正；任一不可解析返回 null（由调用方决定兜底方向） */
fun compareVersion(a: String?, b: String?): Int? {
    val left = parseVersion(a) ?: return null
    val right = parseVersion(b) ?: return null
    return left.compareTo(right)
}

/**
 * remote > current 才算有更新；无法解析任一侧时返回 false。
 * 兜底方向选「不报」：误报有更新会把用户推进一次注定失败的下载，代价远高于漏报一次。
 */
fun isNewerVersion(remote: String?, current: String?): Boolean =
    compareVersion(remote, current)?.let { it > 0 } ?: false

/**
 * Announcement 是否应展示给当前版本。
 * 规则：minVersion 为空或 current >= minVersion，且 maxVersion 为空或 current <= maxVersion。
 * 兜底方向与 isNewerVersion 相反——版本判不出来时返回 true：漏掉一条「接口已变更、本版本不可用」
 * 的公告比多弹一条无关公告严重得多。
 */
fun shouldShowAnnouncement(ann: Announcement, currentVersion: String?): Boolean {
    val min = ann.minVersion?.takeIf { it.isNotBlank() }
    val max = ann.maxVersion?.takeIf { it.isNotBlank() }
    if (min == null && max == null) return true
    val current = parseVersion(currentVersion) ?: return true
    if (min != null) {
        val minParsed = parseVersion(min) ?: return true
        if (current < minParsed) return false
    }
    if (max != null) {
        val maxParsed = parseVersion(max) ?: return true
        if (current > maxParsed) return false
    }
    return true
}
