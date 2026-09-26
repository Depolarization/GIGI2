package com.gigi.tcg.i18n

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * 语言策略单测：只跟随系统，简中/繁中/英文之外一律 fallback 英文。
 * 判定优先级：country(TW/HK/MO) → script(Hant/Hans) → 默认简中。
 */
class AppLocaleTest {

    @Test
    fun `中文系按 country 判简繁`() {
        assertEquals(AppLanguage.SimplifiedChinese, AppLanguage.from(Locale("zh")))
        assertEquals(AppLanguage.SimplifiedChinese, AppLanguage.from(Locale("zh", "CN")))
        assertEquals(AppLanguage.SimplifiedChinese, AppLanguage.from(Locale("zh", "SG")))
        assertEquals(AppLanguage.SimplifiedChinese, AppLanguage.from(Locale("zh", "MY")))
        assertEquals(AppLanguage.TraditionalChinese, AppLanguage.from(Locale("zh", "TW")))
        assertEquals(AppLanguage.TraditionalChinese, AppLanguage.from(Locale("zh", "HK")))
        assertEquals(AppLanguage.TraditionalChinese, AppLanguage.from(Locale("zh", "MO")))
    }

    @Test
    fun `无 country 时按 script 兜底，country 判定优先于 script`() {
        assertEquals(AppLanguage.TraditionalChinese, AppLanguage.from(Locale.forLanguageTag("zh-Hant")))
        assertEquals(AppLanguage.SimplifiedChinese, AppLanguage.from(Locale.forLanguageTag("zh-Hans")))
        // Hans 配 TW：country 优先
        assertEquals(AppLanguage.TraditionalChinese, AppLanguage.from(Locale.forLanguageTag("zh-Hant-TW")))
        assertEquals(AppLanguage.TraditionalChinese, AppLanguage.from(Locale.forLanguageTag("zh-Hans-TW")))
        // Hant 配 CN：country 不命中繁区 ⇒ script 命中繁
        assertEquals(AppLanguage.TraditionalChinese, AppLanguage.from(Locale.forLanguageTag("zh-Hant-CN")))
        // Hans 配 CN ⇒ 简
        assertEquals(AppLanguage.SimplifiedChinese, AppLanguage.from(Locale.forLanguageTag("zh-Hans-CN")))
    }

    @Test
    fun `英文系保持英文`() {
        assertEquals(AppLanguage.English, AppLanguage.from(Locale("en")))
        assertEquals(AppLanguage.English, AppLanguage.from(Locale("en", "US")))
        assertEquals(AppLanguage.English, AppLanguage.from(Locale("en", "GB")))
    }

    @Test
    fun `其余语言与空值 fallback 英文`() {
        for (tag in listOf("ja", "ko", "fr", "de", "ru", "ar", "th", "vi", "id", "es", "pt", "it")) {
            assertEquals("fallback($tag)", AppLanguage.English, AppLanguage.from(Locale.forLanguageTag(tag)))
        }
        assertEquals(AppLanguage.English, AppLanguage.from(null))
        assertEquals(AppLanguage.English, AppLanguage.from(Locale.ROOT))
    }

    @Test
    fun `lang 参数即米哈游接口语言码`() {
        assertEquals("zh-cn", AppLanguage.SimplifiedChinese.apiLangParam())
        assertEquals("zh-tw", AppLanguage.TraditionalChinese.apiLangParam())
        assertEquals("en-us", AppLanguage.English.apiLangParam())
    }
}
