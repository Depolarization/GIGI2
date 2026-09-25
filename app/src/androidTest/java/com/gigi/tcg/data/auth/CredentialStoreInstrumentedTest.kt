package com.gigi.tcg.data.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CredentialStoreInstrumentedTest {

    @Test
    fun saveThenReadThenClear_roundTripsViaKeystore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CredentialStore(context)
        val cookie = "e_hk4e_token=1; cookie_token=2"

        store.save(cookie)
        assertEquals(cookie, store.cookieHeader())

        store.clear()
        assertNull(store.cookieHeader())
    }
}
