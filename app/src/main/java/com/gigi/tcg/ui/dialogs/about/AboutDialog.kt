package com.gigi.tcg.ui.dialogs.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gigi.tcg.BuildConfig
import com.gigi.tcg.R
import com.gigi.tcg.data.github.GITHUB_RELEASES_URL
import com.gigi.tcg.ui.about.BILIBILI_FEEDBACK_URL
import com.gigi.tcg.ui.about.AboutScreen
import com.gigi.tcg.ui.about.UpdateState
import com.gigi.tcg.ui.about.isCheckFinished
import com.gigi.tcg.ui.about.rememberAboutViewModel
import com.gigi.tcg.ui.about.rememberLinkOpener

// 关于对话框外壳（V10-B）：正文交给 AboutScreen，按钮语义收拢到本对话框底部一行——
// [反馈]（跳 B 站，无浏览器时兜底复制链接）[检查更新]（Checking 期禁用防重复触发）[关闭]。
// Material3 AlertDialog 只有 confirm/dismiss 两个槽位，为保「同一行、顺序固定」把三枚
// TextButton 一起放进 confirmButton 的 Row 里。
//
// 检查结果不叠在「关于」之上：AboutDialog 整体换成结果弹窗，二者互斥。宿主 GigiNavHost
// 是 `if (aboutOpen) AboutDialog(...)`，先调 onClose 会连结果弹窗一起卸载，所以「关闭关于」
// 用内容整体替换实现，用户点掉结果弹窗时才真正 onClose 退出。
// VM 挂在 Activity 作用域：上一轮遗留的终态不再弹结果，只消费本次点出来的 Checking→终态跃迁；
// 标记存普通 remember，旋转后随组合体重置，不会重复弹。

@Composable
fun AboutDialog(onClose: () -> Unit) {
    val viewModel = rememberAboutViewModel()
    val openUrl = rememberLinkOpener()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()

    var checkRequested by remember { mutableStateOf(false) }
    var pendingResult by remember { mutableStateOf<UpdateState?>(null) }
    LaunchedEffect(updateState) {
        val state = updateState
        if (state is UpdateState.Checking) {
            checkRequested = true
        } else if (checkRequested && state.isCheckFinished) {
            pendingResult = state
            checkRequested = false
        }
    }

    val result = pendingResult
    if (result != null) {
        UpdateResultDialog(state = result, onOpenUrl = openUrl, onClose = onClose)
        return
    }

    val checking = updateState is UpdateState.Checking
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.about_title)) },
        text = { AboutScreen(viewModel = viewModel) },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { openUrl(BILIBILI_FEEDBACK_URL) }) {
                    Text(stringResource(R.string.action_feedback))
                }
                TextButton(
                    onClick = viewModel::checkUpdate,
                    enabled = !checking,
                ) {
                    Text(
                        stringResource(
                            if (checking) R.string.about_update_checking
                            else R.string.about_update_check,
                        ),
                    )
                }
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.action_close))
                }
            }
        },
    )
}

@Composable
private fun UpdateResultDialog(
    state: UpdateState,
    onOpenUrl: (String) -> Unit,
    onClose: () -> Unit,
) {
    when (state) {
        is UpdateState.Available -> {
            val info = state.info
            val unknown = stringResource(R.string.common_unknown)
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text(stringResource(R.string.about_update_dialog_title)) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            stringResource(
                                R.string.about_update_dialog_versions,
                                BuildConfig.VERSION_NAME,
                                info.latestVersionName ?: unknown,
                            ),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        info.releaseNotes?.takeIf { it.isNotBlank() }?.let { notes ->
                            Spacer(Modifier.size(8.dp))
                            Text(notes, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.size(8.dp))
                        Text(
                            stringResource(R.string.about_update_dialog_source, info.source),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onOpenUrl(info.downloadUrl?.takeIf { it.isNotBlank() } ?: GITHUB_RELEASES_URL)
                            onClose()
                        },
                    ) { Text(stringResource(R.string.about_update_dialog_download)) }
                },
                dismissButton = {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
                },
            )
        }
        is UpdateState.UpToDate -> SimpleResultDialog(
            title = stringResource(R.string.about_update_check),
            message = stringResource(R.string.about_update_status_up_to_date, state.source),
            onClose = onClose,
        )
        is UpdateState.Failed -> SimpleResultDialog(
            title = stringResource(R.string.about_update_check),
            message = stringResource(R.string.about_update_status_failed),
            detail = state.message,
            onClose = onClose,
        )
        else -> Unit
    }
}

@Composable
private fun SimpleResultDialog(
    title: String,
    message: String,
    onClose: () -> Unit,
    detail: String? = null,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column {
                Text(message)
                detail?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
        },
    )
}
