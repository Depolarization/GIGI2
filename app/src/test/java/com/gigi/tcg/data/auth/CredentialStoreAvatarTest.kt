// V36/2 任务 D：StoredAccount 新增 `avatar`（登录时落盘的头像 URL）的**存量数据兼容**。
// 🔴 索引是磁盘上的 JSON 串，老用户的索引里没有 avatar 键。字段必须带默认值，
// 否则 kotlinx.serialization 对缺失键抛 MissingFieldException ⇒ parseAccountIndex 按"索引损坏"
// 返回空列表 ⇒ 老账号在升级后被当成"没有账户"，直接掉登录态。
// V37-G 任务 A：回填扩到**全部账户** ⇒ 补第二个账户时第一个的成果、索引序与激活键都不许被搅动（下面 4 条锁死）。
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

    // ===== V36/2b：头像回填判据（CredentialStore.replaceAvatar 纯函数） =====

    @Test
    fun `replaceAvatar rewrites only the hit account and keeps index order`() {
        val accounts = listOf(
            StoredAccount("111", "A", avatar = "https://old/a.png", lastActiveEpochMs = 10L),
            StoredAccount("222", "B", avatar = null, lastActiveEpochMs = 20L),
        )

        val next = CredentialStore.replaceAvatar(accounts, "222", "https://new/b.png")

        assertEquals(
            listOf(
                StoredAccount("111", "A", avatar = "https://old/a.png", lastActiveEpochMs = 10L),
                StoredAccount("222", "B", avatar = "https://new/b.png", lastActiveEpochMs = 20L),
            ),
            next,
        )
    }

    @Test
    fun `replaceAvatar refuses blank avatar`() {
        // 空白不是「有头像」：拿它覆盖已有值等于把头像擦掉（回填来源必须非空白才落盘）
        val accounts = listOf(StoredAccount("111", avatar = "https://keep/a.png"))

        assertNull(CredentialStore.replaceAvatar(accounts, "111", "  "))
        assertNull(CredentialStore.replaceAvatar(accounts, "111", null))
    }

    @Test
    fun `replaceAvatar is idempotent and uid-safe`() {
        val accounts = listOf(StoredAccount("111", avatar = "https://same/a.png"))

        // 同值 ⇒ null（不写盘、不刷列表）：补齐后每次进「我的」页都重写索引是纯惊动
        assertNull(CredentialStore.replaceAvatar(accounts, "111", "https://same/a.png"))
        // uid 未命中（已登出/切换竞态）⇒ null
        assertNull(CredentialStore.replaceAvatar(accounts, "999", "https://new/x.png"))
        // 空表 ⇒ null
        assertNull(CredentialStore.replaceAvatar(emptyList(), "111", "https://new/x.png"))
    }

    // ===== V37-G 任务 A：全账户逐个回填依赖的多账户判据 =====

    /** 真机现状复刻：两个账户都没有 avatar 键 ⇒ 逐个补第二个时，第一个的成果不许被抹掉 */
    @Test
    fun `backfilling the second account leaves the first one's avatar intact`() {
        val bothMissing = listOf(
            StoredAccount("261958214", "Oscuro", lastActiveEpochMs = 10L),
            StoredAccount("157777921", "墨邪", lastActiveEpochMs = 20L),
        )

        val afterFirst = CredentialStore.replaceAvatar(bothMissing, "261958214", "https://cdn/a.png")
        val afterSecond = CredentialStore.replaceAvatar(afterFirst ?: failOnNull(), "157777921", "https://cdn/b.png")

        assertEquals(
            listOf(
                StoredAccount("261958214", "Oscuro", lastActiveEpochMs = 10L, avatar = "https://cdn/a.png"),
                StoredAccount("157777921", "墨邪", lastActiveEpochMs = 20L, avatar = "https://cdn/b.png"),
            ),
            afterSecond,
        )
    }

    /** 链上每一步只动自己那一条：uid、顺序、昵称、服务器与 lastActiveEpochMs 全部原样（索引头 = 最近使用） */
    @Test
    fun `replaceAvatar keeps index order and every other field`() {
        val accounts = listOf(
            StoredAccount("111", "A", serverId = "cn_gf01", lastActiveEpochMs = 30L),
            StoredAccount("222", "B", serverId = "cn_qd01", lastActiveEpochMs = 40L, avatar = "https://keep/b.png"),
            StoredAccount("333", "C", lastActiveEpochMs = 50L),
        )

        val next = CredentialStore.replaceAvatar(accounts, "333", "https://cdn/c.png") ?: failOnNull()

        assertEquals(listOf("111", "222", "333"), next.map { it.uid })
        assertEquals(listOf(30L, 40L, 50L), next.map { it.lastActiveEpochMs })
        assertEquals(listOf("A", "B", "C"), next.map { it.nickname })
        assertEquals("cn_qd01", next[1].serverId)
        // 未命中 uid 的那几条没被 copy 过（值相等还不够，其它账户的头像不许变）
        assertEquals("https://keep/b.png", next[1].avatar)
        assertNull(next[0].avatar)
    }

    /** 回填只改索引：激活键与密文槽位是另一路 prefs 键，replaceAvatar 纯函数碰不到（updateAccountAvatar 也只 writeIndex） */
    @Test
    fun `replaceAvatar output keeps the active uid still present in the index`() {
        val accounts = listOf(
            StoredAccount("111", avatar = null),
            StoredAccount("222", avatar = "https://old/b.png"),
        )

        val next = CredentialStore.replaceAvatar(accounts, "111", "https://cdn/a.png") ?: failOnNull()

        // 索引序不变 ⇒ 头（最近使用 = 激活账户）仍是 111，换账号语义不被回填搅动
        assertEquals("111", next.first().uid)
        // 回写的仍是同一份索引 JSON 形态：编码再解码，头像在位且整表等价
        assertEquals(next, CredentialStore.parseAccountIndex(CredentialStore.encodeAccountIndex(next)))
    }

    /** 已补齐的账户被再次「回填同值」⇒ 不写盘（稳态零写盘，与零请求配套） */
    @Test
    fun `repeated backfill passes are no-ops after accounts are complete`() {
        val complete = listOf(
            StoredAccount("111", avatar = "https://cdn/a.png"),
            StoredAccount("222", avatar = "https://cdn/b.png"),
        )

        assertNull(CredentialStore.replaceAvatar(complete, "111", "https://cdn/a.png"))
        assertNull(CredentialStore.replaceAvatar(complete, "222", "https://cdn/b.png"))
    }

    private fun failOnNull(): List<StoredAccount> =
        throw AssertionError("replaceAvatar 判据误判：命中且头像非空白时必须返回新索引")
}
