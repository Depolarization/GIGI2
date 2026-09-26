package com.gigi.tcg.i18n

import android.content.Context
import android.os.Build
import java.util.Locale

/**
 * 应用语言策略：**只跟随系统，无应用内切换入口**（产品要求）。
 * 支持简中(zh-CN) / 繁中(zh-TW,zh-HK,zh-MO,Hant) / 英文(en-*)，其余一律 fallback 英文。
 */
enum class AppLanguage(val languageTag: String) {
    /** 简体中文 */
    SimplifiedChinese("zh-cn"),

    /** 繁体中文 */
    TraditionalChinese("zh-tw"),

    /** 英文（fallback） */
    English("en-us");

    companion object {
        private val TRADITIONAL_REGIONS = setOf("TW", "HK", "MO")

        /**
         * 依据系统 Locale 判定应用语言。
         * 优先级：country(TW/HK/MO) → script(Hant/Hans) → 默认简中；
         * 非 zh 语言族一律英文。
         */
        fun from(locale: Locale?): AppLanguage {
            if (locale == null) return English
            return when (locale.language.lowercase()) {
                "zh" -> when {
                    locale.country.uppercase() in TRADITIONAL_REGIONS -> TraditionalChinese
                    locale.script.uppercase() == "HANT" -> TraditionalChinese
                    else -> SimplifiedChinese
                }
                "en" -> English
                else -> English
            }
        }
    }
}

/** 当前系统语言对应的应用语言 */
fun currentAppLanguage(context: Context): AppLanguage = AppLanguage.from(currentSystemLocale(context))

/** 取系统首选 Locale（API 24+ 用 configuration.locales[0]，更低版本回落已废弃字段） */
fun currentSystemLocale(context: Context): Locale {
    val configuration: android.content.res.Configuration = context.resources.configuration
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        configuration.locales[0] ?: Locale.ENGLISH
    } else {
        @Suppress("DEPRECATION")
        configuration.locale ?: Locale.ENGLISH
    }
}

/** 米哈游接口的 lang 参数值（zh-cn / zh-tw / en-us）——服务端认这三个值 */
fun AppLanguage.apiLangParam(): String = languageTag
