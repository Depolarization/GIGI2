package com.gigi.tcg.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 逐字移植 web/src/utils/__tests__/code.test.ts（10 用例：3 + it.each×5 + 2） */
class UidCodeTest {

    @Test
    fun `字符表低位在前不反转 uid 36 含余数 0 的边界（余 0 → 字符表第 0 位 5）`() {
        assertEquals("CA052N", id2code(36))
    }

    @Test
    fun `uid 1 → CA02N`() {
        assertEquals("CA02N", id2code(1))
    }

    @Test
    fun `uid 185671151（原代码注释中的样例）`() {
        assertEquals("CA0K0C38XN", id2code(185671151))
        assertEquals(
            "eyJDb2RlIjoiQ0EwSzBDMzhYTiIsIlNlYXNvbiI6N30=",
            generateCode(185671151, 7),
        )
    }

    // it.each 展开（Python 参照实现复算）
    @Test
    fun `id2code 7 = CA0VN`() {
        assertEquals("CA0VN", id2code(7))
    }

    @Test
    fun `id2code 123456789 = CA06EXR28N`() {
        assertEquals("CA06EXR28N", id2code(123456789))
    }

    @Test
    fun `id2code 299989984 = CA0M0PZJMN`() {
        assertEquals("CA0M0PZJMN", id2code(299989984))
    }

    @Test
    fun `id2code 253990148 = CA0O2OVVMN`() {
        assertEquals("CA0O2OVVMN", id2code(253990148))
    }

    @Test
    fun `id2code 261958214 = CA086BJHMN`() {
        assertEquals("CA086BJHMN", id2code(261958214))
    }

    @Test
    fun `JSON 组装格式逐字一致 Season 默认 7`() {
        // base64("{"Code":"CA052N","Season":7}")
        assertEquals("eyJDb2RlIjoiQ0EwNTJOIiwiU2Vhc29uIjo3fQ==", generateCode(36))
        assertEquals(generateCode(36), generateCode(36, 7))
    }

    @Test
    fun `Season 以常量形式参与编码 Season 8 时 JSON 值不同`() {
        assertNotEquals(generateCode(36, 8), generateCode(36, 7))
    }
}
