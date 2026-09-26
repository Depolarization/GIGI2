// 公告「已读」持久化（V9-G）。
// 无自建服务器 ⇒ 公告正文是仓库 main 分支的 announcement.json（数据层走镜像读），
// 「这条我已经看过了」只能记在本机：同一 id 不再重复弹窗。
//
// 存储沿用 CredentialStore 的普通 SharedPreferences API（getSharedPreferences + MODE_PRIVATE），
// 不引入新依赖：公告 id 不敏感，不需要 Keystore 那套加密通道。
// 已读记成 JSON 数组而非 String Set —— SharedPreferences 的 StringSet 不保证顺序，
// 而 prune 的语义是「保留最近 N 条」，必须有插入顺序。

package com.gigi.tcg.ui.about

import android.content.Context
import com.gigi.tcg.data.github.Announcement
import com.gigi.tcg.data.github.shouldShowAnnouncement
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** 展示判定：版本区间匹配（复用数据层语义，本棒不重写一套）且该 id 未读过 */
fun shouldShowAnnouncementEntry(ann: Announcement, currentVersion: String?, readIds: Set<String>): Boolean =
    ann.id.isNotBlank() && ann.id !in readIds && shouldShowAnnouncement(ann, currentVersion)

/**
 * 待展示公告列表：丢弃无 id 的条目、按 id 去重、过滤已读。
 *
 * 无 id 直接丢弃而不是照弹：markRead("") 会把所有无 id 公告压成同一条已读记录，
 * 后果是「后发的那条永远弹不出来」——静默漏一条比多弹一条更糟，故宁可从源头要求 id。
 */
fun pendingAnnouncements(
    all: List<Announcement>,
    currentVersion: String?,
    readIds: Set<String>,
): List<Announcement> {
    val seen = mutableSetOf<String>()
    return all.filter { ann ->
        val id = ann.id.takeIf { it.isNotBlank() } ?: return@filter false
        seen.add(id) && shouldShowAnnouncementEntry(ann, currentVersion, readIds)
    }
}

/** 已读 id 按「旧→新」排列，只保留最近 [keep] 个；keep<=0 时全清 */
fun pruneReadIds(orderedReadIds: List<String>, keep: Int): List<String> {
    val distinct = orderedReadIds.filter { it.isNotBlank() }.distinct()
    if (keep <= 0) return emptyList()
    return if (distinct.size <= keep) distinct else distinct.takeLast(keep)
}

class AnnouncementStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** 已读公告 id 集合（无已读记录时为空集，不返回 null） */
    fun readIds(): Set<String> = readIdsOrdered().toSet()

    /** 记一条已读：已存在则不动（保持原顺序），否则追加到末尾并按上限裁剪 */
    fun markRead(id: String) {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) return
        val current = readIdsOrdered()
        if (trimmed in current) return
        writeIds(pruneReadIds(current + trimmed, MAX_READ_IDS))
    }

    /** 该公告是否应展示给当前版本（版本区间 + 未读） */
    fun shouldShow(ann: Announcement, currentVersion: String?): Boolean =
        shouldShowAnnouncementEntry(ann, currentVersion, readIds())

    /** 清理过长的已读记录，保留最近 [keep] 个 id */
    fun prune(keep: Int = DEFAULT_KEEP) {
        val current = readIdsOrdered()
        val pruned = pruneReadIds(current, keep)
        if (pruned.size != current.size) writeIds(pruned)
    }

    /** 已读 id（旧→新）；记录损坏时按「无已读」处理，让公告重新可弹而不是卡死 */
    private fun readIdsOrdered(): List<String> {
        val raw = prefs.getString(KEY_READ_ANNOUNCEMENTS, null) ?: return emptyList()
        return runCatching { JSON.decodeFromString(LIST_SERIALIZER, raw) }.getOrDefault(emptyList())
    }

    private fun writeIds(ids: List<String>) {
        prefs.edit().putString(KEY_READ_ANNOUNCEMENTS, JSON.encodeToString(LIST_SERIALIZER, ids)).apply()
    }

    companion object {
        const val PREFS_FILE: String = "gigi_about"
        const val KEY_READ_ANNOUNCEMENTS: String = "read_announcements"

        /** 上限：够存几百条公告的 id 仍不到 10KB，且 prune 保证不会再涨 */
        const val MAX_READ_IDS: Int = 100
        const val DEFAULT_KEEP: Int = 100

        private val JSON = Json { ignoreUnknownKeys = true }
        private val LIST_SERIALIZER = ListSerializer(String.serializer())
    }
}
