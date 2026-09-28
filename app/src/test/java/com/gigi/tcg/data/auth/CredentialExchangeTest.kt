// CredentialExchangeTest：凭据交换链路中不依赖网络的纯函数（设计 §3.2 语义，对齐 auth-core.mjs）。
// 网络路径（createQRLogin / queryQRLoginStatus / badge login 实际请求）不做 JVM 测试。

package com.gigi.tcg.data.auth

import com.gigi.tcg.data.model.LoginInfoData
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

    // ===== no-role 判定（find(g => g.region === region && g.game_uid)） =====

    /** 构造不匹配 region 的假 list JSON（含 game_uid 为空的干扰项） */
    private val noRoleListJson =
        """{"retcode":0,"message":"OK","data":{"list":[
            {"game_biz":"hk4e_cn","region":"cn_qd01","game_uid":"700000000"},
            {"game_biz":"hk4e_cn","region":"cn_gf01","game_uid":""},
            {"game_biz":"hk4e_cn","region":"cn_gf01"}
        ]}}"""

    @Test
    fun `bound role list parses with nullable fields`() {
        val list = AuthManager.boundRoleListFromJson(json, noRoleListJson)
        assertEquals(3, list.size)
        assertEquals("cn_qd01", list[0].region)
        assertNull(list[2].gameUid)
    }

    @Test
    fun `no-role when region unmatched or game_uid blank`() {
        val list = AuthManager.boundRoleListFromJson(json, noRoleListJson)
        assertNull(AuthManager.findGameRoleForRegion(list, "cn_gf01"))
        // 渠道服虽然有角色，但换个不存在的 region 依旧 no-role
        assertNull(AuthManager.findGameRoleForRegion(list, "us_east"))
        assertNotNull(AuthManager.findGameRoleForRegion(list, "cn_qd01"))
    }

    @Test
    fun `role found returns region and game_uid`() {
        val list = AuthManager.boundRoleListFromJson(
            json,
            """{"data":{"list":[{"game_biz":"hk4e_cn","region":"cn_gf01","game_uid":"123456789","level":60}]}}"""
        )
        val role = AuthManager.findGameRoleForRegion(list, "cn_gf01")
        assertNotNull(role)
        assertEquals("123456789", role!!.gameUid)
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

    // ===== boundRolesOf：no-role 提示"角色实际绑在哪"的诊断数据 =====

    @Test
    fun `bound roles keep server order and drop noise entries`() {
        // noRoleListJson：只有第一条是带 game_uid 的真角色，另两条是噪声项
        val list = AuthManager.boundRoleListFromJson(json, noRoleListJson)
        assertEquals(listOf("cn_qd01"), AuthManager.boundRolesOf(list).map { it.region })
    }

    @Test
    fun `bound roles are empty when account has no real role`() {
        val list = AuthManager.boundRoleListFromJson(
            json,
            """{"data":{"list":[
                {"game_biz":"hk4e_cn","region":"cn_gf01","game_uid":""},
                {"game_biz":"hk4e_cn","region":null}
            ]}}""",
        )
        assertEquals(emptyList<AuthFinalizeResult.BoundRole>(), AuthManager.boundRolesOf(list))
    }

    @Test
    fun `bound roles dedupe same server with multiple roles`() {
        val list = AuthManager.boundRoleListFromJson(
            json,
            """{"data":{"list":[
                {"game_biz":"hk4e_cn","region":"cn_gf01","game_uid":"1"},
                {"game_biz":"hk4e_cn","region":"cn_qd01","game_uid":"5"},
                {"game_biz":"hk4e_cn","region":"cn_gf01","game_uid":"2"}
            ]}}""",
        )
        assertEquals(listOf("cn_gf01", "cn_qd01"), AuthManager.boundRolesOf(list).map { it.region })
    }

    @Test
    fun `bound roles include the selected server when role is genuinely missing`() {
        // 选了渠道服、账号只绑了官服 ⇒ boundRoles=[cn_gf01]，供 UI 提示"绑在官服"
        val list = AuthManager.boundRoleListFromJson(
            json,
            """{"data":{"list":[{"game_biz":"hk4e_cn","region":"cn_gf01","game_uid":"1"}]}}""",
        )
        assertNull(AuthManager.findGameRoleForRegion(list, "cn_qd01"))
        assertEquals(listOf("cn_gf01"), AuthManager.boundRolesOf(list).map { it.region })
    }

    // ===== 渠道服回归钉：真机实测取证（2026-09-28 + 官方接口原始返回） =====
    // 同一米游社账号（官服 + 渠道服双原神角色）：
    // - getUserGameRolesByCookie 完整返回两个角色（渠道服 cn_qd01 / 世界树 / is_official=false）；
    // - getGameRecordCard 只返回官服卡片 → 旧实现据此把渠道服用户误判"未绑定"（本轮修复的根因）。
    // 绑定接口的响应还会混入其他业务线角色（实测绝区零 game_biz=nap_cn / prod_gf_cn / 新艾利都），
    // 不过滤 game_biz 会把它们当成"原神角色绑在别的区服"，污染 no-role 提示。

    /** 真机原始返回片段（脱敏保留结构与关键值） */
    private val dualServerListJson =
        """{"retcode":0,"message":"OK","data":{"list":[
            {"game_biz":"hk4e_cn","region":"cn_gf01","game_uid":"157777921","nickname":"墨邪",
             "level":60,"is_chosen":false,"region_name":"天空岛","is_official":true,"is_banned":false,"unmask":[]},
            {"game_biz":"hk4e_cn","region":"cn_qd01","game_uid":"506119249","nickname":"墨邪",
             "level":45,"is_chosen":false,"region_name":"世界树","is_official":false,"is_banned":false,"unmask":[]}
        ]}}"""

    /** 全游戏列举（不传 game_biz 的实测返回）：混入绝区零（nap_cn） */
    private val crossGameListJson =
        """{"retcode":0,"message":"OK","data":{"list":[
            {"game_biz":"hk4e_cn","region":"cn_gf01","game_uid":"157777921","nickname":"墨邪","level":60,"region_name":"天空岛","is_official":true},
            {"game_biz":"hk4e_cn","region":"cn_qd01","game_uid":"506119249","nickname":"墨邪","level":45,"region_name":"世界树","is_official":false},
            {"game_biz":"nap_cn","region":"prod_gf_cn","game_uid":"25329172","nickname":"燊","level":60,"region_name":"新艾利都","is_official":true}
        ]}}"""

    @Test
    fun `channel server role is discovered from binding api`() {
        // 🔴 渠道服回归钉（本轮修复的核心场景）：绑定接口必须能命中 cn_qd01 世界树角色
        val list = AuthManager.boundRoleListFromJson(json, dualServerListJson)
        val role = AuthManager.findGameRoleForRegion(list, "cn_qd01")
        assertNotNull(role)
        assertEquals("506119249", role!!.gameUid)
        assertEquals("世界树", role.regionName)
        assertEquals(false, role.isOfficial)
    }

    @Test
    fun `official server role is still matched`() {
        val list = AuthManager.boundRoleListFromJson(json, dualServerListJson)
        val role = AuthManager.findGameRoleForRegion(list, "cn_gf01")
        assertNotNull(role)
        assertEquals("157777921", role!!.gameUid)
    }

    @Test
    fun `bound roles list both servers in server order`() {
        val list = AuthManager.boundRoleListFromJson(json, dualServerListJson)
        assertEquals(listOf("cn_gf01", "cn_qd01"), AuthManager.boundRolesOf(list).map { it.region })
    }

    @Test
    fun `other-game bindings are excluded from genshin role matching`() {
        val list = AuthManager.boundRoleListFromJson(json, crossGameListJson)
        // 回归钉：绝区零的 prod_gf_cn 绝不能被当成原神角色命中
        assertNull(AuthManager.findGameRoleForRegion(list, "prod_gf_cn"))
        assertNotNull(AuthManager.findGameRoleForRegion(list, "cn_gf01"))
        assertNotNull(AuthManager.findGameRoleForRegion(list, "cn_qd01"))
    }

    @Test
    fun `bound roles never include other games`() {
        val list = AuthManager.boundRoleListFromJson(json, crossGameListJson)
        assertEquals(listOf("cn_gf01", "cn_qd01"), AuthManager.boundRolesOf(list).map { it.region })
    }

    @Test
    fun `bound roles carry official region name uid and nickname for the notice`() {
        val list = AuthManager.boundRoleListFromJson(json, dualServerListJson)
        val role = AuthManager.boundRolesOf(list).last()
        assertEquals("cn_qd01", role.region)
        assertEquals("世界树", role.regionName)
        assertEquals("506119249", role.uid)
        assertEquals("墨邪", role.nickname)
    }

    @Test
    fun `bindings without game_biz are still treated as genshin`() {
        // 设计红线 1：字段全可空。服务端若不再下发 game_biz，宽松判定保证不至于全员无法登录。
        val list = AuthManager.boundRoleListFromJson(
            json,
            """{"data":{"list":[{"region":"cn_gf01","game_uid":"1"}]}}""",
        )
        assertNotNull(AuthManager.findGameRoleForRegion(list, "cn_gf01"))
    }

    // ===== V33 自动角色选择（拆除服务器预选后的登录决策，纯函数钉死） =====
    // 新流程：扫码确认 → getUserGameRolesByCookie 拿真实角色列表 → selectRoleForAuto：
    // 唯一角色直接登录（渠道服用户不必先猜"渠道服=世界树"）、多角色列候选交用户选择、
    // 零角色提示绑定。过滤口径 = 原神绑定（game_biz）× 国服注册表（ServerId）× game_uid
    // 非空，按 region 去重保序。

    @Test
    fun `auto selection with dual server account asks the user to choose`() {
        val list = AuthManager.boundRoleListFromJson(json, dualServerListJson)
        val auto = AuthManager.selectRoleForAuto(list)
        assertTrue(auto is AutoRoleSelection.Multiple)
        val candidates = (auto as AutoRoleSelection.Multiple).candidates
        // 保序（服务端返回顺序）+ 选择器展示要素（服名 / 等级）随候选透出
        assertEquals(listOf("cn_gf01", "cn_qd01"), candidates.map { it.region })
        assertEquals(listOf("157777921", "506119249"), candidates.map { it.uid })
        assertEquals(listOf(60, 45), candidates.map { it.level })
        assertEquals("天空岛", candidates[0].regionName)
        assertEquals("世界树", candidates[1].regionName)
    }

    @Test
    fun `auto selection with a single channel role logs in directly`() {
        // 渠道服单角色账号：V33 前必须先在登录页选中"世界树"才登得进去，现在直接命中
        val list = AuthManager.boundRoleListFromJson(
            json,
            """{"data":{"list":[{"game_biz":"hk4e_cn","region":"cn_qd01","game_uid":"506119249",
                "nickname":"墨邪","level":45,"region_name":"世界树","is_official":false}]}}""",
        )
        val auto = AuthManager.selectRoleForAuto(list)
        assertTrue(auto is AutoRoleSelection.Single)
        assertEquals("cn_qd01", (auto as AutoRoleSelection.Single).role.region)
    }

    @Test
    fun `auto selection with no genshin role reports none`() {
        // 只有绝区零绑定（game_biz=nap_cn）→ None，绝不误判成"原神角色绑在 prod_gf_cn"
        val list = AuthManager.boundRoleListFromJson(
            json,
            """{"data":{"list":[{"game_biz":"nap_cn","region":"prod_gf_cn","game_uid":"25329172"}]}}""",
        )
        assertEquals(AutoRoleSelection.None, AuthManager.selectRoleForAuto(list))
    }

    @Test
    fun `auto selection excludes regions outside the cn registry`() {
        // 注册表外区服（如国际服 os_euro）：没有对应 ServerId 可落盘 → 不参与登录选择
        val list = AuthManager.boundRoleListFromJson(
            json,
            """{"data":{"list":[{"game_biz":"hk4e_cn","region":"os_euro","game_uid":"800000001"}]}}""",
        )
        assertEquals(AutoRoleSelection.None, AuthManager.selectRoleForAuto(list))
    }

    @Test
    fun `auto selection picks the sole cn role while non-registry regions are filtered`() {
        val list = AuthManager.boundRoleListFromJson(
            json,
            """{"data":{"list":[
                {"game_biz":"hk4e_cn","region":"os_euro","game_uid":"800000001"},
                {"game_biz":"hk4e_cn","region":"cn_qd01","game_uid":"506119249"}
            ]}}""",
        )
        val auto = AuthManager.selectRoleForAuto(list)
        assertTrue(auto is AutoRoleSelection.Single)
        assertEquals("cn_qd01", (auto as AutoRoleSelection.Single).role.region)
    }

    @Test
    fun `auto selection drops entries without game_uid`() {
        // game_uid 缺失的噪声项不能让 Single 误判成全 Account 唯一角色不存在/存在
        val list = AuthManager.boundRoleListFromJson(
            json,
            """{"data":{"list":[
                {"game_biz":"hk4e_cn","region":"cn_gf01"},
                {"game_biz":"hk4e_cn","region":"cn_qd01","game_uid":"5"}
            ]}}""",
        )
        val auto = AuthManager.selectRoleForAuto(list)
        assertTrue(auto is AutoRoleSelection.Single)
        assertEquals("cn_qd01", (auto as AutoRoleSelection.Single).role.region)
    }

    @Test
    fun `auto selection candidates exclude other games`() {
        val list = AuthManager.boundRoleListFromJson(json, crossGameListJson)
        val auto = AuthManager.selectRoleForAuto(list)
        assertTrue(auto is AutoRoleSelection.Multiple)
        // 绝区零（prod_gf_cn）不得出现在候选里
        assertEquals(
            listOf("cn_gf01", "cn_qd01"),
            (auto as AutoRoleSelection.Multiple).candidates.map { it.region },
        )
    }

    // ===== login/info 响应解析（AppGate 旧版凭据收养的服务器归属来源） =====
    // 真机原始返回（2026-09-28）：返回值完全由 cookie 所持 e_hk4e_token 决定（per-角色），
    // 与请求的 badge_region 参数无关——渠道服 token 恒返回 region=cn_qd01。
    // AppGate 收养旧版单槽凭据时以该 region 校准账户服务器（accountServerFor），
    // 不用 currentServer（冷启动恒为默认官服，会把渠道服账户错标成官服）。

    @Test
    fun `login info parses channel region from real response`() {
        val info = json.decodeFromString(
            LoginInfoData.serializer(),
            """{"game_uid":"506119249","nickname":"墨邪","region":"cn_qd01","region_name":"cn_qd01","level":45}""",
        )
        assertEquals("506119249", info.gameUid)
        assertEquals("cn_qd01", info.region)
        assertEquals("墨邪", info.nickname)
    }

    @Test
    fun `login info tolerates missing region`() {
        val info = json.decodeFromString(
            LoginInfoData.serializer(),
            """{"game_uid":"1","nickname":"n"}""",
        )
        assertNull(info.region)
    }
}
