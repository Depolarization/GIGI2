package com.gigi.tcg.i18n

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * LocaleStrings 桥（非 Compose 层取文案）：未注册出哨兵便于排查漏接、
 * getOrDefault 给数据层字面量回落、带参版本走 String.format。
 * 纯 JVM 无 mockito/Robolectric ⇒ 用注入解析器覆盖逻辑，资源真实解析由 UI 侧验证。
 */
class LocaleStringsTest {

    @After
    fun tearDown() {
        LocaleStrings.attach(null)
        LocaleStrings.installResolverForTest(null)
    }

    @Test
    fun `未注册时返回哨兵，便于排查漏接`() {
        assertEquals("[missing:123]", LocaleStrings.get(123))
        assertEquals("[missing:123]", LocaleStrings.get(123, "a"))
    }

    @Test
    fun `未注册时 getOrDefault 回落到调用方字面量`() {
        assertEquals("官服（天空岛）", LocaleStrings.getOrDefault(123, "官服（天空岛）"))
    }

    @Test
    fun `解析器命中时按 id 出文案`() {
        LocaleStrings.installResolverForTest { id -> mapOf(1 to "OK", 2 to "hi %1\$s")[id] }
        org.junit.Assert.assertTrue(LocaleStrings.resolved)
        assertEquals("OK", LocaleStrings.get(1))
        assertEquals("hi there", LocaleStrings.get(2, "there"))
        assertEquals("fallback", LocaleStrings.getOrDefault(99, "fallback"))
    }

    @Test
    fun `解析器抛错折算为哨兵而非崩溃`() {
        LocaleStrings.installResolverForTest { error("boom") }
        assertEquals("[missing:7]", LocaleStrings.get(7))
    }

    @Test
    fun `attach(null) 与 installResolverForTest(null) 均可解除`() {
        LocaleStrings.installResolverForTest { "x" }
        org.junit.Assert.assertTrue(LocaleStrings.resolved)
        LocaleStrings.attach(null)
        org.junit.Assert.assertNull(LocaleStrings.attachedContext)
        org.junit.Assert.assertFalse(LocaleStrings.resolved)
        assertEquals("[missing:7]", LocaleStrings.get(7))
    }
}
