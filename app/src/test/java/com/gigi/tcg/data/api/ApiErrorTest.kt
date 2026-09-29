// 移植 web/src/api/__tests__/retcode.test.ts 中不依赖 fetchMyHomePage 的用例
// （"限流自动重试" describe 依赖 mihoyo 接口封装，归 T3d 集成）。

package com.gigi.tcg.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiErrorTest {

    private fun retcodeError(retcode: Int) =
        ApiError(API_ERROR_KIND_RETCODE, "服务端消息", retcode)

    @Test
    fun `仅凭据失效码 -100 -101 判定为需要重新登录`() {
        assertTrue(isAuthFailureError(retcodeError(-100)))
        assertTrue(isAuthFailureError(retcodeError(-101)))
        // 实测：带有效凭据并发请求主页接口时约 1/3 概率返回 -500004[操作频繁]
        assertFalse(isAuthFailureError(retcodeError(-500004)))
        assertFalse(isAuthFailureError(retcodeError(-1)))
        assertFalse(isAuthFailureError(retcodeError(-110)))
        assertFalse(isAuthFailureError(ApiError(API_ERROR_KIND_NETWORK, "网络请求失败")))
        assertFalse(isAuthFailureError(Exception("其它异常")))
    }

    @Test
    fun `限流与网络失败给出不同的可读文案`() {
        assertEquals("请求过于频繁，请稍后重试", describeApiError(retcodeError(-500004)))
        assertEquals("请检查网络重试", describeApiError(ApiError(API_ERROR_KIND_NETWORK, "网络请求失败")))
        assertEquals("服务端消息", describeApiError(retcodeError(-100)))
        assertEquals("请检查网络重试", describeApiError(Exception("x")))
    }

    @Test
    fun `重试码集合与 web client 一致且不含凭据失效码`() {
        assertEquals(setOf(-500004, -1, -110), RETRYABLE_RETCODES)
        assertEquals(setOf(-100, -101), AUTH_FAILED_RETCODES)
        assertFalse(RETRYABLE_RETCODES.contains(-100))
    }

    // 1034 = 米游社人机验证风控（实测 gcg/basicInfo 恒返回，改请求形态/重试均无效，
    // 见 .task/p1-gcg-samples/FINDINGS.md §7/§8）。三条断言各钉一头，别合并。
    @Test
    fun `1034 独立成套且不进重试与登出`() {
        assertEquals(setOf(1034), CAPTCHA_REQUIRED_RETCODES)
        assertFalse("1034 不代表凭据失效，不得据此登出", AUTH_FAILED_RETCODES.contains(1034))
        assertFalse("1034 重试不可能成功", RETRYABLE_RETCODES.contains(1034))
        assertFalse(isAuthFailureError(retcodeError(1034)))
    }

    @Test
    fun `1034 显示可操作指引而非裸错误码`() {
        val text = describeApiError(ApiError(API_ERROR_KIND_RETCODE, "接口返回 retcode=1034", 1034))
        assertTrue(text, text.isNotBlank())
        assertFalse(text, text.contains("retcode=1034"))
        assertTrue(text, text.contains("人机验证"))
    }

    // ---- V36/1：退避重试判据 + 解析失败归因 ----

    @Test
    fun `network 与节流归入可重试，鉴权与 CAPTCHA 不重试`() {
        assertTrue(isRetryableError(ApiError(API_ERROR_KIND_NETWORK, "网络请求失败")))
        assertTrue(isRetryableError(ApiError(API_ERROR_KIND_THROTTLED, "请求过于频繁，请稍后重试")))
        assertTrue(isRetryableError(retcodeError(-500004)))
        assertFalse(isRetryableError(retcodeError(-100)))
        assertFalse(isRetryableError(retcodeError(-101)))
        assertFalse(isRetryableError(retcodeError(1034)))
        assertFalse(isRetryableError(retcodeError(10001)))
    }

    @Test
    fun `错误可携带原始异常因果链（解析失败归因不丢真因）`() {
        val cause = IllegalStateException("boom")
        val error = ApiError(API_ERROR_KIND_NETWORK, "响应数据解析失败", cause = cause)
        assertSame(cause, error.cause)
        // 旧构造位参形态仍可用：retcode 仍在第 3 位
        assertEquals(-500004, retcodeError(-500004).retcode)
    }

    @Test
    fun `client 兜底的 1034 文案与 i18n 语义一致且无裸码`() {
        val text = MihoyoClient.CAPTCHA_REQUIRED_MESSAGE
        assertFalse(text, text.contains("retcode"))
        assertTrue(text, text.contains("人机验证"))
        assertEquals(describeApiError(retcodeError(1034)), text)
    }
}
