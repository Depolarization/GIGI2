// ①a/①c 覆盖：首刷时序（profile 落定后错峰 staggerMs 再 records）与
// 首刷 RETRYABLE 静默重试一次（手动 refresh/凭据错误不重试）。
// HomeViewModel 本体依赖 Android Application，这里测其提取出的纯函数 helper。

package com.gigi.tcg.ui.screens.home

import com.gigi.tcg.data.api.API_ERROR_KIND_RETCODE
import com.gigi.tcg.data.api.ApiError
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HomeFirstLoadTest {
    @Test
    fun `first load issues records only after profile completed plus stagger`() = runTest {
        val events = mutableListOf<String>()
        val start = currentTime
        runStaggeredFirstLoad(
            staggerMs = 400,
            loadProfile = {
                events += "profile@${currentTime - start}"
            },
            loadRecords = {
                events += "records@${currentTime - start}"
            },
        )
        // 顺序：profile 先完成，records 在其后 400ms 错峰点发出
        assertEquals(listOf("profile@0", "records@400"), events)
        assertEquals(400, currentTime.toInt())
    }

    @Test
    fun `zero stagger still serializes profile before records`() = runTest {
        val events = mutableListOf<String>()
        runStaggeredFirstLoad(
            staggerMs = 0,
            loadProfile = { events += "profile" },
            loadRecords = { events += "records" },
        )
        assertEquals(listOf("profile", "records"), events)
        assertEquals(0, currentTime.toInt())
    }

    @Test
    fun `first load silently retries once on retryable retcode then succeeds`() = runTest {
        var calls = 0
        val result = fetchWithSilentRetry(isFirstLoad = true) {
            calls += 1
            if (calls == 1) throw ApiError(API_ERROR_KIND_RETCODE, "操作频繁", -500004)
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, calls)
    }

    @Test
    fun `manual refresh does not silently retry and error surfaces`() = runTest {
        var calls = 0
        try {
            fetchWithSilentRetry(isFirstLoad = false) {
                calls += 1
                throw ApiError(API_ERROR_KIND_RETCODE, "操作频繁", -500004)
            }
            fail("expected ApiError")
        } catch (e: ApiError) {
            assertEquals(-500004, e.retcode)
        }
        assertEquals(1, calls)
    }

    @Test
    fun `auth failure never silently retries even on first load`() = runTest {
        var calls = 0
        try {
            fetchWithSilentRetry(isFirstLoad = true) {
                calls += 1
                throw ApiError(API_ERROR_KIND_RETCODE, "please login", -100)
            }
            fail("expected ApiError")
        } catch (e: ApiError) {
            assertEquals(-100, e.retcode)
        }
        assertEquals(1, calls)
    }

    @Test
    fun `first load settles error after second attempt also retryable`() = runTest {
        var calls = 0
        try {
            fetchWithSilentRetry(isFirstLoad = true) {
                calls += 1
                throw ApiError(API_ERROR_KIND_RETCODE, "操作频繁", -500004)
            }
            fail("expected ApiError")
        } catch (e: ApiError) {
            assertEquals(-500004, e.retcode)
        }
        assertEquals(2, calls)
    }

    @Test
    fun `non-api errors pass through untouched`() = runTest {
        var calls = 0
        try {
            fetchWithSilentRetry(isFirstLoad = true) {
                calls += 1
                throw IllegalStateException("boom")
            }
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertEquals(1, calls)
        assertTrue(true)
    }
}
