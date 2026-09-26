// 关于页正文（V9-G）：更新检查 / 公告 / Bug 上报接线 + 原「说明」弹窗的既有人工文案。
//
// 本文件刻意做成「内容 Column」而非弹窗：宿主 AboutDialog 的 AlertDialog 已经是导航入口
// （GigiNavHost 里的 aboutOpen），换壳等于改导航——留给后续棒合并时统一处理。
//
// 文案 i18n（V9-F1 keys）：「关于本软件/使用要点/数据来源与权限/原理与致谢/反馈」各段已接线
// stringResource；更新检查与公告相关（「更新与反馈」区块、两个弹窗、UpdateStateLine 状态句）
// 尚无对应 key，暂留中文。
// 链接跳转必须兜 ActivityNotFoundException：国内设备无默认浏览器、或浏览器被停用时，
// 不兜就是点一下崩一次；兜法是「复制链接 + 提示」，用户仍有路可走。

package com.gigi.tcg.ui.about

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gigi.tcg.BuildConfig
import com.gigi.tcg.R
import com.gigi.tcg.data.github.GITHUB_RELEASES_URL
import com.gigi.tcg.data.github.GITHUB_REPO_URL
import com.gigi.tcg.ui.components.LocalToast

/** 仓库 LICENSE 页（V9-D 已落 Apache 2.0 全文） */
private const val LICENSE_URL = "$GITHUB_REPO_URL/blob/main/LICENSE"

/** 原「反馈」入口：B 站主页，与 GitHub Issue 并列保留 */
private const val BILIBILI_FEEDBACK_URL = "https://space.bilibili.com/560719483"

/** label/value 均存 @StringRes id，渲染期再解析（顶层 val 拿不到 Compose 作用域） */
private val PROJECT_FACTS = listOf(
    R.string.about_fact_project to R.string.about_value_project,
    R.string.about_fact_form to R.string.about_value_form,
)

/** 「开源协议」无对应 key，三语版暂沿用原文（V9-H 不许改 res/） */
private const val LICENSE_FACT_LABEL = "开源协议"
private const val LICENSE_FACT_VALUE = "Apache License 2.0，完整开源"

private val USAGE_TIP_IDS = listOf(
    R.string.about_tip_delay,
    R.string.about_tip_restart,
    R.string.about_tip_recent,
    R.string.about_tip_uid_query,
    R.string.about_tip_sunset,
)

private val DATA_SOURCE_BULLET_IDS = listOf(
    R.string.about_data_bullet_source,
    R.string.about_data_bullet_credential,
)

/** 更新检查/公告说明句无对应 key，三语版暂沿用原文 */
private const val DATA_SOURCE_UPDATE_BULLET =
    "• 更新检查与公告只读取公开仓库的静态文件，经国内镜像访问，全程不携带任何账号凭据。"

/** 关于页 VM：宿主不传时自建（VM 挂在 Activity 作用域，跨开关复用同一份已读队列） */
@Composable
fun rememberAboutViewModel(): AboutViewModel {
    val app = LocalContext.current.applicationContext as Application
    return viewModel(factory = AboutViewModel.factory(app))
}

