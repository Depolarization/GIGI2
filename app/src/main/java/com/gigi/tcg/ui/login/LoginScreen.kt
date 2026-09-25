// 登录页（设计 §4.5：全屏 Scaffold，非弹窗）。布局/文案对齐 web LoginPage.tsx：
// 标题 → 失效横幅/换服提示 → 服务器二选一 → 二维码（已扫描蒙层）→ 状态文案 → 重试按钮 → 底部说明。

package com.gigi.tcg.ui.login

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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gigi.tcg.data.ServerId

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

    // 添加账户页存在上一级（当前账户主界面）：系统返回键等价"返回"按钮；
    // 首登页无上一级，不接管，保持系统默认（退出应用）。
    BackHandler(enabled = addAccount && onCancelAddAccount != null) {
        onCancelAddAccount?.invoke()
    }

    Scaffold(
        topBar = {
            if (addAccount) {
                CenterAlignedTopAppBar(
                    title = { Text("添加账户") },
                    navigationIcon = {
                        TextButton(onClick = { onCancelAddAccount?.invoke() }) {
                            Text("返回")
                        }
                    },
                )
            }
        },
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
                        if (addAccount) "GIGI · 添加账户" else "GIGI · 七圣召唤赛事工具",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )

                    if (expiredNotice) {
                        Text(
                            "登录已失效，请重新扫码登录",
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
                                label = { Text(option.name) },
                            )
                        }
                    }

                    QrBox(state)

                    Text(
                        statusText(state),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )

                    if (state is LoginUiState.Failed) {
                        Button(onClick = { viewModel.retry() }) {
                            Text("重新生成二维码")
                        }
                    }

                    Text(
                        if (addAccount) {
                            "新扫码账户将加入本机账户列表，其他账户不会丢失。"
                        } else {
                            "二维码由米哈游通行证签发，确认后自动按当前所选服务器完成凭据交换。" +
                                "凭据仅保存在本机加密区，不会上传至任何服务器。"
                        },
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
                contentDescription = "米游社扫码登录二维码",
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
                            contentDescription = "已扫描",
                            tint = Color.White,
                        )
                        Text(
                            "已扫描，请在手机确认",
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

private fun statusText(state: LoginUiState): String = when (state) {
    is LoginUiState.Checking -> "正在生成二维码…"
    is LoginUiState.Qr -> when (state.phase) {
        QrPhase.Waiting -> "请用米游社或云·原神扫码并确认登录"
        QrPhase.Scanned -> "已扫描，请在手机上确认登录…"
        QrPhase.Refreshing -> "二维码已失效，正在换新码…"
    }
    is LoginUiState.Finalizing -> "登录已确认，正在完成凭据交换…"
    is LoginUiState.Failed -> "生成失败：${state.message}"
    is LoginUiState.LoggedIn -> "登录成功"
}
