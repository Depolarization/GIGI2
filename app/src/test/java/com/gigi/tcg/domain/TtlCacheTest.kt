package com.gigi.tcg.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 逐字移植 web/src/utils/__tests__/ttlCache.test.ts（3 用例）。
 * TS 的模块级单例 + vi fake timers → Kotlin 侧每用例新建实例 + 注入 fake clock。
 * TS 中 null/undefined（miss）在 Kotlin 侧统一为 null。
 */
class TtlCacheTest {

    private var now = 0L
    private lateinit var cache: TtlCache

    @Before
    fun setUp() {
        // 对照 TS beforeEach { clearAllCache(); useFakeTimers() }
        now = 0L
        cache = TtlCache(now = { now })
    }

    @Test
    fun `returns a value before expiry and misses after TTL`() {
        cache.cacheSet("gigi:public:wiki-list:v1", mapOf("count" to 3), 1000)
        assertEquals(mapOf("count" to 3), cache.cacheGet("gigi:public:wiki-list:v1"))
        now += 1001
        assertNull(cache.cacheGet("gigi:public:wiki-list:v1"))
    }

    @Test
    fun `deletes one key explicitly`() {
        cache.cacheSet("gigi:public:wiki-list:v1", "wiki", 1000)
        cache.cacheDelete("gigi:public:wiki-list:v1")
        assertNull(cache.cacheGet("gigi:public:wiki-list:v1"))
    }

    @Test
    fun `clears private keys but preserves public data`() {
        cache.cacheSet("gigi:private:123:card-stats:v1", "private", 1000)
        cache.cacheSet("gigi:public:wiki-list:v1", "public", 1000)
        cache.clearPrivateCache()
        assertNull(cache.cacheGet("gigi:private:123:card-stats:v1"))
        assertEquals("public", cache.cacheGet("gigi:public:wiki-list:v1"))
    }
}
