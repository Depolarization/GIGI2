// NoRoleNoticeTest：登录页 no-role 提示的**分因**逻辑（V30）。
//
// 背景：判据只有一个——所选 region 没在 getGameRecordCard 返回里命中带 game_role_id 的
// **原神**（game_id=2）项。但"没命中"有三种成因，合并成同一句提示就是 bug 级的误导：
//   1. 账号真没绑任何原神角色 → 必须引导去米游社绑定（"切换服务器"是错误建议）
//   2. 角色绑在别的区服       → 必须说清"绑在哪 + 是哪个角色"，否则用户反复扫码
//   3. 非原神卡片混入（实测绝区零 game_id=8 / prod_gf_cn）
//                             → 必须被 boundRolesOf 挡在门外，不得出现在提示里
// 这里只验证纯函数的分支选择与文案组装，不触碰 AuthManager 的网络路径。

package com.gigi.tcg.ui.login

import com.gigi.tcg.data.ServerId
import com.gigi.tcg.data.auth.AuthFinalizeResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoRoleNoticeTest {

    private fun boundRole(
        region: String,
        regionName: String? = null,
        uid: String = "1",
        nickname: String? = null,
    ) = AuthFinalizeResult.BoundRole(region, regionName, uid, nickname)

    @Test
    fun `no bound roles guides the user to bind instead of switching servers`() {
        val notice = noRoleNotice(
            AuthFinalizeResult.NoRole(region = ServerId.Channel.id, boundRoles = emptyList()),
            ServerId.Channel,
        )
        // 简中桥未 attach 时回落字面量：必须引导绑定，且不得出现"绑定在…不在你选择的"自相矛盾文案
        assertTrue(notice.contains("未绑定"))
        assertTrue(notice.contains("任何原神角色"))
        assertFalse(notice.contains("不在你选择的"))
    }

    @Test
    fun `role bound to another server tells the user where it actually is`() {
        // 典型场景：渠道服用户扫码，但角色其实绑在官服
        val notice = noRoleNotice(
            AuthFinalizeResult.NoRole(
                region = ServerId.Channel.id,
                boundRoles = listOf(boundRole(ServerId.Official.id, "天空岛", "157777921", "墨邪")),
            ),
            ServerId.Channel,
        )
        assertTrue(notice.contains("绑定在"))
        assertTrue(notice.contains("不在你选择的"))
        // 两端服务器名都要出现：既说绑在哪，也说当前选的是哪台
        assertTrue(notice.contains("天空岛"))
        assertTrue(notice.contains(ServerId.Channel.name))
        // 角色身份也要透出，用户才能一眼确认是哪个号
        assertTrue(notice.contains("157777921"))
    }

    @Test
    fun `official region name wins over local mapping when present`() {
        // 服务端 region_name 是官方口径；即使本地映射能译出名字，也优先用服务端下发的
        val notice = noRoleNotice(
            AuthFinalizeResult.NoRole(
                region = ServerId.Channel.id,
                boundRoles = listOf(boundRole("os_euro", "欧服", "800000001")),
            ),
            ServerId.Channel,
        )
        assertTrue(notice.contains("欧服"))
        assertFalse(notice.contains("os_euro"))
    }

    @Test
    fun `unknown region without official name falls back to raw identifier`() {
        // 未注册标识符且无 region_name：原样透出（诊断信息宁可多显示也不丢）
        val notice = noRoleNotice(
            AuthFinalizeResult.NoRole(
                region = ServerId.Channel.id,
                boundRoles = listOf(boundRole("os_euro", null, "800000001")),
            ),
            ServerId.Channel,
        )
        assertTrue(notice.contains("os_euro"))
    }

    @Test
    fun `bound roles containing the selected one do not produce the elsewhere message`() {
        // 防御：即使 boundRoles 里含所选 region（正常链路不该发生，NoRole 意味未命中），
        // 过滤后为空 ⇒ 仍走"未绑定所选服"分支，不得出现自相矛盾的"绑在…不在你选择的"。
        val notice = noRoleNotice(
            AuthFinalizeResult.NoRole(
                region = ServerId.Channel.id,
                boundRoles = listOf(boundRole(ServerId.Channel.id, "世界树", "5")),
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
                boundRoles = listOf(
                    boundRole(ServerId.Official.id, "天空岛", "157777921"),
                    boundRole("os_euro", null, "800000001"),
                    boundRole(ServerId.Channel.id, "世界树", "5"),
                ),
            ),
            ServerId.Channel,
        )
        assertTrue(notice.contains("天空岛"))
        assertTrue(notice.contains("os_euro"))
        // 所选服被排除，不得出现在"绑在…"列表里
        assertFalse(notice.contains("世界树（UID"))
    }
}
