// 报告 §8.5：解析结果缓存上移到 AppContainer 后必须仍是 LRU + lang 维度隔离。

package com.gigi.tcg.domain

import com.gigi.tcg.data.model.CardBasicInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CardDetailCacheTest {

    private fun detail(name: String) = CardDetail(
        info = CardBasicInfo(name = name),
        element = CardElement.Unknown,
        skills = emptyList(),
        effect = null,
    )

    @Test
    fun `同 key 命中同一实例`() {
        val cache = CardDetailCache(max = 2)
        val detail = detail("浊水幻灵")
        cache.put("zh-cn:502488", detail)

        assertSame(detail, cache.get("zh-cn:502488"))
    }

    @Test
    fun `不同语言的同一张卡互不串文案`() {
        val cache = CardDetailCache(max = 2)
        cache.put("zh-cn:502488", detail("浊水幻灵"))
        cache.put("en:502488", detail("Hydro Mimic"))

        assertEquals("浊水幻灵", cache.get("zh-cn:502488")?.info?.name)
        assertEquals("Hydro Mimic", cache.get("en:502488")?.info?.name)
    }

    @Test
    fun `超出容量淘汰最久未使用`() {
        val cache = CardDetailCache(max = 2)
        cache.put("a", detail("A"))
        cache.put("b", detail("B"))
        cache.get("a") // a 变成最近使用
        cache.put("c", detail("C"))

        assertEquals(2, cache.size())
        assertEquals("A", cache.get("a")?.info?.name)
        assertNull(cache.get("b"))
        assertEquals("C", cache.get("c")?.info?.name)
    }

    @Test
    fun `未命中返回 null`() {
        assertNull(CardDetailCache().get("nope"))
    }
}
