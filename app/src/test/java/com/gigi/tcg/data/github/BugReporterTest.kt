// BugReporter 的语言策略 + URL 编码契约（V9-I）。
// 锁两件事：
// 1. Issue 文案恒中文——切换 JVM 默认 locale 也不变（这条是刻意决策，见 BugReporter 里 IssueText 的 KDoc）；
// 2. buildIssueUrl 产出必须是纯 ASCII 百分号编码串，中文/换行/&/# 都不允许裸奔。
// 不测 buildAutoFilledBody / buildCopyableReport / collectDeviceSnapshot：它们要 Context，
// 工程没引 Robolectric，Android 桩类只会抛「not mocked」。

package com.gigi.tcg.data.github

import java.net.URLDecoder
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BugReporterTest {
    private val reporter = BugReporter(appVersionName = "1.2.3", appVersionCode = 7)

    private lateinit var defaultLocale: Locale

    @Before
    fun saveLocale() {
        defaultLocale = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    // ===== Issue 文案：恒中文、与 locale 无关 =====

    @Test
    fun `Issue 模板正文逐字锁定 重构不得改动用户看到的内容`() {
        assertEquals(
            "### 问题描述\n\n" +
                "### 复现步骤\n1. \n2. \n\n" +
                "### 期望行为\n\n" +
                "### 实际行为\n\n" +
                "### 日志 / 截图\n",
            BugReporter.ISSUE_TEMPLATE_BODY,
        )
    }

    @Test
    fun `切到英文 locale 后 Issue 文案仍然是中文`() {
        val bodyZh = BugReporter.ISSUE_TEMPLATE_BODY
        val headerZh = reporter.buildReportHeader()
        val snapshotZh = BugReporter.formatDeviceSnapshot(TEST_SNAPSHOT)

        Locale.setDefault(Locale.ENGLISH)
        assertEquals(bodyZh, BugReporter.ISSUE_TEMPLATE_BODY)
        assertEquals(headerZh, reporter.buildReportHeader())
        assertEquals(snapshotZh, BugReporter.formatDeviceSnapshot(TEST_SNAPSHOT))
        assertTrue(BugReporter.ISSUE_TEMPLATE_BODY.contains("### 问题描述"))
        assertTrue(reporter.buildReportHeader().contains("问题反馈"))
    }

    @Test
    fun `土耳其 locale 也不会改写字母 i 造成文案漂移`() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        assertTrue(reporter.buildReportHeader().contains("GIGI 1.2.3（7）问题反馈"))
        assertTrue(BugReporter.ISSUE_TEMPLATE_BODY.contains("### 日志 / 截图"))
    }

    @Test
    fun `设备信息段字段名是中文 且把快照值原样带出`() {
        val text = BugReporter.formatDeviceSnapshot(TEST_SNAPSHOT)
        listOf("应用版本", "Android", "设备", "系统语言", "安装/构建时间").forEach { label ->
            assertTrue("$label 缺失", text.contains(label))
        }
        assertTrue(text, text.contains("Xiaomi 23049RAD8C"))
        assertTrue(text, text.contains("zh-CN"))
    }

    // ===== buildIssueUrl 编码 =====

    @Test
    fun `完整 Issue 链接是纯 ASCII：无裸中文 无换行 无空格`() {
        val url = reporter.buildIssueUrl("导出崩 & 卡死\n第二行", BugReporter.ISSUE_TEMPLATE_BODY)
        // Uri 是 Android API、工程无 Robolectric ⇒ 不做 Uri.parse 往返还原（UpdateCheckerTest 里
        // 已用 URLDecoder 覆盖还原），这里只锁「URL 传输层安全」：全字符必须是可打印 ASCII。
        assertFalse(url, Regex("[^\\x20-\\x7E]").containsMatchIn(url))
        assertFalse(url, url.contains("\n"))
        assertFalse(url, url.contains("\r"))
        assertFalse(url, url.contains(" "))
        assertFalse(url, Regex("[\\u4E00-\\u9FFF]").containsMatchIn(url))
        assertTrue(url, url.startsWith("https://github.com/Depolarization/GIGI2/issues/new?title="))
        assertTrue(url, url.contains("&labels=bug&body="))
    }

    @Test
    fun `body 里的保留字符编码后不会被解析成新参数`() {
        val body = "A & B # C ? D = E\nF"
        val url = reporter.buildIssueUrl("t", body)
        val bodyValue = url.substringAfter("&body=")
        assertEquals("body 段不允许裸 &", bodyValue, bodyValue.substringBefore("&"))
        assertFalse(bodyValue, bodyValue.contains("#"))
        assertFalse(bodyValue, bodyValue.contains("?"))
        assertFalse(bodyValue, bodyValue.contains("+")) // 空格必须是 %20，'+' 会被 GitHub 当字面量
        assertEquals(body, URLDecoder.decode(bodyValue, "UTF-8"))
    }

    @Test
    fun `标题里的中文与版本前缀编码后能原样解回`() {
        val url = reporter.buildIssueUrl("[1.2.3] 长图导出崩溃", "b")
        val title = url.substringAfter("title=").substringBefore("&labels=")
        assertEquals("[1.2.3] 长图导出崩溃", URLDecoder.decode(title, "UTF-8"))
    }

    companion object {
        private val TEST_SNAPSHOT = DeviceSnapshot(
            appVersionName = "1.2.3",
            appVersionCode = 7,
            androidRelease = "14",
            androidSdkInt = 34,
            manufacturer = "Xiaomi",
            model = "23049RAD8C",
            language = "zh-CN",
            installedAt = "2026-09-26 10:00:00",
        )
    }
}
