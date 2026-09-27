// 登录/添加账户页（设计 §4.5：全屏 Scaffold，非弹窗）—— V27-E 按 M3 重构：
// CenterAlignedTopAppBar（标题按分支：首登「扫码登录」/ 添加账户「添加账户」，
// navigationIcon 返回箭头仅添加账户分支）+ 可滚动单列，区块间距走 M3 阶梯 24dp、
// 卡内 12/16dp、按钮与说明文字 8dp；「重新生成二维码」为二维码卡下方次要 TextButton
// （失败态才升级为主按钮重试），「保存」是唯一主动作按钮（保存中转进度），
// 其下紧贴自动删除灰色小字说明。落盘编排（PNG、Pictures/GIGI/二维码/、换码后自动删图）
// 收敛在 LoginViewModel.saveQrToAlbum，页面只做展示与 Snackbar 反馈。
// V28-D：① 页底两段说明文本（首登凭据去向 / 添加账户多账户共存）撤出本页——与「关于」
// 对话框重复，正文归 about 段；② 服务器二选一从 FilterChip 换成撑满的 SegmentedButton
// （与保存卡面弹窗的普通/动态切换同款控件，chip 居中悬浮不像一个「开关」）；
// ③ 二维码卡内只放码本身（换码按钮移出卡外），保证码相对卡片四边等距、几何居中。

package com.gigi.tcg.ui.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gigi.tcg.R
import com.gigi.tcg.data.ServerId
import com.gigi.tcg.i18n.LocaleStrings
import com.gigi.tcg.i18n.displayName
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
    val saving by viewModel.qrSaving.collectAsStateWithLifecycle()

    // 保存二维码的反馈走本页 Scaffold 的 Snackbar：本页挂在 AppGate 状态机上，
    // 拿不到 NavHost 内 provide 的 LocalToast（首登时 NavHost 根本不在组合里）。
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 添加账户页存在上一级（当前账户主界面）：系统返回键等价顶栏返回；
    // 首登页无上一级，不接管，保持系统默认（退出应用）。
    BackHandler(enabled = addAccount && onCancelAddAccount != null) {
        onCancelAddAccount?.invoke()
    }

    Scaffold(
        topBar = {
            // 两种入口共用本页，标题按分支取文案：首登无上一级（无返回键），
            // 添加账户是 Main 上的叠加层。M3 规范：返回走 navigationIcon 的 IconButton + 箭头，
            // 配色/高度用 TopAppBar 默认值，与 GigiNavHost 主顶栏同源。
            CenterAlignedTopAppBar(
                title = {
                    Text(stringResource(if (addAccount) R.string.account_add else R.string.login_qr_title))
                },
                navigationIcon = {
                    if (addAccount) {
                        IconButton(onClick = { onCancelAddAccount?.invoke() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        // 单列可滚动：内容在窄屏/横屏不裁剪；区块间 24dp（M3 大区块阶梯），
        // 顶栏下方不再叠居中大标题——页面靠顶栏定锚，正文直接铺开避免「飘在页面中间」。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // ① 品牌标题（首登 = 工具名 / 添加账户分支明示动作），titleLarge 居中
            Text(
                stringResource(if (addAccount) R.string.login_title_add else R.string.login_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )

            // ② 服务器级横幅：登录失效 / NoRole 引导换服（error 色，切服前一直保留）
            if (expiredNotice) {
                Text(
                    stringResource(R.string.login_expired_notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.notice?.let { notice ->
                Text(
                    notice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ③ 服务器二选一（切服即重开二维码）。用 SegmentedButton 而非 chip：整行撑满、
            //    两段等宽拼接，视觉上就是一个「二选一」开关；chip 悬浮居中不像互斥选择。
            //    itemShape 必须带 index/count，否则两段都是全圆角、拼不起来。
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ServerId.ALL.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == server,
                        onClick = { viewModel.selectServer(option) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ServerId.ALL.size,
                        ),
                        label = { Text(option.displayName()) },
                    )
                }
            }

            // ④ 二维码区块：卡内只放码（图 / 生成中占位 / 已扫描蒙层），「重新生成」移出卡外。
            //    卡内套一层 padding(16dp) + aspectRatio(1f) 的方形 Box，码 fillMaxSize 贴满方形内容区：
            //    码是正方形位图，Fit 缩放后正好等于内容区，四边留白恒等于 16dp、几何居中。
            //    换码按钮留在卡内会把码往上挤（上下不对称），故拆成两个区块。
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Card(Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        QrBox(state)
                    }
                }

                // 「重新生成二维码」紧贴卡下的次要 TextButton：与「保存」层级分离
                // （保存才是本页唯一主动作）；失败态另有主按钮兜底。
                val qr = state as? LoginUiState.Qr
                if (qr != null && qr.phase == QrPhase.Waiting) {
                    TextButton(
                        onClick = { viewModel.retry() },
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text(stringResource(R.string.action_regenerate_qr))
                    }
                }
            }

            // ⑤ 保存组：按钮 + 说明文字以 8dp 紧贴成一体，跟随二维码而非飘散。
            //    二维码未就绪（生成中/失败/收尾）禁用；保存中转小进度环。
            val qrReady = state is LoginUiState.Qr
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    enabled = qrReady && !saving,
                    onClick = {
                        scope.launch {
                            // 协程上下文拿不了 stringResource，走 VM 同源的 LocaleStrings
                            val message = when (val outcome = viewModel.saveQrToAlbum()) {
                                is QrSaveOutcome.Success ->
                                    LocaleStrings.get(R.string.toast_saved_to_album_path, outcome.albumPath)
                                is QrSaveOutcome.Failure -> outcome.message
                            }
                            snackbarHostState.showSnackbar(message)
                        }
                    },
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.login_qr_save))
                }
                Text(
                    stringResource(R.string.login_qr_autodelete_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            // ⑥ 登录状态文案（失败原因透出为 error 色）；Failed 态换码是主出路，
            //    此时才以主按钮形态出现（与 ④ 的次要入口二选一，不会并列）。
            Text(
                statusText(state),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state is LoginUiState.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
            )
            if (state is LoginUiState.Failed) {
                Button(onClick = { viewModel.retry() }) {
                    Text(stringResource(R.string.action_regenerate_qr))
                }
            }
            // 原 ⑦「底部说明」（首登凭据去向 / 添加账户多账户共存）撤出本页：
            // 与主页右上角「关于」对话框重复，V28-D 起归 about 段（数据来源 / 使用要点）。
        }
    }
}

/** 二维码展示框：有码画码（已扫描叠蒙层徽标）；无码画占位——失败留空、其余显示 loading。
 *  尺寸由父级方形 Box 决定（fillMaxSize），本组件不再自持固定边长——四边等距由父级 padding 保证 */
@Composable
private fun QrBox(state: LoginUiState) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val qr = state as? LoginUiState.Qr
        if (qr != null) {
            Image(
                bitmap = qr.payload,
                contentDescription = stringResource(R.string.login_qr_image_desc),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            if (qr.phase == QrPhase.Scanned) {
                // M3 scrim：半透明黑遮罩 + 白色对勾徽标，明示「已扫、等手机确认」
                Box(
                    modifier = Modifier
                        .fillMaxSize()
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
                    .fillMaxSize()
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
