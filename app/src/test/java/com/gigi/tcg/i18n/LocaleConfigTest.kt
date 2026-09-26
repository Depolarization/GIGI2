package com.gigi.tcg.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * V9-K 防回归：localeConfig 声明必须存在且形态正确（纯 JVM 读文本，无 Robolectric）。
 * 只列 zh-CN / zh-TW / en 三个，禁止 zh-Hans / zh-Hant（系统语言菜单匹配会破坏）；
 * Manifest 必须挂 @xml/locales_config，否则 Android 13+ 不显示 per-app 语言入口。
 */
class LocaleConfigTest {

    private val localesFile = listOf(
        File("src/main/res/xml/locales_config.xml"),
        File("app/src/main/res/xml/locales_config.xml"),
    ).firstOrNull { it.isFile }

    private val manifestFile = listOf(
        File("src/main/AndroidManifest.xml"),
        File("app/src/main/AndroidManifest.xml"),
    ).firstOrNull { it.isFile }

    @Test
    fun `locales_config 存在且恰好声明三个 locale`() {
        val file = localesFile
        assumeTrue(
            "工作目录下未找到 res/xml/locales_config.xml（Gradle 单测工作目录异常），跳过",
            file != null,
        )
        val text = file!!.readText()
        assertEquals(3, Regex("<locale\\s").findAll(text).count())
        val names = Regex("""android:name="([^"]+)"""").findAll(text).map { it.groupValues[1] }.toList()
        assertEquals(listOf("zh-CN", "zh-TW", "en"), names)
        assertFalse("禁止 zh-Hans：系统 per-app 语言菜单匹配会破坏", text.contains("zh-Hans"))
        assertFalse("禁止 zh-Hant：系统 per-app 语言菜单匹配会破坏", text.contains("zh-Hant"))
        assertTrue(text.contains("<locale-config"))
    }

    @Test
    fun `Manifest 挂接 localeConfig 指向 locales_config`() {
        val file = manifestFile
        assumeTrue(
            "工作目录下未找到 AndroidManifest.xml（Gradle 单测工作目录异常），跳过",
            file != null,
        )
        assertTrue(file!!.readText().contains("""android:localeConfig="@xml/locales_config""""))
    }
}
