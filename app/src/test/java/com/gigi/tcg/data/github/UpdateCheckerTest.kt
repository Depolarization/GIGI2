// GitHub 基础设施行为单测（V9-D）：镜像回退链顺序 + Issue 链接编码 + 设备信息段。
// UpdateChecker.check() 走网络且落 android.util.Log，不在 JVM 单测范围内；
// 这里锁的是「决定行为」的那几个纯函数——链条顺序错了、编码漏了，现场就是拉不到更新。

package com.gigi.tcg.data.github

import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    private val reporter = BugReporter(appVersionName = "1.2.3", appVersionCode = 7)

    // ===== 镜像回退链 =====

    @Test
    fun `候选顺序即降级顺序 jsDelivr 实测最稳压轴`() {
        val candidates = GitHubMirror.candidates(UPDATE_INFO_PATH)
        assertEquals(4, candidates.size)
        assertEquals(GitHubMirror.jsDelivr(UPDATE_INFO_PATH), candidates[0])
        assertEquals(GitHubMirror.ghProxy(UPDATE_INFO_PATH), candidates[1])
        assertEquals(GitHubMirror.ghFastTop(UPDATE_INFO_PATH), candidates[2])
        assertEquals(GitHubMirror.ghProxyNet(UPDATE_INFO_PATH), candidates[3])
    }

    @Test
    fun `镜像 URL 覆盖四种实现且都指到同一 owner repo ref path`() {
        val path = "announcement.json"
        GitHubMirror.candidates(path, "v1.0.0").forEach { url ->
            assertTrue(url, url.contains("Depolarization"))
            assertTrue(url, url.contains("GIGI2"))
            assertTrue(url, url.contains("v1.0.0"))
            assertTrue(url, url.endsWith(path))
        }
        // raw 官方域名国内超时，只允许出现在镜像前缀之后，不作为独立候选
        assertFalse(GitHubMirror.candidates(path).any { it.startsWith("https://raw.githubusercontent.com") })
        assertTrue(GitHubMirror.ghProxy(path).contains("raw.githubusercontent.com"))
    }

    @Test
    fun `镜像标签用于 source 排障字段`() {
        assertEquals("jsDelivr", GitHubMirror.labelOf(GitHubMirror.jsDelivr("x.json")))
        assertEquals("gh-proxy", GitHubMirror.labelOf(GitHubMirror.ghProxy("x.json")))
        assertEquals("ghfast.top", GitHubMirror.labelOf(GitHubMirror.ghFastTop("x.json")))
        assertEquals("ghproxy.net", GitHubMirror.labelOf(GitHubMirror.ghProxyNet("x.json")))
        assertEquals("unknown", GitHubMirror.labelOf("https://evil.example/x"))
    }

    @Test
    fun `更新检查走官方 API 路径不带镜像前缀`() {
        assertEquals("https://api.github.com", GITHUB_API_BASE)
        assertEquals(
            "https://api.github.com/repos/Depolarization/GIGI2/releases/latest",
            "$GITHUB_API_BASE/repos/${GitHubMirror.REPO_OWNER}/${GitHubMirror.REPO_NAME}/releases/latest",
        )
    }

    // ===== Issue 链接编码 =====

    @Test
    fun `中文换行与保留字符必须百分号编码`() {
        assertEquals("1.2.3-beta._~", BugReporter.encodeQueryValue("1.2.3-beta._~"))
        assertEquals("a%20b", BugReporter.encodeQueryValue("a b"))
        assertEquals("%E9%95%BF%E5%9B%BE", BugReporter.encodeQueryValue("长图"))
        assertEquals("%26", BugReporter.encodeQueryValue("&"))
        assertEquals("%23", BugReporter.encodeQueryValue("#"))
        assertEquals("%3D", BugReporter.encodeQueryValue("="))
        assertEquals("a%0Ab", BugReporter.encodeQueryValue("a\nb"))
        assertEquals("a%0D%0Ab", BugReporter.encodeQueryValue("a\r\nb"))
        assertEquals("", BugReporter.encodeQueryValue(""))
    }

    @Test
    fun `buildIssueUrl 编码后参数不串味且能原样解回`() {
        val title = "长图导出崩溃 & 换行\n第二行"
        val body = "### 问题描述\n点击导出就崩 #1"
        val url = reporter.buildIssueUrl(title, body)

        assertTrue(url, url.startsWith("https://github.com/Depolarization/GIGI2/issues/new?title="))
        assertTrue(url, url.contains("&labels=bug&body="))
        // body 段里的裸换行会把 URL 截断，必须全是百分号
        assertFalse(url, url.contains("\n"))
        assertFalse(url, url.contains(" "))
        assertFalse(url, url.contains("&换行"))

        val query = url.substringAfter("?")
        val params = query.split("&").map { it.substringBefore("=") to it.substringAfter("=") }
        val decoded = params.toMap().mapValues { URLDecoder.decode(it.value, "UTF-8") }
        assertEquals(title, decoded["title"])
        assertEquals(body, decoded["body"])
        assertEquals("bug", decoded["labels"])
    }

    @Test
    fun `body 中的 & 不会被解释成第二个参数`() {
        val url = reporter.buildIssueUrl("t", "A & B & C")
        val bodyValue = url.substringAfter("&body=")
        assertFalse(bodyValue, bodyValue.contains("&"))
        assertEquals("A & B & C", URLDecoder.decode(bodyValue, "UTF-8"))
    }

    // ===== 设备信息段（buildAutoFilledBody 的纯逻辑部分）=====

    @Test
    fun `设备信息段包含排障必需的五项`() {
        val snapshot = DeviceSnapshot(
            appVersionName = "1.2.3",
            appVersionCode = 7,
            androidRelease = "14",
            androidSdkInt = 34,
            manufacturer = "Xiaomi",
            model = "23049RAD8C",
            language = "zh-CN",
            installedAt = "2026-09-26 10:00:00",
        )
        val text = BugReporter.formatDeviceSnapshot(snapshot)
        assertTrue(text, text.contains("1.2.3（versionCode 7）"))
        assertTrue(text, text.contains("14（API 34）"))
        assertTrue(text, text.contains("Xiaomi 23049RAD8C"))
        assertTrue(text, text.contains("zh-CN"))
        assertTrue(text, text.contains("2026-09-26 10:00:00"))
        // 每行都要换行，最后一行不留多余空行（拼进 Issue 正文会多出一段空白）
        assertEquals(text.lines().size - 1, text.count { it == '\n' })
    }

    @Test
    fun `Issue 模板段与表单字段一致`() {
        val template = BugReporter.ISSUE_TEMPLATE_BODY
        listOf("问题描述", "复现步骤", "期望行为", "实际行为").forEach { section ->
            assertTrue("$section 缺失", template.contains(section))
        }
    }
}
