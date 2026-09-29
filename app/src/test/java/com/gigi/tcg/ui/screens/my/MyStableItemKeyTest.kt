// V36/2 任务 A：Lazy key 必须复合唯一。
// 钉死的实测事实：真机 3 条 recent_matches 的 game_id **全是字符串 "10"**（非 null 但重复），
// 原写法 `item.gameId ?: "noid-$index"` 只兜 null、不兜重复 ⇒ IllegalArgumentException: Key "10"
// was already used ⇒ 点「收藏对局」直接闪退。
package com.gigi.tcg.ui.screens.my

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MyStableItemKeyTest {

    @Test
    fun `repeated ids still get distinct keys`() {
        // 实测形态：3 条记录的 game_id 都是 "10"
        val keys = listOf("10", "10", "10").mapIndexed { index, id -> stableItemKey(id, index) }
        assertEquals(3, keys.distinct().size)
    }

    @Test
    fun `null ids get distinct keys instead of colliding on the fallback`() {
        val keys = List(3) { index -> stableItemKey(null, index) }
        assertEquals(listOf("noid-0", "noid-1", "noid-2"), keys)
        assertEquals(3, keys.distinct().size)
    }

    @Test
    fun `key is stable for the same id and index`() {
        assertEquals(stableItemKey("10", 1), stableItemKey("10", 1))
        assertNotEquals(stableItemKey("10", 1), stableItemKey("10", 2))
    }

    @Test
    fun `key keeps non string ids and survives blank strings`() {
        // id 可能是 Int（deck/schedule）也可能是空串（脏数据）：都不能撞
        val keys = listOf<Any?>(7, "7", "", null, 7).mapIndexed { index, id -> stableItemKey(id, index) }
        assertTrue(keys.all { it.isNotEmpty() })
        assertEquals(keys.size, keys.distinct().size)
        assertEquals("7-0", keys.first())
    }
}
