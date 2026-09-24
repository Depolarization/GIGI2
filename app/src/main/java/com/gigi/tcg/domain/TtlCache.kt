// 轻量 TTL 缓存（内存层）：逐字对照 Web 版 utils/ttlCache.ts。仅缓存公开/派生数据，
// 不保存密码、Cookie、验证码或 QR state。key 必须包含私有 UID；
// clearPrivateCache 在登出/失效时调用。（DataStore 磁盘层归 T3，不在本文件。）

package com.gigi.tcg.domain

private const val PRIVATE_PREFIX = "gigi:private:"

class TtlCache(private val now: () -> Long = { System.currentTimeMillis() }) {

    private class Entry(val value: Any?, val expiresAt: Long)

    private val store = mutableMapOf<String, Entry>()

    fun cacheGet(key: String): Any? {
        val entry = store[key] ?: return null
        if (entry.expiresAt <= now()) {
            store.remove(key)
            return null
        }
        return entry.value
    }

    fun cacheSet(key: String, value: Any?, ttlMs: Long) {
        store[key] = Entry(value, now() + ttlMs)
    }

    fun cacheDelete(key: String) {
        store.remove(key)
    }

    fun clearPrivateCache() {
        val keysToRemove = store.keys.filter { it.startsWith(PRIVATE_PREFIX) }
        keysToRemove.forEach { store.remove(it) }
    }

    fun clearAllCache() {
        store.clear()
    }
}
