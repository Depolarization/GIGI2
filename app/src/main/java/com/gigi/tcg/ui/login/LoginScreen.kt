// 登录页（设计 §4.5：全屏 Scaffold，非弹窗）。布局/文案对齐 web LoginPage.tsx：
// 顶栏（首登「扫码登录」/ 添加账户「添加账户」）→ 标题 → 失效横幅/换服提示 → 服务器二选一 →
// 二维码（已扫描蒙层）→ 保存到相册按钮 + 自动删除说明 → 状态文案 → 重试按钮 → 底部说明。

package com.gigi.tcg.ui.login

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gigi.tcg.R
import com.gigi.tcg.data.ServerId
import com.gigi.tcg.i18n.displayName
import com.gigi.tcg.ui.dialogs.cardcover.CardImageSaver
import com.gigi.tcg.ui.dialogs.cardcover.albumRelativePath
import com.gigi.tcg.ui.dialogs.cardcover.exportDateText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    expiredNotice: Boolean = false,
    addAccount: Boolean = false,
    onCancelAddAccount: (() -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val server by viewModel.serverFlow.collectAsStateWithLifecycle()

    // 保存二维码的反馈走本页 Scaffold 的 Snackbar：本页挂在 AppGate 状态机上，
    // 拿不到 NavHost 内 provide 的 LocalToast（首登时 NavHost 根本不在组合里）。
    val appContext = LocalContext.current.applicationContext
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var savingQr by remember { mutableStateOf(false) }
    val savedToAlbumText = stringResource(R.string.toast_saved_to_album_path, albumRelativePath())
    val saveFailedText = stringResource(R.string.toast_save_failed)

    // 添加账户页存在上一级（当前账户主界面）：系统返回键等价"返回"按钮；
    // 首登页无上一级，不接管，保持系统默认（退出应用）。
    BackHandler(enabled = addAccount && onCancelAddAccount != null) {
        onCancelAddAccount?.invoke()
    }

    Scaffold(
        topBar = {
            // 两种入口共用本页，都给标题标明当前步骤：首登无上一级（无返回键），
            // 添加账户是 Main 上的叠加层、有返回键。
            CenterAlignedTopAppBar(
                title = {
                    Text(stringResource(if (addAccount) R.string.account_add else R.string.login_qr_title))
                },
                navigationIcon = {
                    if (addAccount) {
                        TextButton(onClick = { onCancelAddAccount?.invoke() }) {
                            Text(stringResource(R.string.action_back))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(if (addAccount) R.string.login_title_add else R.string.login_title),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )

                    if (expiredNotice) {
                        Text(
                            stringResource(R.string.login_expired_notice),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }

                    state.notice?.let { notice ->
                        Text(
                            notice,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ServerId.ALL.forEach { option ->
                            FilterChip(
                                selected = option == server,
                                onClick = { viewModel.selectServer(option) },
                                label = { Text(option.displayName()) },
                            )
                        }
                    }

                    QrBox(state)

                    val qrState = state as? LoginUiState.Qr
                    if (qrState != null) {
                        OutlinedButton(
                            enabled = !savingQr,
                            onClick = {
                                savingQr = true
                                scope.launch {
                                    val failure = try {
                                        // PNG：黑白码走 JPEG 会被压出噪点、影响扫码。
                                        // 位图与界面共用同一实例，不 recycle（回收会砸掉正在显示的二维码）。
                                        val uri = CardImageSaver(appContext).saveBitmap(
                                            bitmap = qrState.payload.asAndroidBitmap(),
                                            baseName = "扫码登录_${exportDateText()}",
                                            format = Bitmap.CompressFormat.PNG,
                                            subDir = null,
                                        )
                                        viewModel.onQrSaved(uri)
                                        null
                                    } catch (cancel: CancellationException) {
                                        throw cancel
                                    } catch (e: Exception) {
                                        e.message ?: saveFailedText
                                    }
                                    savingQr = false
                                    snackbarHostState.showSnackbar(failure ?: savedToAlbumText)
                                }
                            },
                        ) {
                            Text(stringResource(R.string.login_qr_save))
                        }

                        Text(
                            stringResource(R.string.login_qr_autodelete_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }

                    Text(
                        statusText(state),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )

                    if (state is LoginUiState.Failed) {
                        Button(onClick = { viewModel.retry() }) {
                            Text(stringResource(R.string.action_regenerate_qr))
                        }
                    }

                    Text(
                        stringResource(
                            if (addAccount) R.string.login_add_account_hint else R.string.login_qr_hint
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun QrBox(state: LoginUiState) {
    Box(contentAlignment = Alignment.Center) {
        val qr = state as? LoginUiState.Qr
        if (qr != null) {
            Image(
                bitmap = qr.payload,
                contentDescription = stringResource(R.string.login_qr_image_desc),
                modifier = Modifier.size(260.dp),
                contentScale = ContentScale.Fit,
            )
            if (qr.phase == QrPhase.Scanned) {
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .background(Color.Black.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = stringResource(R.string.login_qr_scanned_badge),
                            tint = Color.White,
                        )
                        Text(
                            stringResource(R.string.login_qr_scanned_confirm),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .background(Color.White, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (state !is LoginUiState.Failed) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun statusText(state: LoginUiState): String = when (state) {
    is LoginUiState.Checking -> stringResource(R.string.login_status_generating)
    is LoginUiState.Qr -> when (state.phase) {
        QrPhase.Waiting -> stringResource(R.string.login_status_waiting)
        QrPhase.Scanned -> stringResource(R.string.login_status_scanned)
        QrPhase.Refreshing -> stringResource(R.string.login_status_refreshing)
    }
    is LoginUiState.Finalizing -> stringResource(R.string.login_status_finalizing)
    is LoginUiState.Failed -> stringResource(R.string.login_status_failed, state.message)
    is LoginUiState.LoggedIn -> stringResource(R.string.login_status_success)
}
