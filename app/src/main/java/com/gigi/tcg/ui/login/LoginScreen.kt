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
// V33（2026-09-28）：⑧ 拆除服务器二选一控件（SegmentedButton）—— 二维码与服务器无关，
// 服务器归属的正确决策时机在扫码确认之后（LoginViewModel.finalize → 唯一角色自动登录 /
// 多角色 ChooseRole 弹选择）。原控件的信息功能（"支持这两台服"）由二维码下方的
// login_servers_supported 一行说明承接；⑨ 新增 RoleChooserDialog（多角色账号的选择器）。
// V34（2026-09-28）：⑩ RoleChooserDialog 由单选改**多选**（样式对齐统计页导出对话框的
// ExportOptionRow：整行可点 + Checkbox）：默认全选、确认按钮在至少勾一项时可用，
// 勾中的角色逐个登录并保存为多账户（提交顺序 = 候选列表顺序，见 selectedRolesInOrder）；
// 部分失败时对话框带 notice 重现（只列失败项），用户无需重新扫码即可补登。

package com.gigi.tcg.ui.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gigi.tcg.R
import com.gigi.tcg.data.auth.AuthFinalizeResult
import com.gigi.tcg.i18n.LocaleStrings
import com.gigi.tcg.i18n.serverDisplayNameSync
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
        snackbarHost = {
            // V37-H：与 GigiToast.kt ToastHost 同构，显式传四色槽，不吃 M3 默认回落。
            // M3 1.3.2 深色基线 inverseSurface=#E6E0E9（浅），若走默认则浅底浅字≈1.0:1 夜间不可读。
            val colorScheme = MaterialTheme.colorScheme
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = colorScheme.inverseSurface,
                    contentColor = colorScheme.inverseOnSurface,
                    actionContentColor = colorScheme.inversePrimary,
                    dismissActionContentColor = colorScheme.inverseOnSurface,
                )
            }
        },
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

            // ③ 二维码卡：卡内只放码（图 / 生成中占位 / 已扫描蒙层）。
            //    卡内套一层 padding(16dp) + aspectRatio(1f) 的方形 Box，码 fillMaxSize 贴满方形内容区：
            //    码是正方形位图，Fit 缩放后正好等于内容区，四边留白恒等于 16dp、几何居中。
            //    （V33：此位置原有的服务器二选一控件已拆除，服务器归属由扫码后的角色选择承担。）
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

            // ④ 操作组：两个同款 OutlinedButton 横向等宽并列 + 其下说明文字，
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

                // 支持范围说明（V33）：承接原「服务器二选一」控件拆除后的信息功能 ——
                // 用户扫码前就能确认"我这个渠道服/官服账号能不能用"，不再靠一个切换开关暗示。
                Text(
                    stringResource(R.string.login_servers_supported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            // 原 ⑤ 独立的失败态主按钮已并入 ④ 的「重新生成」（同一动作 viewModel.retry，
            // 无需按状态换样式）；原「底部说明」（首登凭据去向 / 添加账户多账户共存）
            // V28-D 起撤出本页，归 about 段（数据来源 / 使用要点）。
        }
    }
    // ⑨ 角色选择对话框（V33，V34 改多选）：多角色账号（典型：官服+渠道服各一）时覆盖本页展示候选。
    //    码已确认失效、凭据未写入，用户勾选（默认全选）后逐个登录并保存；「重新扫码」给换账号出口。
    //    部分失败时本态带着 notice 重现（candidates 只列失败项），用户可直接补登失败角色。
    (state as? LoginUiState.ChooseRole)?.let { chooseRole ->
        RoleChooserDialog(
            candidates = chooseRole.candidates,
            notice = chooseRole.notice,
            onConfirm = { viewModel.chooseRoles(it) },
            onRescan = { viewModel.begin() },
        )
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
    is LoginUiState.ChooseRole -> stringResource(R.string.login_status_choose_role)
    is LoginUiState.Failed -> stringResource(R.string.login_status_failed, state.message)
    is LoginUiState.LoggedIn -> stringResource(R.string.login_status_success)
}

