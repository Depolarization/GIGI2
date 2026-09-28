// V6A-P0 单测：冷启动播种判定（A/P0-1）与 observeMain 瞬时失败重试判定（B/P0-2）。
// AppContainer/GateViewModel 在纯 JVM 构造不出（CredentialStore 需 Context，见 U3A2-WIRE.md §2），
// 故两处逻辑均抽为 internal 纯函数在此覆盖；"登出后 sessionUid 不回填"另以写入点穷举 + 真机验证。

package com.gigi.tcg.ui.login

import com.gigi.tcg.data.ServerId
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

    // ---- C（V7B）：添加账号叠加层的 Main 状态迁移不离开 Main 分支 ----
    // AppGate 的 when(state) 只有分支类别变化才会移出/重建 GigiNavHost；
    // 纯状态层断言：进入/退出叠加层均为 Main→Main，verified 与宿主分支保持稳定。

    @Test
    fun `main defaults to no add-account overlay`() {
        assertEquals(false, GateUiState.Main(verified = true).addAccount)
    }

    @Test
    fun `entering and cancelling add-account stay in Main branch keeping verified`() {
        val before = GateUiState.Main(verified = false)
        val overlay: GateUiState = GateUiState.Main(verified = before.verified, addAccount = true) // addAccount()
        assertEquals(true, overlay is GateUiState.Main) // 分支类别未变 ⇒ GigiNavHost 留在组合
        val after = before.copy(addAccount = false) // cancelAddAccount() 等价路径
        assertEquals(false, after.addAccount)
        assertEquals(before.verified, after.verified) // verifiedForCurrentSession/verified 不被翻动
        assertEquals(before, after) // 与进入前逐字段一致 ⇒ 导航宿主经历同一分支类别
    }

    // ---- D（V31）：旧版单槽凭据收养的服务器归属口径 ----
    // 实测（2026-09-28 真机 + 原始返回）：e_hk4e_token 是 per-角色 的，login/info 返回值
    // 完全由 cookie 所持 token 决定、与 badge_region 参数无关——渠道服 token 恒返回
    // region=cn_qd01。收养必须用响应 region，而非 currentServer（冷启动恒为默认官服），
    // 否则渠道服账户被错标成官服（此后所有请求 badge_region/server 全错）。

    @Test
    fun `channel region from login info wins over official fallback`() {
        assertEquals(ServerId.Channel, accountServerFor("cn_qd01", ServerId.Official))
    }

    @Test
    fun `official region from login info is respected over channel fallback`() {
        assertEquals(ServerId.Official, accountServerFor("cn_gf01", ServerId.Channel))
    }

    @Test
    fun `missing or unknown region falls back to current server`() {
        assertEquals(ServerId.Official, accountServerFor(null, ServerId.Official))
        assertEquals(ServerId.Channel, accountServerFor("", ServerId.Channel))
        // 非国服注册表值（如星铁/绝区零 prod_* 体系）不引入新失败面，回落当前服务器
        assertEquals(ServerId.Channel, accountServerFor("prod_gf_cn", ServerId.Channel))
    }
}
