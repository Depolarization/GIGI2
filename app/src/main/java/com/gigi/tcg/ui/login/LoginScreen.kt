// 登录/添加账户页（设计 §4.5：全屏 Scaffold，非弹窗）—— V27-E 按 M3 重构：
// CenterAlignedTopAppBar（标题按分支：首登「扫码登录」/ 添加账户「添加账户」，
// navigationIcon 返回箭头仅添加账户分支）+ 可滚动单列，区块间距走 M3 阶梯 24dp、
// 卡内 16dp、按钮与说明文字 8dp。落盘编排（PNG、Pictures/GIGI/二维码/、换码后自动删图）
// 收敛在 LoginViewModel.saveQrToAlbum，页面只做展示与 Snackbar 反馈。
// V28-D：① 页底两段说明文本（首登凭据去向 / 添加账户多账户共存）撤出本页——与「关于」
// 对话框重复，正文归 about 段；② 服务器二选一从 FilterChip 换成撑满的 SegmentedButton
// （与保存卡面弹窗的普通/动态切换同款控件，chip 居中悬浮不像一个「开关」）；
// ③ 二维码卡内只放码本身（换码按钮移出卡外），保证码相对卡片四边等距、几何居中。
// V29-B：④ 添加账户页撤掉正文的品牌标题行（顶栏已经是「添加账户」，正文再挂一行是同一句
// 话讲两遍）；⑤ 「保存」「重新生成」改为同款 OutlinedButton 等宽横向并列，失败态不再另起
// 主按钮——两处都是 viewModel.retry，按状态换形态只是让用户重新找按钮；
// ⑥ 按钮下方原先两段灰字（「请扫码并确认登录」+「成功后自动删图」）合并为一句，并入
// login_status_waiting、删 login_qr_autodelete_hint；
// ⑦ 切换服务器不再重新出码（换码只由码自身失效驱动，见 LoginViewModel.selectServer）。

package com.gigi.tcg.ui.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
            // ① 品牌标题——仅首登页。添加账户页不显示：顶栏已经是「添加账户」，
            // 正文再挂一行「GIGI · 添加账户」是同一句话讲两遍，且会把二维码往下顶
            // （用户 V29-B 明确要求撤掉）。首登页顶栏是「扫码登录」，这行 GIGI 品牌名
            // 是本页唯一的工具标识，保留。
            if (!addAccount) {
                Text(
                    stringResource(R.string.login_title),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
            }

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

            // ③ 服务器二选一（只切 finalize 的目标服务器，**不换码** —— 见 LoginViewModel.selectServer）。
            //    用 SegmentedButton 而非 chip：整行撑满、两段等宽拼接，视觉上就是一个「二选一」开关；
            //    chip 悬浮居中不像互斥选择。
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

            // ④ 二维码卡：卡内只放码（图 / 生成中占位 / 已扫描蒙层）。
            //    卡内套一层 padding(16dp) + aspectRatio(1f) 的方形 Box，码 fillMaxSize 贴满方形内容区：
            //    码是正方形位图，Fit 缩放后正好等于内容区，四边留白恒等于 16dp、几何居中。
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

            // ⑤ 操作组：两个同款 OutlinedButton 横向等宽并列 + 其下一段说明文字，
            //    以 8dp 紧贴成一体跟随二维码（V28-D 的「保存是唯一主动作」层级在此让位于
            //    「保存 / 重新生成」平权：两者都是对同一张码的操作，分出主次反而要用户猜）。
            //    二维码未就绪（生成中/收尾）两个都禁用；保存中转小进度环。
            val qr = state as? LoginUiState.Qr
            val qrReady = state is LoginUiState.Qr
            // 换码入口的有效时机：等待扫码中（作废当前码换一张）/ 生成失败（换码是唯一出路）。
            // Scanned 态禁掉：手机已经拿着这张码，此刻换码等于把用户手上扫了一半的码作废。
            val canRegenerate = !saving && (qr?.phase == QrPhase.Waiting || state is LoginUiState.Failed)
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
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
                        modifier = Modifier.weight(1f),
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
                    OutlinedButton(
                        enabled = canRegenerate,
                        onClick = { viewModel.retry() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.action_regenerate))
                    }
                }

                // 唯一一段说明文字（V29-B：原先这里是「扫码并确认登录」+「成功后自动删图」
                // 两段灰字，语义相邻却各说各话 ⇒ 合并进 login_status_waiting 一句讲完，
                // 三语文案见 strings）。Failed 态透出失败原因，走 error 色。
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
            }
            // 原 ⑥ 独立的失败态主按钮已并入 ⑤ 的「重新生成」（同一动作 viewModel.retry，
            // 无需按状态换样式）；原 ⑦「底部说明」（首登凭据去向 / 添加账户多账户共存）
            // V28-D 起撤出本页，归 about 段（数据来源 / 使用要点）。
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
