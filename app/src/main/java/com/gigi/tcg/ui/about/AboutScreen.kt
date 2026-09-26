// 关于页正文（V9-G）：更新检查 / 公告 / Bug 上报接线 + 原「说明」弹窗的既有人工文案。
//
// 本文件刻意做成「内容 Column」而非弹窗：宿主 AboutDialog 的 AlertDialog 已经是导航入口
// （GigiNavHost 里的 aboutOpen），换壳等于改导航——留给后续棒合并时统一处理。
//
// 文案全部走 R.string（V9-F1 keys，F3 补齐更新/公告/反馈段与排版 glue）。
// 🔴 项目符号 / 「label：value」分隔符 / 括号一律不写死在代码里，改由 about_bullet、
// about_label_separator 等资源提供，否则英文设备会带着全角标点。
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
    R.string.about_fact_license to R.string.about_value_license,
)

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
    R.string.about_bullet_update_check,
)

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
            copyText(url, context.getString(R.string.about_no_browser))
        }
    }

    LaunchedEffect(Unit) { viewModel.loadAnnouncements() }

    // 弹窗可见性从状态派生，而不是把「已看过弹窗」写回 VM：
    // VM 作用域是 Activity，写回去会导致用户下次进关于页看不到刚刚查到的新版本。
    var updateDialogOpen by remember { mutableStateOf(false) }
    LaunchedEffect(updateState) { updateDialogOpen = updateState is UpdateState.Available }

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        val bullet = stringResource(R.string.about_bullet)
        val labelSep = stringResource(R.string.about_label_separator)
        SectionTitle(stringResource(R.string.about_section_software))
        val factRows = PROJECT_FACTS.map { (labelId, valueId) ->
            stringResource(labelId) to stringResource(valueId)
        }
        factRows.forEach { (label, value) ->
            AboutParagraph {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(label) }
                append("$labelSep$value")
            }
        }

        SectionTitle(stringResource(R.string.about_section_version))
        val versionLine = stringResource(R.string.about_version_line, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        val repoLabel = stringResource(R.string.about_bullet_repo)
        val licenseLabel = stringResource(R.string.about_bullet_license)
        AboutParagraph { append(versionLine) }
        AboutParagraph {
            append(bullet)
            append(repoLabel)
            appendLink("Depolarization/GIGI2", GITHUB_REPO_URL)
            append(licenseLabel)
            appendLink("Apache License 2.0", LICENSE_URL)
        }

        SectionTitle(stringResource(R.string.about_section_update_feedback))
        // onClick 不是 Composable 作用域，文案先在组合里解析再捕获
        val issueSummary = stringResource(R.string.about_issue_summary)
        val copiedReport = stringResource(R.string.about_copied_report)
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
                Text(
                    stringResource(
                        if (updateState is UpdateState.Checking) R.string.about_update_checking
                        else R.string.about_update_check,
                    ),
                )
            }
            OutlinedButton(onClick = { openUrl(viewModel.buildBugReportUrl(issueSummary)) }) {
                Text(stringResource(R.string.about_feedback_issue))
            }
        }
        TextButton(
            onClick = { copyText(viewModel.buildCopyableReport(), copiedReport) },
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(stringResource(R.string.about_copy_report))
        }
        UpdateStateLine(updateState)

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        SectionTitle(stringResource(R.string.about_section_tips))
        // AboutParagraph 的 lambda 不是 Composable scope，stringResource 一律先在组合内解析
        USAGE_TIP_IDS.forEach { tipId ->
            val tip = stringResource(tipId)
            AboutParagraph { append("$bullet$tip") }
        }

        SectionTitle(stringResource(R.string.about_section_data))
        val dataCover = stringResource(R.string.about_data_bold_cover)
        val dataMid = stringResource(R.string.about_data_mid)
        val dataDetail = stringResource(R.string.about_data_bold_detail)
        val dataEnd = stringResource(R.string.about_data_end)
        AboutParagraph {
            append(bullet)
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(dataCover) }
            append(dataMid)
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(dataDetail) }
            append(dataEnd)
        }
        DATA_SOURCE_BULLET_IDS.forEach { bulletId ->
            val text = stringResource(bulletId)
            AboutParagraph { append("$bullet$text") }
        }

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
        val bilibiliHome = stringResource(R.string.about_link_bilibili_home)
        AboutParagraph {
            append("$bullet$feedbackHint")
            appendLink(bilibiliHome, BILIBILI_FEEDBACK_URL)
        }
    }

    val available = (updateState as? UpdateState.Available)?.info
    if (updateDialogOpen && available != null) {
        val unknown = stringResource(R.string.common_unknown)
        AlertDialog(
            onDismissRequest = { updateDialogOpen = false },
            title = { Text(stringResource(R.string.about_update_dialog_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(
                            R.string.about_update_dialog_versions,
                            BuildConfig.VERSION_NAME,
                            available.latestVersionName ?: unknown,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    available.releaseNotes?.takeIf { it.isNotBlank() }?.let { notes ->
                        Spacer(Modifier.size(8.dp))
                        Text(notes, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(
                        stringResource(R.string.about_update_dialog_source, available.source),
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
                ) { Text(stringResource(R.string.about_update_dialog_download)) }
            },
            dismissButton = {
                TextButton(onClick = { updateDialogOpen = false }) {
                    Text(stringResource(R.string.about_update_dialog_later))
                }
            },
        )
    }

    // 公告逐条确认：只弹队列第一条，「知道了」即记已读，队列自然推进到下一条。
    announcements.firstOrNull()?.let { announcement ->
        AlertDialog(
            onDismissRequest = { viewModel.markAnnouncementRead(announcement.id) },
            title = {
                Text(
                    announcement.title.ifBlank { stringResource(R.string.about_announcement_title) },
                )
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(announcement.body, style = MaterialTheme.typography.bodyMedium)
                    announcement.url?.takeIf { it.isNotBlank() }?.let { url ->
                        Spacer(Modifier.size(8.dp))
                        Text(
                            stringResource(R.string.about_announcement_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { openUrl(url) },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.markAnnouncementRead(announcement.id) }) {
                    Text(
                        stringResource(
                            if (announcement.url.isNullOrBlank()) R.string.about_announcement_ok
                            else R.string.about_announcement_link,
                        ),
                    )
                }
            },
        )
    }
}

@Composable
private fun UpdateStateLine(state: UpdateState) {
    val message = when (state) {
        UpdateState.Idle -> null
        UpdateState.Checking -> stringResource(R.string.about_update_status_checking)
        is UpdateState.UpToDate -> stringResource(R.string.about_update_status_up_to_date, state.source)
        is UpdateState.Available -> stringResource(
            R.string.about_update_status_available,
            state.info.latestVersionName.orEmpty(),
        )
        is UpdateState.Failed -> stringResource(R.string.about_update_status_failed)
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
