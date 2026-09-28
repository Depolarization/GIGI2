// NoRoleNoticeTest：登录页 no-role 提示文案（V33 重写）。
//
// V33 拆除服务器预选控件后，"所选服务器(region)没在绑定列表里命中"这一成因不复存在：
// 角色发现改为扫码确认后自动决策 —— 唯一角色直接登录、多角色列候选（ChooseRole）、
// 零角色才落 NoRole。因此提示只剩一种形态："该账号没有可登录的国服原神角色"，
// 文案统一引导去米游社绑定/检查，**不再提"切换服务器"**（该入口已撤销，再给这个
// 建议等于把用户指向一个不存在的按钮；V30 的"不在你选择的 X"变体同样废弃）。
//
// 本测试钉死两点：① 引导话术仍在；② 旧误导话术（切换服务器 / 不在你选择的）永不回归。
// 纯展示逻辑，不触碰任何凭据判定（登录成败仍由 AuthManager 决定）。

package com.gigi.tcg.ui.login

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoRoleNoticeTest {

    @Test
    fun `no-role notice guides to HoYoLAB binding`() {
        val notice = noRoleNotice()
        // 简中桥未 attach 时回落字面量：必须引导绑定
        assertTrue(notice.contains("未绑定"))
        assertTrue(notice.contains("任何原神角色"))
        assertTrue(notice.contains("米游社"))
    }

    @Test
    fun `no-role notice never suggests switching servers`() {
        // V33 回归钉：服务器选择器已从登录页撤销，旧文案的"请切换服务器"
        //（以及 V30 的"不在你选择的"变体）会把用户指向不存在的按钮
        val notice = noRoleNotice()
        assertFalse(notice.contains("切换服务器"))
        assertFalse(notice.contains("不在你选择的"))
    }
}
