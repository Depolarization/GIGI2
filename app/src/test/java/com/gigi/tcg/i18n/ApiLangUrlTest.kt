package com.gigi.tcg.i18n

import com.gigi.tcg.R
import com.gigi.tcg.data.ServerApi
import com.gigi.tcg.data.ServerId
import com.gigi.tcg.data.api.API_ERROR_KIND_NETWORK
import com.gigi.tcg.data.api.API_ERROR_KIND_RETCODE
import com.gigi.tcg.data.api.API_ERROR_KIND_THROTTLED
import com.gigi.tcg.data.api.ApiError
import com.gigi.tcg.data.api.cardDetailUrl
import com.gigi.tcg.data.api.gameRecordsUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接口 lang 参数与错误文案的资源通道。
 * 🔴 纯 JVM 单测里 BuildConfig 的 String 域名字段是空串桩 ⇒ 断言用 contains/后缀，不用全等，
 * 同时验证 ServerApi 的 const 兜底在空串下依然成立。
 */
class ApiLangUrlTest {

    @After
    fun tearDown() {
        LocaleStrings.attach(null)
        LocaleStrings.installResolverForTest(null)
    }

    @Test
    fun `loginInfoUrl 的 lang 随应用语言变化且恒有兜底域名`() {
        for (lang in AppLanguage.entries) {
            val url = ServerApi.loginInfoUrl(lang)
            assertTrue(url, url.endsWith("lang=${lang.apiLangParam()}"))
            assertTrue(url, url.startsWith("https://api-takumi.mihoyo.com/common/badge/v1/login/info?"))
        }
        assertTrue(ServerApi.loginInfoUrl().endsWith("lang=zh-cn"))
    }

    @Test
    fun `赛事接口 lang 与服务器参数同时生效`() {
        val records = gameRecordsUrl("123", ServerId.Official, AppLanguage.TraditionalChinese)
        assertTrue(records, records.contains("lang=zh-tw"))
        assertTrue(records, records.contains("badge_region=cn_gf01"))
        assertTrue(records, records.contains("game_biz=hk4e_cn"))
        assertTrue("域名兜底", records.startsWith("https://hk4e-api.mihoyo.com"))
    }

    @Test
    fun `图鉴详情走 BuildConfig 模板并替换 lang 与 entry_page_id`() {
        val detail = cardDetailUrl(42, AppLanguage.TraditionalChinese)
        assertTrue(detail, detail.contains("entry_page_id=42"))
        assertTrue(detail, detail.endsWith("lang=zh-tw"))
        assertTrue("域名来自 BuildConfig", detail.startsWith("https://act-api-takumi-static.mihoyo.com"))
        assertTrue(
            "模板里的默认 zh-cn 必须被覆盖",
            !Regex("lang=zh-cn").containsMatchIn(detail),
        )
    }

    @Test
    fun `域名运行时读 BuildConfig，空值时回落 const 兜底`() {
        assertEquals(com.gigi.tcg.BuildConfig.EVENT_ORIGIN, ServerApi.eventOrigin)
        assertEquals(com.gigi.tcg.BuildConfig.RECORD_ORIGIN, ServerApi.recordOrigin)
        assertEquals(com.gigi.tcg.BuildConfig.BADGE_LOGIN_URL, ServerApi.badgeLoginUrl)
        assertEquals(com.gigi.tcg.BuildConfig.LOGIN_URL, ServerApi.loginPageUrl)
        assertEquals(com.gigi.tcg.BuildConfig.CONTENT_LIST_URL, com.gigi.tcg.data.api.CARD_INFO_URL)
        // 兜底链：configured() 在空串时回落
        assertEquals("https://hk4e-api.mihoyo.com", ServerApi.EVENT_ORIGIN)
        assertEquals("https://api-takumi-record.mihoyo.com", ServerApi.RECORD_ORIGIN)
    }

    @Test
    fun `错误分类映射到 F1 资源 id`() {
        assertEquals(R.string.error_throttled, apiErrorMessageRes(ApiError(API_ERROR_KIND_THROTTLED, "x")))
        assertEquals(R.string.error_throttled, apiErrorMessageRes(ApiError(API_ERROR_KIND_RETCODE, "x", -500004)))
        assertEquals(R.string.error_check_network, apiErrorMessageRes(Exception("x")))
        assertEquals(R.string.error_check_network, apiErrorMessageRes(ApiError(API_ERROR_KIND_NETWORK, "x")))
        assertEquals(R.string.error_api_generic, apiErrorMessageRes(ApiError(API_ERROR_KIND_RETCODE, "", -100)))
        assertNull(apiErrorMessageRes(ApiError(API_ERROR_KIND_RETCODE, "服务端消息", -100)))
    }

    @Test
    fun `桥未就绪时回落简中，就绪后走三语，服务端消息始终透传`() {
        val throttled = ApiError(API_ERROR_KIND_RETCODE, "x", -500004)
        assertEquals("请求过于频繁，请稍后重试", apiErrorText(throttled))

        LocaleStrings.installResolverForTest { id ->
            mapOf(R.string.error_throttled to "繁體文案")[id]
        }
        assertEquals("繁體文案", apiErrorText(throttled))
        assertEquals("服务端消息", apiErrorText(ApiError(API_ERROR_KIND_RETCODE, "服务端消息", -100)))
    }
}
