// V36/2 任务 D：StoredAccount 新增 `avatar`（登录时落盘的头像 URL）的**存量数据兼容**。
// 🔴 索引是磁盘上的 JSON 串，老用户的索引里没有 avatar 键。字段必须带默认值，
// 否则 kotlinx.serialization 对缺失键抛 MissingFieldException ⇒ parseAccountIndex 按"索引损坏"
// 返回空列表 ⇒ 老账号在升级后被当成"没有账户"，直接掉登录态。
package com.gigi.tcg.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialStoreAvatarTest {

    @Test
    fun `legacy index without avatar field still parses`() {
        // 升级前落盘的原始形态：只有 uid / nickname / serverId / lastActiveEpochMs
        val legacy = """
            [{"uid":"123456","nickname":"旅行者","serverId":"cn_gf01","lastActiveEpochMs":10}]
        """.trimIndent()

        val parsed = CredentialStore.parseAccountIndex(legacy)

        assertEquals(1, parsed.size)
        assertEquals("123456", parsed.single().uid)
        assertEquals("旅行者", parsed.single().nickname)
        assertEquals("cn_gf01", parsed.single().serverId)
        assertNull("缺字段必须回落 null，不能整表作废", parsed.single().avatar)
    }

    @Test
    fun `avatar survives encode and parse round trip`() {
        val accounts = listOf(
            StoredAccount("123456", "旅行者", "cn_gf01", 10L, avatar = "https://example.com/a.png"),
            StoredAccount("654321", null, "cn_qd01", 20L, avatar = ""),
        )

        assertEquals(accounts, CredentialStore.parseAccountIndex(CredentialStore.encodeAccountIndex(accounts)))
    }

    @Test
    fun `null avatar is not written into the json`() {
        // explicitNulls=false：null 键整条省略，编码结果与升级前逐字节一致（不撑爆 prefs 串）
        val encoded = CredentialStore.encodeAccountIndex(listOf(StoredAccount("123456")))

        assertTrue(encoded.contains("123456"))
        assertTrue(!encoded.contains("avatar"))
    }

    @Test
    fun `unknown fields are ignored as before`() {
        val raw = """[{"uid":"123456","avatar":"https://example.com/a.png","some_future_field":1}]"""

        assertEquals("https://example.com/a.png", CredentialStore.parseAccountIndex(raw).single().avatar)
    }
}
