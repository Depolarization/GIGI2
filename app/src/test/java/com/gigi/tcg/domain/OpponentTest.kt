package com.gigi.tcg.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** 逐字移植 web/src/utils/__tests__/opponent.test.ts（6 用例） */
class OpponentTest {

    private val cur = "261958214"

    @Test
    fun `己方 UID 在第二段 → 对手为第一段`() {
        assertEquals("146178207", extractOpponentUid("146178207_261958214", cur))
    }

    @Test
    fun `己方 UID 在第一段 → 对手为第二段`() {
        assertEquals("340438735", extractOpponentUid("261958214_340438735", cur))
    }

    @Test
    fun `真实 5 段式 trans_no 取前两段判定 与胜负无关`() {
        assertEquals(
            "146178207",
            extractOpponentUid("146178207_261958214_1786515594_4969_cn_gf01", cur),
        )
        assertEquals(
            "340438735",
            extractOpponentUid("261958214_340438735_1786515429_5075_cn_gf01", cur),
        )
    }

    @Test
    fun `胜负结果不影响解析（解析只依赖 trans_no 与 cur_uid）`() {
        assertEquals("146178207", extractOpponentUid("146178207_261958214", cur))
        assertEquals("146178207", extractOpponentUid("146178207_261958214", cur))
    }

    @Test
    fun `缺失下划线 空串 null undefined → unknown`() {
        assertEquals(UNKNOWN_OPPONENT_UID, extractOpponentUid("123456789", cur))
        assertEquals(UNKNOWN_OPPONENT_UID, extractOpponentUid("", cur))
        // TS 的 null 与 undefined 在 Kotlin 侧统一为 null
        assertEquals(UNKNOWN_OPPONENT_UID, extractOpponentUid(null, cur))
    }

    @Test
    fun `按字符串比较（避免数值化导致的边界差异）`() {
        assertEquals("0261958214", extractOpponentUid("0261958214_261958214", "261958214"))
    }
}
