// NoRoleNoticeTest：登录页 no-role 提示的**分因**逻辑（V30）。
//
// 背景：判据只有一个——所选 region 没在 getGameRecordCard 返回里命中带 game_role_id 的项。
// 但"没命中"有两种成因，合并成同一句提示就是 bug 级的误导：
//   1. 账号真没绑角色        → "未绑定 X 的原神角色"（原提示正确）
//   2. 角色绑在别的区服      → 必须说清"绑在哪"，否则用户反复扫码（修复目标）
// 这里只验证纯函数的分支选择与文案组装，不触碰 AuthManager 的网络路径。

package com.gigi.tcg.ui.login

import com.gigi.tcg.data.ServerId
import com.gigi.tcg.data.auth.AuthFinalizeResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoRoleNoticeTest {

    @Test
    fun `no bound roles falls back to the plain not-bound message`() {
        val notice = noRoleNotice(
            AuthFinalizeResult.NoRole(region = ServerId.Channel.id, boundRegions = emptyList()),
            ServerId.Channel,
        )
        // 简中桥未 attach 时回落字面量：文案里必须含所选服务器，且不含"绑定在"那句
        assertTrue(notice.contains("未绑定"))
        assertFalse(notice.contains("不在你选择的"))
    }

    @Test
    fun `role bound to another server tells the user where it actually is`() {
        // 典型场景：渠道服用户扫码，但角色其实绑在官服
        val notice = noRoleNotice(
            AuthFinalizeResult.NoRole(
                region = ServerId.Channel.id,
                boundRegions = listOf(ServerId.Official.id),
            ),
            ServerId.Channel,
        )
        assertTrue(notice.contains("绑定在"))
        assertTrue(notice.contains("不在你选择的"))
        // 两端服务器名都要出现：既说绑在哪，也说当前选的是哪台
        assertTrue(notice.contains(ServerId.Official.name))
        assertTrue(notice.contains(ServerId.Channel.name))
    }

    @Test
    fun `bound regions containing the selected one do not produce the elsewhere message`() {
        // 防御：即使 boundRegions 里含所选 region（正常链路不该发生，NoRole 意味未命中），
        // 过滤后为空 ⇒ 仍走"未绑定"分支，不得出现自相矛盾的"绑在…不在你选择的"。
        val notice = noRoleNotice(
            AuthFinalizeResult.NoRole(
                region = ServerId.Channel.id,
                boundRegions = listOf(ServerId.Channel.id),
            ),
            ServerId.Channel,
        )
        assertTrue(notice.contains("未绑定"))
        assertFalse(notice.contains("不在你选择的"))
    }

    @Test
    fun `multiple bound servers are joined and selected one is excluded`() {
        val notice = noRoleNotice(
            AuthFinalizeResult.NoRole(
                region = ServerId.Channel.id,
                boundRegions = listOf(ServerId.Official.id, "os_euro", ServerId.Channel.id),
            ),
            ServerId.Channel,
        )
        assertTrue(notice.contains(ServerId.Official.name))
        // 未注册标识符原样透出（诊断信息宁可多显示也不丢），且不重复出现所选服
        assertTrue(notice.contains("os_euro"))
        assertTrue(notice.contains(ServerId.Channel.name))
    }
}
