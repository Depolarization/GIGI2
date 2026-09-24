package com.gigi.tcg.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐字移植 web/src/config/__tests__/servers.test.ts 前 6 个注册表用例（L14/23/28/33/42/51）。
 * 后 3 个 URL 构造用例（L64/72/81）归 T3b。
 */
class ServerRegistryTest {

    @Test
    fun `只包含官服与渠道服两个服务器，标识符唯一、名称正确`() {
        assertEquals(
            listOf(ServerId.Official, ServerId.Channel),
            ServerId.ALL,
        )
        assertEquals(
            listOf(
                Triple("cn_gf01", "官服（天空岛）", "官服"),
                Triple("cn_qd01", "渠道服（世界树）", "渠道服"),
            ),
            ServerId.ALL.map { Triple(it.id, it.name, it.shortName) },
        )
        assertEquals("官服（天空岛）", ServerId.from("cn_gf01")?.name)
        assertEquals("官服", ServerId.from("cn_gf01")?.shortName)
        assertEquals("渠道服（世界树）", ServerId.from("cn_qd01")?.name)
        assertEquals("渠道服", ServerId.from("cn_qd01")?.shortName)
    }

    @Test
    fun `默认项明确且存在于注册表中`() {
        assertEquals("cn_gf01", ServerId.DEFAULT.id)
        assertEquals("官服（天空岛）", ServerId.from(ServerId.DEFAULT.id)?.name)
    }

    @Test
    fun `二选一切换 otherServer 返回另一个服务器`() {
        assertEquals("cn_qd01", otherServer(ServerId.Official).id)
        assertEquals("cn_gf01", otherServer(ServerId.Channel).id)
    }

    @Test
    fun `标识符校验：国际服等非注册表值一律不合法`() {
        assertTrue(ServerId.isValid("cn_gf01"))
        assertTrue(ServerId.isValid("cn_qd01"))
        // TS 用 unknown 覆盖 1 / {} 等非字符串；Kotlin 类型系统已排除非 String? 输入
        for (raw in listOf<String?>(
            "os_asia", "os_euro", "os_usa", "os_cht", null, "", "cn_gf99", "cn_gf01 ",
        )) {
            assertFalse("isValid($raw)", ServerId.isValid(raw))
            assertNull("from($raw)", ServerId.from(raw))
        }
    }

    @Test
    fun `兜底 未选择 空值 非法标识符都回退到默认服务器并标记 fallback`() {
        for (raw in listOf<String?>(null, "", "   ", "cn_xx", "os_asia", "<script>")) {
            val r = resolveServerWithFallback(raw)
            assertTrue("fallback($raw)", r.fallback)
            assertEquals(ServerId.DEFAULT.id, r.server.id)
        }
        val hit = resolveServerWithFallback("cn_qd01")
        assertSame(ServerId.Channel, hit.server)
        assertFalse(hit.fallback)
    }

    @Test
    fun `国服端点常量与原工程 api-lua 一致`() {
        assertEquals("hk4e_cn", ServerApi.GAME_BIZ)
        assertEquals("https://hk4e-api.mihoyo.com", ServerApi.EVENT_ORIGIN)
        assertEquals("/event/geniusinvokationtcg", ServerApi.EVENT_PATH_PREFIX)
        assertEquals("https://api-takumi-record.mihoyo.com", ServerApi.RECORD_ORIGIN)
        assertEquals("/game_record/app/genshin/api", ServerApi.RECORD_PATH_PREFIX)
        assertEquals(
            "https://api-takumi.mihoyo.com/common/badge/v1/login/info?game_biz=hk4e_cn&lang=zh-cn",
            ServerApi.LOGIN_INFO_URL,
        )
    }
}