@Composable
fun AboutScreen(
    viewModel: AboutViewModel = rememberAboutViewModel(),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val toast = LocalToast.current
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val announcements by viewModel.pendingAnnouncements.collectAsStateWithLifecycle()

    val copyText: (String, String) -> Unit = { text, feedback ->
        @Suppress("DEPRECATION")
        clipboard.setText(AnnotatedString(text))
        toast(feedback)
    }
    val openUrl: (String) -> Unit = { url ->
        if (!startViewIntent(context, url)) {
            copyText(url, "未找到可用的浏览器，链接已复制")
        }
    }

    LaunchedEffect(Unit) { viewModel.loadAnnouncements() }

    // 弹窗可见性从状态派生，而不是把「已看过弹窗」写回 VM：
    // VM 作用域是 Activity，写回去会导致用户下次进关于页看不到刚刚查到的新版本。
    var updateDialogOpen by remember { mutableStateOf(false) }
    LaunchedEffect(updateState) { updateDialogOpen = updateState is UpdateState.Available }

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        SectionTitle(stringResource(R.string.about_section_software))
        val factRows = PROJECT_FACTS.map { (labelId, valueId) ->
            stringResource(labelId) to stringResource(valueId)
        } + (LICENSE_FACT_LABEL to LICENSE_FACT_VALUE)
        factRows.forEach { (label, value) ->
            AboutParagraph {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(label) }
                append("：$value")
            }
        }

        SectionTitle("版本")
        AboutParagraph { append("版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）") }
        AboutParagraph {
            append("• 源码仓库：")
            appendLink("Depolarization/GIGI2", GITHUB_REPO_URL)
            append("　开源协议：")
            appendLink("Apache License 2.0", LICENSE_URL)
        }

        SectionTitle("更新与反馈")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = viewModel::checkUpdate,
                enabled = updateState !is UpdateState.Checking,
            ) {
                if (updateState is UpdateState.Checking) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (updateState is UpdateState.Checking) "检查中…" else "检查更新")
            }
            OutlinedButton(onClick = { openUrl(viewModel.buildBugReportUrl()) }) {
                Text("反馈问题")
            }
        }
        TextButton(
            onClick = { copyText(viewModel.buildCopyableReport(), "诊断信息已复制") },
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text("复制诊断信息")
        }
        UpdateStateLine(updateState)

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        SectionTitle(stringResource(R.string.about_section_tips))
        // AboutParagraph 的 lambda 不是 Composable scope，stringResource 一律先在组合内解析
        USAGE_TIP_IDS.forEach { tipId ->
            val tip = stringResource(tipId)
            AboutParagraph { append("• $tip") }
        }

        SectionTitle(stringResource(R.string.about_section_data))
        val dataCover = stringResource(R.string.about_data_bold_cover)
        val dataMid = stringResource(R.string.about_data_mid)
        val dataDetail = stringResource(R.string.about_data_bold_detail)
        val dataEnd = stringResource(R.string.about_data_end)
        AboutParagraph {
            append("• ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(dataCover) }
            append(dataMid)
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(dataDetail) }
            append(dataEnd)
        }
        DATA_SOURCE_BULLET_IDS.forEach { bulletId ->
            val bullet = stringResource(bulletId)
            AboutParagraph { append("• $bullet") }
        }
        AboutParagraph { append(DATA_SOURCE_UPDATE_BULLET) }

        SectionTitle(stringResource(R.string.about_section_thanks))
        val thanksIntro = stringResource(R.string.about_thanks_intro)
        val thanksLink = stringResource(R.string.about_thanks_link)
        val thanksPeriod = stringResource(R.string.about_thanks_period)
        val thanksBody = stringResource(R.string.about_thanks_body)
        AboutParagraph {
            append(thanksIntro)
            appendLink(thanksLink, "https://www.bilibili.com/video/BV1boF5z6E9G")
            append(thanksPeriod)
        }
        AboutParagraph { append(thanksBody) }

        SectionTitle(stringResource(R.string.about_section_feedback))
        val feedbackHint = stringResource(R.string.about_feedback_hint)
        AboutParagraph {
            append("• $feedbackHint")
            appendLink("B 站主页", BILIBILI_FEEDBACK_URL)
        }
    }

    val available = (updateState as? UpdateState.Available)?.info
    if (updateDialogOpen && available != null) {
        AlertDialog(
            onDismissRequest = { updateDialogOpen = false },
            title = { Text("发现新版本") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "当前 ${BuildConfig.VERSION_NAME} → 最新 ${available.latestVersionName ?: "未知"}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    available.releaseNotes?.takeIf { it.isNotBlank() }?.let { notes ->
                        Spacer(Modifier.size(8.dp))
                        Text(notes, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "来源：${available.source}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        openUrl(available.downloadUrl?.takeIf { it.isNotBlank() } ?: GITHUB_RELEASES_URL)
                        updateDialogOpen = false
                    },
                ) { Text("去下载") }
            },
            dismissButton = {
                TextButton(onClick = { updateDialogOpen = false }) { Text("稍后再说") }
            },
        )
    }

    // 公告逐条确认：只弹队列第一条，「知道了」即记已读，队列自然推进到下一条。
    announcements.firstOrNull()?.let { announcement ->
        AlertDialog(
            onDismissRequest = { viewModel.markAnnouncementRead(announcement.id) },
            title = { Text(announcement.title.ifBlank { "公告" }) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(announcement.body, style = MaterialTheme.typography.bodyMedium)
                    announcement.url?.takeIf { it.isNotBlank() }?.let { url ->
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "点击查看详细内容",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { openUrl(url) },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.markAnnouncementRead(announcement.id) }) {
                    Text(if (announcement.url.isNullOrBlank()) "知道了" else "查看链接")
                }
            },
        )
    }
}

@Composable
private fun UpdateStateLine(state: UpdateState) {
    val message = when (state) {
        UpdateState.Idle -> null
        UpdateState.Checking -> "正在检查更新…"
        is UpdateState.UpToDate -> "已是最新版本（${state.source}）"
        is UpdateState.Available -> "发现新版本 ${state.info.latestVersionName ?: ""}，点「去下载」获取"
        is UpdateState.Failed -> "检查失败，请稍后重试"
    } ?: return
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
    if (state is UpdateState.Failed && !state.message.isNullOrBlank()) {
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 打开外部链接；设备无浏览器（或被停用）时返回 false，由调用方兜底复制 */
private fun startViewIntent(context: Context, url: String): Boolean = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
    true
} catch (error: ActivityNotFoundException) {
    false
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun AboutParagraph(build: AnnotatedString.Builder.() -> Unit) {
    Text(
        text = buildAnnotatedString(build),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

private fun AnnotatedString.Builder.appendLink(text: String, url: String) {
    pushLink(LinkAnnotation.Url(url = url))
    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(text) }
    pop()
}
