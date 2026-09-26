// Bug 上报（V9-D，数据层预留，UI 接线由后续棒做）。
// 无自建服务器 ⇒ 两条落地路径都做：
// 1. 预填 GitHub Issue 链接（?title=&labels=bug&body=），交给系统浏览器/自定义 Tab 提交；
// 2. 预填文本复制到剪贴板（发给 B 站私信/群等非 GitHub 渠道时用）。
// 设备信息段写成「纯数据 + 纯格式化」两层：android.util.Build 与 Context 只在收集层出现，
// 格式化层可在 JVM 单测里断言，不必引 Robolectric。

package com.gigi.tcg.data.github

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 上报所需的运行环境快照（纯数据，便于单测与未来的网络上报复用） */
data class DeviceSnapshot(
    val appVersionName: String,
    val appVersionCode: Int,
    val androidRelease: String,
    val androidSdkInt: Int,
    val manufacturer: String,
    val model: String,
    val language: String,
    val installedAt: String,
)

class BugReporter(
    private val appVersionName: String,
    private val appVersionCode: Int,
) {
    /**
     * 预填 Issue 链接。body 由调用方给出（通常就是 buildAutoFilledBody 的结果）。
     * 参数一律百分号编码：中文、换行、'&'、'#' 直接拼进 URL 会截断 body 或造出假参数。
     */
    fun buildIssueUrl(title: String, body: String): String =
        "$GITHUB_ISSUES_URL/new?title=${encodeQueryValue(title)}" +
            "&labels=$BUG_LABEL&body=${encodeQueryValue(body)}"

    /** 预填 Issue 链接（标题带版本，正文 = 设备信息 + 待填写模板） */
    fun buildIssueUrl(summary: String, context: Context): String =
        buildIssueUrl("[$appVersionName] $summary", buildAutoFilledBody(context))

    /** Issue 正文：设备信息 + 让用户填写的模板段 */
    fun buildAutoFilledBody(context: Context): String =
        formatDeviceSnapshot(collectDeviceSnapshot(context)) + "\n\n" + ISSUE_TEMPLATE_BODY

    /** 剪贴板版：不依赖 GitHub，粘到任何反馈渠道都自带上下文 */
    fun buildCopyableReport(context: Context): String =
        "GIGI ${appVersionName}（$appVersionCode）问题反馈\n" +
            formatDeviceSnapshot(collectDeviceSnapshot(context)) + "\n\n" + ISSUE_TEMPLATE_BODY

    /** 从系统读快照：唯一的 Android 依赖点 */
    fun collectDeviceSnapshot(context: Context): DeviceSnapshot {
        val locale = Locale.getDefault()
        val installedAt = runCatching {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            INSTALL_TIME_FORMAT.format(Date(packageInfo.lastUpdateTime))
        }.getOrDefault("未知")
        return DeviceSnapshot(
            appVersionName = appVersionName,
            appVersionCode = appVersionCode,
            androidRelease = Build.VERSION.RELEASE ?: "未知",
            androidSdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER ?: "未知",
            model = Build.MODEL ?: "未知",
            language = "${locale.language}-${locale.country}",
            installedAt = installedAt,
        )
    }

    companion object {
        const val BUG_LABEL: String = "bug"

        /** 待填写段：与 .github/ISSUE_TEMPLATE/bug_report.yml 的字段保持一致，两边改一处就要改两处 */
        const val ISSUE_TEMPLATE_BODY: String =
            "### 问题描述\n\n" +
                "### 复现步骤\n1. \n2. \n\n" +
                "### 期望行为\n\n" +
                "### 实际行为\n\n" +
                "### 日志 / 截图\n"

        /**
         * query 值百分号编码（RFC 3986）。
         * 为什么不用 android.net.Uri.encode：Uri 在 JVM 单测里是抛「not mocked」的桩类，
         * 这条路径的编码正确性必须可测；手写编码器还能保证空格是 %20（URLEncoder 会编成 '+'，
         * GitHub 的 title 参数会把 '+' 当字面量显示）。
         */
        fun encodeQueryValue(value: String): String {
            if (value.isEmpty()) return ""
            val builder = StringBuilder(value.length + 16)
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val code = byte.toInt() and 0xFF
                if (code in UNRESERVED_CODES) {
                    builder.append(ASCII_CHARS[code])
                } else {
                    builder.append('%').append(HEX_DIGITS[code shr 4]).append(HEX_DIGITS[code and 0x0F])
                }
            }
            return builder.toString()
        }

        /** 报告正文的环境信息段（纯函数） */
        fun formatDeviceSnapshot(snapshot: DeviceSnapshot): String = buildString {
            appendLine("**应用版本**：${snapshot.appVersionName}（versionCode ${snapshot.appVersionCode}）")
            appendLine("**Android**：${snapshot.androidRelease}（API ${snapshot.androidSdkInt}）")
            appendLine("**设备**：${snapshot.manufacturer} ${snapshot.model}")
            appendLine("**系统语言**：${snapshot.language}")
            append("**安装/构建时间**：${snapshot.installedAt}")
        }

        private const val INSTALL_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"
        private val INSTALL_TIME_FORMAT = SimpleDateFormat(INSTALL_TIME_PATTERN, Locale.US)
        private const val HEX_DIGITS = "0123456789ABCDEF"

        /** A-Za-z0-9 与 -._~ 直出，其余全部转 %XX（表里存字符串，避开 Kotlin 1.9 起废弃的 Int→Char 转换） */
        private val UNRESERVED_CODES: Set<Int> = buildSet {
            addAll('0'.code..'9'.code)
            addAll('a'.code..'z'.code)
            addAll('A'.code..'Z'.code)
            addAll("-._~".map { it.code })
        }
        private val ASCII_CHARS: List<String> = (0..255).map { index ->
            String(byteArrayOf(index.toByte()), Charsets.US_ASCII)
        }
    }
}

/**
 * 未来的崩溃/反馈上报出口（自建服务器或第三方 SDK 到位后实现）。
 * 当前不实现网络上报：无服务器可用，GitHub Issue 是唯一落地路径。
 * TODO(V9-D 后续)：接入后端时，把 DeviceSnapshot + 堆栈 POST 到自有端点，并在 CredentialStore
 *   之外单独处理「上报即用户主动授权」的合规确认，不要复用米游社凭据通道。
 */
interface FeedbackSink {
    suspend fun submit(report: FeedbackReport): Result<Unit>
}

/** 上报载荷：分类 + 内容 + 环境快照文本（与 Issue 正文同构，换后端不必改调用方） */
data class FeedbackReport(
    val category: String,
    val content: String,
    val deviceInfo: String,
)
