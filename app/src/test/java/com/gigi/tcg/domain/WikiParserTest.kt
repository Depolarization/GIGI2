package com.gigi.tcg.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** 逐字移植 web/src/utils/__tests__/wiki.test.ts（4 用例）。JSON 载体用字符串模板手工拼装，保证"JSON 套 JSON"结构逐字一致。 */
class WikiParserTest {

    // ---- parseFilterDefs（ch_ext 二次解析）----

    @Test
    fun `parseFilterDefs 解析 attribute_key filter 项的 value`() {
        val chExt = """
            [
              {"type":"text","attribute_key":"col_per_row","value":"2"},
              {"type":"text","attribute_key":"filter","value":"[{\"label\":\"元素\",\"children\":[{\"label\":\"火元素\"},{\"label\":\"水元素\"}]},{\"label\":\"武器\",\"children\":[{\"label\":\"弓\"}]}]"}
            ]
        """.trimIndent()
        val defs = parseFilterDefs(chExt)
        assertEquals(2, defs.size)
        assertEquals(
            WikiFilterDef("元素", listOf(WikiFilterChild("火元素"), WikiFilterChild("水元素"))),
            defs[0],
        )
    }

    @Test
    fun `parseFilterDefs 无 filter 项 空 chExt undefined 非法 JSON → 空数组（容错不抛异常）`() {
        assertEquals(emptyList<WikiFilterDef>(), parseFilterDefs("""[{"attribute_key":"other","value":"1"}]"""))
        assertEquals(emptyList<WikiFilterDef>(), parseFilterDefs(""))
        assertEquals(emptyList<WikiFilterDef>(), parseFilterDefs(null)) // TS undefined 与 null 同路径
        assertEquals(emptyList<WikiFilterDef>(), parseFilterDefs("not-json"))
    }

    // ---- parseCardFilters（ext 二次解析 filter.text）----

    @Test
    fun `parseCardFilters 解析 c_233 下的归属标签数组`() {
        val ext = """
            {
              "c_233": {"filter":{"text":"[\"元素/火元素\",\"武器/单手剑\"]"}},
              "c_234": {"filter":{"text":"[\"类别/装备牌\"]"}}
            }
        """.trimIndent()
        assertEquals(listOf("元素/火元素", "武器/单手剑"), parseCardFilters(ext, "c_233"))
        assertEquals(listOf("类别/装备牌"), parseCardFilters(ext, "c_234"))
    }

    @Test
    fun `parseCardFilters 键缺失 text缺失 非法JSON → 空数组`() {
        assertEquals(emptyList<String>(), parseCardFilters("""{"c_235":{}}""", "c_235"))
        assertEquals(emptyList<String>(), parseCardFilters("{}", "c_233"))
        assertEquals(emptyList<String>(), parseCardFilters("broken", "c_233"))
        assertEquals(emptyList<String>(), parseCardFilters(null, "c_233"))
    }
}
