// RoleChooserSelectionTest：角色选择对话框（V34 多选）的纯函数契约 ——
// 默认全选 / 提交顺序 = 候选列表顺序（勾选先后不参与决策）/ 子集过滤 / 空集；
// 以及部分成功提示 partialFailNotice 的文案契约（已保存数、失败数、首个原因）。
// 纯展示/编排逻辑，不触碰任何凭据判定（登录成败仍由 AuthManager 决定）。

package com.gigi.tcg.ui.login

import com.gigi.tcg.data.auth.AuthFinalizeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleChooserSelectionTest {

    private fun role(region: String, uid: String, nickname: String? = null) =
        AuthFinalizeResult.BoundRole(
            region = region,
            regionName = null,
            uid = uid,
            nickname = nickname,
            level = null,
        )

    private val candidates = listOf(
        role("cn_gf01", "157777921", "墨邪"),
        role("cn_qd01", "506119249", "墨邪"),
    )

    @Test
    fun `initial selection checks every candidate`() {
        assertEquals(setOf("157777921", "506119249"), initialRoleSelection(candidates))
        assertTrue(initialRoleSelection(emptyList()).isEmpty())
    }

    @Test
    fun `selected roles preserve candidate order regardless of check order`() {
        // 即便用户在逻辑上"先勾渠道服再勾官服"，输出仍按候选列表顺序（官服在前）——
        // 这是 VM 侧"激活账户 = 第一个成功角色"语义成立的前提
        val ordered = selectedRolesInOrder(candidates, setOf("506119249", "157777921"))
        assertEquals(listOf("157777921", "506119249"), ordered.map { it.uid })
    }

    @Test
    fun `selected roles filter out unchecked`() {
        assertEquals(listOf("506119249"), selectedRolesInOrder(candidates, setOf("506119249")).map { it.uid })
        assertEquals(listOf("157777921"), selectedRolesInOrder(candidates, setOf("157777921")).map { it.uid })
    }

    @Test
    fun `empty selection yields empty list`() {
        assertTrue(selectedRolesInOrder(candidates, emptySet()).isEmpty())
    }

    @Test
    fun `partial notice reports saved and failed counts with first cause`() {
        // 简中桥未 attach 时回落字面量模板并格式化（LocaleStrings.getOrDefault 带参版）
        val notice = partialFailNotice(savedCount = 1, failedCount = 1, firstReason = "网络请求失败")
        assertTrue(notice.contains("已保存 1 个账户"))
        assertTrue(notice.contains("1 个失败"))
        assertTrue(notice.contains("网络请求失败"))
    }
}
