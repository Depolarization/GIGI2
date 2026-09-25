// V6A-P0 单测：冷启动播种判定（A/P0-1）与 observeMain 瞬时失败重试判定（B/P0-2）。
// AppContainer/GateViewModel 在纯 JVM 构造不出（CredentialStore 需 Context，见 U3A2-WIRE.md §2），
// 故两处逻辑均抽为 internal 纯函数在此覆盖；"登出后 sessionUid 不回填"另以写入点穷举 + 真机验证。

package com.gigi.tcg.ui.login

import com.gigi.tcg.data.api.API_ERROR_KIND_NETWORK
import com.gigi.tcg.data.api.API_ERROR_KIND_RETCODE
import com.gigi.tcg.data.api.ApiError
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Test

class GateSeedAndRetryTest {

    // ---- A：启动播种判定 ----

    @Test
    fun `seed uses active account uid when cookie present`() {
        assertEquals("uid-1", seededSessionUid(hasCookie = true, activeAccountUid = "uid-1"))
    }

    @Test
    fun `no cookie never seeds`() {
        assertEquals(null, seededSessionUid(hasCookie = false, activeAccountUid = "uid-1"))
    }

    @Test
    fun `legacy single-slot without account index does not seed`() {
        assertEquals(null, seededSessionUid(hasCookie = true, activeAccountUid = null))
    }

    // ---- B：重试判定真值表 ----

    private val auth100 = ApiError(API_ERROR_KIND_RETCODE, "登录失效", retcode = -100)
    private val auth101 = ApiError(API_ERROR_KIND_RETCODE, "扫码登录失效", retcode = -101)
    private val throttled = ApiError(API_ERROR_KIND_RETCODE, "操作频繁，请稍后再试", retcode = -500004)
    private val networkKind = ApiError(API_ERROR_KIND_NETWORK, "响应不是有效的 JSON")

    @Test
    fun `throttled minus 500004 retries within budget`() {
        assertEquals(true, shouldRetryVerify(throttled, attempt = 1, maxAttempts = 3))
        assertEquals(true, shouldRetryVerify(throttled, attempt = 3, maxAttempts = 3))
    }

    @Test
    fun `io exception retries`() {
        assertEquals(true, shouldRetryVerify(IOException("connect failed"), attempt = 1, maxAttempts = 3))
    }

    @Test
    fun `other retcode and network-kind retry as transient`() {
        val other = ApiError(API_ERROR_KIND_RETCODE, "retcode=-500099", retcode = -500099)
        assertEquals(true, shouldRetryVerify(other, attempt = 1, maxAttempts = 3))
        assertEquals(true, shouldRetryVerify(networkKind, attempt = 2, maxAttempts = 3))
    }

    @Test
    fun `auth failure codes never retry here`() {
        assertEquals(false, shouldRetryVerify(auth100, attempt = 1, maxAttempts = 3))
        assertEquals(false, shouldRetryVerify(auth101, attempt = 1, maxAttempts = 3))
    }

    @Test
    fun `cancellation never retries`() {
        assertEquals(false, shouldRetryVerify(CancellationException("cancelled"), attempt = 1, maxAttempts = 3))
    }

    @Test
    fun `attempts beyond cap stop retrying`() {
        assertEquals(false, shouldRetryVerify(throttled, attempt = 4, maxAttempts = 3))
        assertEquals(false, shouldRetryVerify(IOException("still down"), attempt = 0, maxAttempts = 3))
    }
}