/**
 * 角色选择对话框（V33 引入，V34 改多选）：该米游社账号绑定了多个可登录角色时展示候选
 * （典型：官服+渠道服各一）。勾选（**默认全选**，与统计页导出多选同一产品决策：主用途是
 * "都要"）后点「登录并保存」→ [LoginViewModel.chooseRoles] 携选中区服逐个完成凭据交换并
 * 落盘为多账户；「重新扫码」→ [LoginViewModel.begin] 换一张码重来（想换账号的场景）。
 *
 * [notice]：部分成功时由 VM 带回（"已保存 N 个账户，M 个失败：原因"，候选此时只列失败项），
 * 帮用户在框内直接补登，不必重新扫码。
 *
 * 🔴 不允许点外部/返回键关闭：此刻凭据已确认但未落盘，必须显式决断（登录 / 重新扫码）
 * 才能离开，静默关闭会让用户以为登录卡死。
 */
@Composable
private fun RoleChooserDialog(
    candidates: List<AuthFinalizeResult.BoundRole>,
    notice: String?,
    onConfirm: (List<AuthFinalizeResult.BoundRole>) -> Unit,
    onRescan: () -> Unit,
) {
    // 默认全选；勾选态以候选为 key —— 部分失败重现对话框（候选变为失败项）时重置为全选，
    // 用户直接确认即可重试全部失败项。
    var selected by remember(candidates) { mutableStateOf(initialRoleSelection(candidates)) }
    AlertDialog(
        onDismissRequest = { /* 不提供隐式关闭：显式登录或重新扫码 */ },
        title = { Text(stringResource(R.string.login_choose_role_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                notice?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                candidates.forEach { role ->
                    RoleOptionRow(
                        role = role,
                        checked = role.uid in selected,
                        onCheckedChange = { now ->
                            selected = if (now) selected + role.uid else selected - role.uid
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = { onConfirm(selectedRolesInOrder(candidates, selected)) },
            ) {
                Text(stringResource(R.string.login_choose_role_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onRescan) {
                Text(stringResource(R.string.login_choose_role_rescan))
            }
        },
    )
}

/**
 * 多选项行（V34，样式对齐统计页导出对话框的 ExportOptionRow）：整行可点 + 左 Checkbox、
 * 右两行文案（昵称 + [roleSubtitle]）。Checkbox 自身与整行点击都切换勾选（M3 惯用手势），
 * 不冲突——Checkbox 消费自身点击，行只响应其余区域。
 */
@Composable
private fun RoleOptionRow(
    role: AuthFinalizeResult.BoundRole,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                role.nickname?.takeIf { it.isNotEmpty() } ?: role.uid,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                roleSubtitle(role),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 角色行副标题：「服名 · Lv.N · UID xxxxx」—— 展示数据可直接判别的身份要素，
 * 让用户一眼确认要点哪个号（跟原本"先选服再扫"相比，这里给的是**真实存在的角色**）。
 * 服名优先服务端 region_name，缺失回落本地映射；Lv 缺失则省略该段（"Lv."/UID 为游戏通用写法，不入 i18n）。
 */
internal fun roleSubtitle(role: AuthFinalizeResult.BoundRole): String = listOfNotNull(
    role.regionName?.takeIf { it.isNotEmpty() } ?: serverDisplayNameSync(role.region),
    role.level?.let { "Lv.$it" },
    "UID ${role.uid}",
).joinToString(" · ")

/**
 * 角色选择初始集合（V34 多选）：**默认全选** —— 与统计页导出多选同一产品决策，
 * 主用途是"两个角色都要"（纯函数，JVM 单测钉死）。
 */
internal fun initialRoleSelection(candidates: List<AuthFinalizeResult.BoundRole>): Set<String> =
    candidates.mapTo(LinkedHashSet()) { it.uid }

/**
 * 取勾选中的角色，**按候选列表顺序**输出（= 服务端绑定列表顺序，官服通常在前）——
 * 提交顺序与勾选先后无关，保证 VM 侧"激活账户 = 第一个成功角色"的语义稳定可预期。
 * 纯函数，JVM 单测钉死。
 */
internal fun selectedRolesInOrder(
    candidates: List<AuthFinalizeResult.BoundRole>,
    selected: Set<String>,
): List<AuthFinalizeResult.BoundRole> = candidates.filter { it.uid in selected }
