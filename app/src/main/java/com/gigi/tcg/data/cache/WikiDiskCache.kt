// 图鉴列表磁盘缓存（设计文档 §2.4 唯一落盘项）：DataStore Preferences，文件 wiki_cache。
// 公开静态数据才允许落盘——私有数据（主页/对局/排行/卡牌统计/凭据）一律只走内存层。
// TTL 1 小时，写入时间与载荷同键存放；过期视为未命中（返回 null）。

package com.gigi.tcg.data.cache

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gigi.tcg.data.repo.WikiDiskStore
import java.io.IOException
import kotlinx.coroutines.flow.first

private val Context.wikiCacheDataStore: DataStore<Preferences> by preferencesDataStore(
    name = WikiDiskCache.DATA_STORE_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class WikiDiskCache(
    private val context: Context,
    private val now: () -> Long = { System.currentTimeMillis() },
) : WikiDiskStore {
    override suspend fun get(serverId: String): String? {
        val prefs = try {
            context.wikiCacheDataStore.data.first()
        } catch (e: IOException) {
            // 读盘抖动：按未命中处理，不把缓存失败升级成页面错误
            return null
        }
        val timestamp = prefs[timeKey(serverId)]?.toLongOrNull() ?: return null
        if (now() - timestamp >= TTL_MS) return null
        return prefs[contentKey(serverId)]
    }

    override suspend fun put(serverId: String, rawJson: String) {
        val timestamp = now()
        context.wikiCacheDataStore.edit { prefs ->
            prefs[contentKey(serverId)] = rawJson
            prefs[timeKey(serverId)] = timestamp.toString()
        }
    }

    companion object {
        const val DATA_STORE_NAME: String = "wiki_cache"

        /** 图鉴列表 TTL：1 小时（§2.4 缓存表 / pageCache.ts WIKI_CACHE_TTL_MS） */
        const val TTL_MS: Long = 60 * 60 * 1000L

        /** 键前缀含服务器标识符（§2.4：缓存键一律含服务器） */
        fun contentKey(serverId: String) = stringPreferencesKey("gigi:wiki:$serverId:list")

        private fun timeKey(serverId: String) = stringPreferencesKey("gigi:wiki:$serverId:time")
    }
}
