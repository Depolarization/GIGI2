package com.gigi.tcg.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialStoreTest {

    @Test
    fun `safe uid accepts only key-safe characters`() {
        assertTrue(CredentialStore.isSafeUid("123456"))
        assertTrue(CredentialStore.isSafeUid("player_01-test"))
        assertFalse(CredentialStore.isSafeUid(""))
        assertFalse(CredentialStore.isSafeUid("player/01"))
        assertFalse(CredentialStore.isSafeUid("player 01"))
        assertFalse(CredentialStore.isSafeUid("玩家"))
    }

    @Test
    fun `account storage keys are derived from uid`() {
        assertEquals("ciphertext_123456", CredentialStore.cipherPrefsKey("123456"))
        assertEquals("gigi_credentials_key_123456", CredentialStore.keyAlias("123456"))
    }

    @Test
    fun `account index round trips all fields`() {
        val accounts = listOf(
            StoredAccount("123456", "旅行者", "cn_gf01", 10L, avatar = "https://example.com/a.png"),
            StoredAccount("654321", null, "cn_qd01", 20L),
        )

        val encoded = CredentialStore.encodeAccountIndex(accounts)

        assertEquals(accounts, CredentialStore.parseAccountIndex(encoded))
    }

    /** V36/2 新增 avatar 字段：null 时不写键（explicitNulls=false），存量索引格式不变 */
    @Test
    fun `absent avatar is encoded as missing key`() {
        val encoded = CredentialStore.encodeAccountIndex(listOf(StoredAccount("123456", avatar = null)))
        assertFalse(encoded.contains("avatar"))
        assertNull(CredentialStore.parseAccountIndex(encoded).single().avatar)
    }

    @Test
    fun `malformed account index becomes empty`() {
        assertTrue(CredentialStore.parseAccountIndex(null).isEmpty())
        assertTrue(CredentialStore.parseAccountIndex("not-json").isEmpty())
        assertTrue(CredentialStore.parseAccountIndex("{}").isEmpty())
    }

    @Test
    fun `renewal requires a token in the new response`() {
        assertFalse(AuthManager.hasFreshEhk4e(emptyList()))
        assertFalse(AuthManager.hasFreshEhk4e(listOf("cookie_token=other")))
        assertTrue(AuthManager.hasFreshEhk4e(listOf("e_hk4e_token=new")))
    }

    @Test
    fun `account index drops unsafe and duplicate uids`() {
        val raw = """
            [
              {"uid":"safe-1"},
              {"uid":"bad/uid"},
              {"uid":"safe-1"},
              {"uid":"safe_2"}
            ]
        """.trimIndent()

        assertEquals(listOf("safe-1", "safe_2"), CredentialStore.parseAccountIndex(raw).map { it.uid })
    }

    // ===== V39-D2：切换激活账户**原位更新时间戳**，索引顺序不动 =====
    // 用户报「切账户时账户列表跳顺序」：旧 setActiveUid 把选中项提到最前，勾选符号本来就由
    // `uid == activeUid` 决定 ⇒ 顺序不动才只有勾在动。「最近使用」只体现在时间戳列上。

    @Test
    fun `activate in place keeps index order and only bumps the picked timestamp`() {
        val a = StoredAccount("111", "A", lastActiveEpochMs = 1L, avatar = "https://cdn/a.png")
        val b = StoredAccount("222", "B", lastActiveEpochMs = 2L)
        val c = StoredAccount("333", "C", lastActiveEpochMs = 3L)
        val accounts = listOf(a, b, c)

        val afterB = CredentialStore.activateInPlace(accounts, "222", 100L)

        assertNotNull(afterB)
        assertEquals(listOf("111", "222", "333"), afterB!!.map { it.uid })
        assertEquals(listOf(1L, 100L, 3L), afterB.map { it.lastActiveEpochMs })
        assertEquals("未选中的条目整条原样（昵称/服务器/头像都不动）", a, afterB.first())
        assertEquals(c, afterB.last())
        // 🔴 旧行为不得复活：把选中项提到最前（[B, A, C]）正是本改动要消灭的现象
        assertNotEquals(listOf(b, a, c), afterB)
        assertEquals("入参索引不被就地修改", listOf(1L, 2L, 3L), accounts.map { it.lastActiveEpochMs })

        // 连续切换（B 再 C）：顺序仍是 A, B, C，只有被选那条的时间戳前进
        val afterC = CredentialStore.activateInPlace(afterB, "333", 200L)
        assertEquals(listOf("111", "222", "333"), afterC!!.map { it.uid })
        assertEquals(listOf(1L, 100L, 200L), afterC.map { it.lastActiveEpochMs })
        assertNotEquals(listOf(c, a, b), afterC)
    }

    /** 重复点当前账户：仍是同一份顺序（幂等），只刷新时间戳 */
    @Test
    fun `re-activating the same uid is idempotent apart from the timestamp`() {
        val accounts = listOf(StoredAccount("111", lastActiveEpochMs = 1L), StoredAccount("222", lastActiveEpochMs = 2L))

        val again = CredentialStore.activateInPlace(accounts, "111", 5L)

        assertEquals(listOf("111", "222"), again!!.map { it.uid })
        assertEquals(listOf(5L, 2L), again.map { it.lastActiveEpochMs })
    }

    @Test
    fun `activate in place writes nothing when uid misses or is unsafe`() {
        val accounts = listOf(StoredAccount("111"), StoredAccount("222"))

        // null = 调用方（setActiveUid）直接 return ⇒ 不落盘、不动激活键
        assertNull(CredentialStore.activateInPlace(accounts, "999", 1L))
        assertNull(CredentialStore.activateInPlace(emptyList(), "111", 1L))
        assertNull(CredentialStore.activateInPlace(accounts, "", 1L))
        assertNull(CredentialStore.activateInPlace(accounts, "bad uid", 1L))
        assertNull(CredentialStore.activateInPlace(accounts, "玩家", 1L))
    }
}
