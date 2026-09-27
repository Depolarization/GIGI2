// V26 回归锁：玩家详情弹窗的头像兜底优先级。
// 背景：列表接口（排行榜 rank_infos / 对局 game_records）必定返回 avatar_url，
// 而 other_home_page 在 is_shield（无权访问该玩家主页）时不给头像 ⇒ 不兜底就只剩
// 灰底 Person 占位。故约定「pageInfo 头像优先，缺失时回落入口列表头像」。
package com.gigi.tcg.ui.dialogs.playerdetail

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerDetailAvatarTest {

    private val pageAvatar = "https://img/page.png"
    private val entryAvatar = "https://img/rank.png"

    /** pageInfo 有头像时不许被入口头像顶掉（正常访问分支以接口数据为准） */
    @Test
    fun pageInfoAvatarWins() {
        assertEquals(pageAvatar, resolveAvatarUrl(pageAvatar, entryAvatar))
    }

    /** 无权访问分支：pageInfo 头像为 null ⇒ 用排行榜/对局列表那张 */
    @Test
    fun fallsBackToEntryAvatarWhenNull() {
        assertEquals(entryAvatar, resolveAvatarUrl(null, entryAvatar))
    }

    /** 接口可能回空串而非 null，同样要回落（isBlank 而非 isEmpty） */
    @Test
    fun fallsBackToEntryAvatarWhenBlank() {
        assertEquals(entryAvatar, resolveAvatarUrl("", entryAvatar))
        assertEquals(entryAvatar, resolveAvatarUrl("   ", entryAvatar))
    }

    /** 两侧都没有 ⇒ 返回 null，由 Avatar 画占位（不能把空串传下去） */
    @Test
    fun nullWhenBothMissing() {
        assertEquals(null, resolveAvatarUrl(null, null))
        assertEquals(null, resolveAvatarUrl("", "  "))
        assertEquals(null, resolveAvatarUrl(null, ""))
    }
}
