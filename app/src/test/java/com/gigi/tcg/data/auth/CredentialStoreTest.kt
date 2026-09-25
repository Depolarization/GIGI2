package com.gigi.tcg.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            StoredAccount("123456", "旅行者", "cn_gf01", 10L),
            StoredAccount("654321", null, "cn_qd01", 20L),
        )

        val encoded = CredentialStore.encodeAccountIndex(accounts)

        assertEquals(accounts, CredentialStore.parseAccountIndex(encoded))
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
}
