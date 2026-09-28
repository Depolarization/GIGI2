// CredentialExchangeTest：凭据交换链路中不依赖网络的纯函数（设计 §3.2 语义，对齐 auth-core.mjs）。
// 网络路径（createQRLogin / queryQRLoginStatus / badge login 实际请求）不做 JVM 测试。

package com.gigi.tcg.data.auth

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialExchangeTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ===== mergeFragments（auth-core.mjs 同语义：同名以后者覆盖） =====

    @Test
    fun `mergeFragments overwrites same name with later value`() {
        val merged = AuthManager.mergeFragments(
            listOf("account_id=100", "ltoken_v2=aaa", "account_id=200")
        )
        assertEquals(listOf("account_id=200", "ltoken_v2=aaa"), merged)
    }

    @Test
    fun `mergeFragments joins multiple fragments preserving first-seen order`() {
        val merged = AuthManager.mergeFragments(
            listOf("ltoken_v2=lt", "cookie_token_v2=ct", "e_hk4e_token=ehk")
        )
        assertEquals(listOf("ltoken_v2=lt", "cookie_token_v2=ct", "e_hk4e_token=ehk"), merged)
    }

    @Test
    fun `mergeFragments skips malformed parts without equals sign`() {
        // 🔴 mjs 原语义照抄：join('; ') 后按 ';' 拆分，无 '=' 的片段与空键被跳过
        val merged = AuthManager.mergeFragments(listOf("good=1", "novalue", "  spaced=2  "))
        assertEquals(listOf("good=1", "spaced=2"), merged)
    }

    @Test
    fun `mergeFragments keeps value content containing equals signs`() {
        val merged = AuthManager.mergeFragments(listOf("token=abc=def=ghi"))
        assertEquals(listOf("token=abc=def=ghi"), merged)
    }

    // ===== Set-Cookie 解析（collectSetCookies 语义：截 attributes、滤无 '=' 项） =====

    @Test
    fun `set-cookie header line reduces to name-value pair`() {
        // 手工模拟 OkHttp headers("Set-Cookie") 的逐行输出
        val lines = listOf(
            "e_hk4e_token=123456#abc; Max-Age=2592000; Path=/; Secure; HttpOnly",
            "overridden=x; Domain=mihoyo.com",
            ";empty",
        )
        val pairs = lines.asSequence()
            .map { it.substringBefore(';').trim() }
            .filter { it.contains('=') && !it.startsWith("=") }
            .toList()
        assertEquals(listOf("e_hk4e_token=123456#abc", "overridden=x"), pairs)
        val merged = AuthManager.mergeFragments(pairs + listOf("overridden=y"))
        assertTrue(merged.contains("e_hk4e_token=123456#abc"))
        assertTrue(merged.contains("overridden=y"))
    }

    // ===== findPair（getPair 语义） =====

    @Test
    fun `findPair returns first match value or null`() {
        val fragments = listOf("account_id=100", "cookie_token_v2=ctv2")
        assertEquals("ctv2", AuthManager.findPair(fragments, "cookie_token_v2"))
        assertNull(AuthManager.findPair(fragments, "account_id_v2"))
    }

    // ===== no-role 判定（find(g => g.region === region && g.game_role_id)） =====

    /** 构造不匹配 region 的假 list JSON（含 game_role_id 为空的干扰项） */
    private val noRoleListJson =
        """{"retcode":0,"message":"OK","data":{"list":[
            {"region":"cn_qd01","game_role_id":"700000000"},
            {"region":"cn_gf01","game_role_id":""},
            {"region":"cn_gf01"}
        ]}}"""

    @Test
    fun `record card list parses with nullable fields`() {
        val list = AuthManager.recordCardListFromJson(json, noRoleListJson)
        assertEquals(3, list.size)
        assertEquals("cn_qd01", list[0].region)
        assertNull(list[2].gameRoleId)
    }

    @Test
    fun `no-role when region unmatched or game_role_id blank`() {
        val list = AuthManager.recordCardListFromJson(json, noRoleListJson)
        assertNull(AuthManager.findGameRoleForRegion(list, "cn_gf01"))
        // 渠道服虽然有角色，但换个不存在的 region 依旧 no-role
        assertNull(AuthManager.findGameRoleForRegion(list, "us_east"))
        assertNotNull(AuthManager.findGameRoleForRegion(list, "cn_qd01"))
    }

    @Test
    fun `role found returns region and game_role_id`() {
        val list = AuthManager.recordCardListFromJson(
            json,
            """{"data":{"list":[{"region":"cn_gf01","game_role_id":"123456789","level":60}]}}"""
        )
        val role = AuthManager.findGameRoleForRegion(list, "cn_gf01")
        assertNotNull(role)
        assertEquals("123456789", role!!.gameRoleId)
        assertEquals("cn_gf01", role.region)
    }

    // ===== e_hk4e_token 成功路径（字符串样例纯函数验证，不依赖网络） =====

    @Test
    fun `exchange success merges e_hk4e_token and keeps base credentials`() {
        val confirmed = listOf(
            "account_id=100", "account_id_v2=100", "mid=500", "uid=100",
            "ltoken_v2=LT", "ltuid_v2=100", "cookie_token_v2=CTV2",
        )
        val exchangeSetCookie = listOf(
            "e_hk4e_token=123456#Dm5z8Yx...; Max-Age=2592000; Path=/; Secure",
            "cookie_token=CTV2; Max-Age=2592000; Path=/",
        )
        val fragments = AuthManager.mergeFragments(confirmed)
        val extra = exchangeSetCookie.map { it.substringBefore(';').trim() }
        val merged = AuthManager.mergeFragments(fragments + extra)

        assertTrue(merged.any { it.startsWith(AuthManager.E_HK4E_TOKEN_PREFIX) })
        // 基础凭据保留（cookie_token_v2 不被同名以外的 cookie_token 覆盖——不同键名）
        assertEquals("CTV2", AuthManager.findPair(merged, "cookie_token_v2"))
        assertEquals("CTV2", AuthManager.findPair(merged, "cookie_token"))
        assertEquals("LT", AuthManager.findPair(merged, "ltoken_v2"))
        // 落盘形态：'; ' 连接的 name=value 串
        val stored = merged.joinToString("; ")
        assertTrue(stored.startsWith("account_id=100; account_id_v2=100"))
        assertTrue(stored.contains("e_hk4e_token=123456#Dm5z8Yx..."))
    }

    @Test
    fun `exchange without e_hk4e_token is not success`() {
        val merged = AuthManager.mergeFragments(
            listOf("account_id=100", "cookie_token_v2=CTV2") + listOf("mistoken=abc")
        )
        assertTrue(merged.none { it.startsWith(AuthManager.E_HK4E_TOKEN_PREFIX) })
    }

    @Test
    fun `badge login body carries region uid and game_biz`() {
        val body = AuthManager.badgeLoginBody(json, "cn_gf01", "123456789")
        val buffer = okio.Buffer()
        body.writeTo(buffer)
        assertEquals(
            """{"region":"cn_gf01","uid":"123456789","game_biz":"hk4e_cn"}""",
            buffer.readUtf8()
        )
    }

    // ===== boundRegionsOf：no-role 提示"角色实际绑在哪"的诊断数据 =====

    @Test
    fun `bound regions keep server order and drop noise entries`() {
        // noRoleListJson：只有第一条是带 game_role_id 的真角色，另两条是噪声项
        val list = AuthManager.recordCardListFromJson(json, noRoleListJson)
        assertEquals(listOf("cn_qd01"), AuthManager.boundRegionsOf(list))
    }

    @Test
    fun `bound regions are empty when account has no real role`() {
        val list = AuthManager.recordCardListFromJson(
            json,
            """{"data":{"list":[{"region":"cn_gf01","game_role_id":""},{"region":null}]}}""",
        )
        assertEquals(emptyList<String>(), AuthManager.boundRegionsOf(list))
    }

    @Test
    fun `bound regions dedupe same server with multiple roles`() {
        val list = AuthManager.recordCardListFromJson(
            json,
            """{"data":{"list":[
                {"region":"cn_gf01","game_role_id":"1"},
                {"region":"cn_qd01","game_role_id":"5"},
                {"region":"cn_gf01","game_role_id":"2"}
            ]}}""",
        )
        assertEquals(listOf("cn_gf01", "cn_qd01"), AuthManager.boundRegionsOf(list))
    }

    @Test
    fun `bound regions include the selected server when role is genuinely missing`() {
        // 选了渠道服、账号只绑了官服 ⇒ boundRegions=[cn_gf01]，供 UI 提示"绑在官服"
        val list = AuthManager.recordCardListFromJson(
            json,
            """{"data":{"list":[{"region":"cn_gf01","game_role_id":"1"}]}}""",
        )
        assertNull(AuthManager.findGameRoleForRegion(list, "cn_qd01"))
        assertEquals(listOf("cn_gf01"), AuthManager.boundRegionsOf(list))
    }
}
