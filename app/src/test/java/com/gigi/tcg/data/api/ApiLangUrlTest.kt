// V9-J：域名兜底收口（originOf / defaultAvatarUrl）+ 接口 lang 参数随界面语言的透传断言。
// 纯 JVM 单测：不触网；com.gigi.tcg.i18n.ApiLangUrlTest（资源通道侧）已存在，本类补 URL 构造侧用例。

package com.gigi.tcg.data.api

import com.gigi.tcg.BuildConfig
import com.gigi.tcg.data.ServerApi
import com.gigi.tcg.data.ServerId
import com.gigi.tcg.i18n.AppLanguage
import com.gigi.tcg.i18n.LocaleStrings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiLangUrlTest {

    @After
    fun tearDown() {
        LocaleStrings.attach(null)
    }

    // ---- originOf：纯字符串运算 ----

    @Test
    fun `originOf 剥掉 common 路径只留 scheme 与主机`() {
        assertEquals("https://a.b.com", ServerApi.originOf("https://a.b.com/common/x"))
        assertEquals(
            "https://act-api-takumi-static.mihoyo.com",
            ServerApi.originOf(BuildConfig.CONTENT_LIST_URL),
        )
    }

    @Test
    fun `originOf 裸主机补 https 并保留端口`() {
        assertEquals("https://a.b.com", ServerApi.originOf("a.b.com/path?q=1"))
        assertEquals("https://a.b.com:8443", ServerApi.originOf("a.b.com:8443"))
        assertEquals("http://a.b.com:80", ServerApi.originOf("http://a.b.com:80/x"))
    }

    @Test
    fun `originOf 处理锚点与查询无斜杠的 URL`() {
        assertEquals("https://webstatic.mihoyo.com", ServerApi.originOf("https://webstatic.mihoyo.com#a/b"))
        assertEquals("https://webstatic.mihoyo.com", ServerApi.originOf("https://webstatic.mihoyo.com?x=1"))
        assertEquals(
            "https://webstatic.mihoyo.com",
            ServerApi.originOf("https://webstatic.mihoyo.com/ys/event/tcgmatch/index.html#/homePage"),
        )
    }

    // ---- DEFAULT_AVATAR_URL：主机收敛 + 兜底完整 ----

    @Test
    fun `默认头像主机随 LOGIN_URL 的 origin 收敛，路径为静态资源相对路径`() {
        assertEquals(
            ServerApi.originOf(BuildConfig.LOGIN_URL) +
                "/upload/event/2023-05-16/7c0b9ec9dac9c3b75204bdebef3cb794_9053060040416908582.png",
            DEFAULT_AVATAR_URL,
        )
        // 默认构建下与历史字面量逐字节一致（换域名只影响 LOGIN_URL 时头像跟随）
        assertEquals(ServerApi.DEFAULT_AVATAR_URL, DEFAULT_AVATAR_URL)
    }

    // ---- lang 透传：显式传参必须改写 URL 里的 lang ----

    @Test
    fun `cardDetailUrl 显式 lang 覆盖模板默认 zh-cn`() {
        assertTrue(cardDetailUrl(1, AppLanguage.English).endsWith("lang=en-us"))
        val tw = cardDetailUrl(1, AppLanguage.TraditionalChinese)
        assertTrue(tw.endsWith("lang=zh-tw"))
        assertTrue(!Regex("lang=zh-cn").containsMatchIn(tw))
        // 默认参数保持简中（既有调用点/测试语义不变）
        assertTrue(cardDetailUrl(1).endsWith("lang=zh-cn"))
    }

    @Test
    fun `凭据接口与登录态接口 lang 随入参`() {
        val en = userInfoUrl(ServerId.Official, AppLanguage.English)
        assertTrue(en, en.endsWith("lang=en-us"))
        for ((i, lang) in AppLanguage.entries.withIndex()) {
            val uid = "u$i"
            assertTrue(gameRecordsUrl(uid, ServerId.Channel, lang).contains("lang=${lang.languageTag}"))
            assertTrue(myHomePageUrl(uid, ServerId.Channel, lang).contains("lang=${lang.languageTag}"))
            assertTrue(peakRankUrl(uid, ServerId.Channel, lang).contains("lang=${lang.languageTag}"))
            assertTrue(competitionRankUrl(uid, ServerId.Channel, lang).contains("lang=${lang.languageTag}"))
            assertTrue(otherHomePageUrl("c", uid, ServerId.Channel, lang).contains("lang=${lang.languageTag}"))
        }
    }

    // ---- LocaleStrings.currentLanguage：数据层拿界面语言 ----

    @Test
    fun `未 attach 时 currentLanguage 回落简中`() {
        LocaleStrings.attach(null)
        assertEquals(AppLanguage.SimplifiedChinese, LocaleStrings.currentLanguage())
    }

    @Test
    fun `仅注入 resolver（无 Context）时 currentLanguage 仍回落简中`() {
        LocaleStrings.installResolverForTest { "x" }
        try {
            assertEquals(AppLanguage.SimplifiedChinese, LocaleStrings.currentLanguage())
        } finally {
            LocaleStrings.installResolverForTest(null)
        }
    }
}
