// 主界面导航骨架（设计 §4.1/§4.2/§4.5）：Scaffold + TopAppBar + NavigationBar（宽屏 ≥840dp 换 NavigationRail）。
// 顶栏 actions：玩家查询 / 服务器切换（Menu 单选）/ 关于 / 退出登录（LocalLogout）。
// 全局弹窗（PlayerQuery/PlayerDetail/CardCover/About）与 ToastController 统一挂载在本层，四页面共享。
// 服务器切换：key(server) 整体重建导航图（含各页 ViewModel），对齐 web App.tsx key={server.id} 重挂载语义，
// 旧服务器数据态不带入新服务器；startRoute 记住当前 tab，重建后停留原页。

package com.gigi.tcg.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gigi.tcg.GigiApp
import com.gigi.tcg.ui.components.LocalToast
import com.gigi.tcg.ui.components.ToastController
import com.gigi.tcg.ui.components.ToastHost
import com.gigi.tcg.ui.dialogs.about.AboutDialog
import com.gigi.tcg.ui.dialogs.cardcover.CardCoverSheet
import com.gigi.tcg.ui.dialogs.playerdetail.PlayerDetailDialog
import com.gigi.tcg.ui.dialogs.playerdetail.PlayerQueryDialog
import com.gigi.tcg.ui.login.LocalAccountActions
import com.gigi.tcg.ui.screens.cardstats.CardStatsRoute
import com.gigi.tcg.ui.screens.cardwiki.CardWikiRoute
import com.gigi.tcg.ui.screens.home.HomeRoute
import com.gigi.tcg.ui.screens.rank.RankRoute

private const val ROUTE_HOME = "home"
private const val ROUTE_RANK = "rank"
private const val ROUTE_CARD_STATS = "cardstats"
private const val ROUTE_CARD_WIKI = "cardwiki"

private data class GigiDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val destinations = listOf(
    GigiDestination(ROUTE_HOME, "首页", Icons.Outlined.Home),
    GigiDestination(ROUTE_RANK, "排行榜", Icons.Outlined.EmojiEvents),
    GigiDestination(ROUTE_CARD_STATS, "卡牌统计", Icons.Outlined.BarChart),
    GigiDestination(ROUTE_CARD_WIKI, "卡牌图鉴", Icons.Outlined.Style),
)

private val RAIL_BREAKPOINT = 840.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GigiNavHost() {
    val container = (LocalContext.current.applicationContext as GigiApp).container
    val server by container.currentServer.collectAsStateWithLifecycle()
    val sessionUid by container.sessionUid.collectAsStateWithLifecycle()
    val accounts by container.accounts.collectAsStateWithLifecycle()
    val activeUid by container.activeAccountUid.collectAsStateWithLifecycle()
    val actions = LocalAccountActions.current
    val activeAccount = accounts.firstOrNull { it.uid == activeUid }

    var queryOpen by remember { mutableStateOf(false) }
    var detailUid by remember { mutableStateOf<String?>(null) }
    var coverId by remember { mutableStateOf<Long?>(null) }
    var aboutOpen by remember { mutableStateOf(false) }
    var accountMenuOpen by remember { mutableStateOf(false) }
    var logoutConfirmOpen by remember { mutableStateOf(false) }
    val toastController = remember { ToastController() }
    var startRoute by remember { mutableStateOf(ROUTE_HOME) }
    var announcedServer by remember { mutableStateOf(server) }

    LaunchedEffect(sessionUid) {
        queryOpen = false
        detailUid = null
        coverId = null
        aboutOpen = false
        accountMenuOpen = false
        logoutConfirmOpen = false
    }

    LaunchedEffect(server) {
        if (server != announcedServer) {
            announcedServer = server
            toastController.show("已切换到${server.name}，正在按该服务器重新拉取数据")
        }
    }

    CompositionLocalProvider(LocalToast provides { toastController.show(it) }) {
        key(server to sessionUid) {
            val navController = rememberNavController()
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination?.route

            BoxWithConstraints {
                val useRail = maxWidth >= RAIL_BREAKPOINT

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(destinations.firstOrNull { it.route == currentRoute }?.label ?: "GIGI")
                            },
                            actions = {
                                IconButton(onClick = { queryOpen = true }) {
                                    Icon(Icons.Outlined.PersonSearch, contentDescription = "玩家查询")
                                }
                                Box {
                                    TextButton(onClick = { accountMenuOpen = true }) {
                                        Text(activeAccount?.displayName() ?: "账户")
                                    }
                                    DropdownMenu(
                                        expanded = accountMenuOpen,
                                        onDismissRequest = { accountMenuOpen = false },
                                    ) {
                                        accounts.forEach { account ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        "${account.displayName()}（${account.server().shortName}）",
                                                    )
                                                },
                                                leadingIcon = {
                                                    if (account.uid == activeUid) {
                                                        Icon(Icons.Outlined.Check, contentDescription = null)
                                                    }
                                                },
                                                onClick = {
                                                    accountMenuOpen = false
                                                    if (account.uid != activeUid) {
                                                        startRoute = currentRoute ?: ROUTE_HOME
                                                        actions.switchAccount(account.uid)
                                                    }
                                                },
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("添加账户") },
                                            leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                                            onClick = {
                                                accountMenuOpen = false
                                                actions.addAccount()
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("退出当前账户") },
                                            leadingIcon = {
                                                Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null)
                                            },
                                            onClick = {
                                                accountMenuOpen = false
                                                logoutConfirmOpen = true
                                            },
                                        )
                                    }
                                }
                                IconButton(onClick = { aboutOpen = true }) {
                                    Icon(Icons.Outlined.Info, contentDescription = "关于")
                                }
                            },
                        )
                    },
                    bottomBar = {
                        if (!useRail) {
                            NavigationBar {
                                destinations.forEach { dest ->
                                    NavigationBarItem(
                                        selected = currentRoute == dest.route,
                                        onClick = { navController.navigateToTab(dest.route) },
                                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                                        label = { Text(dest.label) },
                                    )
                                }
                            }
                        }
                    },
                    snackbarHost = { ToastHost(toastController) },
                ) { innerPadding ->
                    Row(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                        if (useRail) {
                            NavigationRail {
                                destinations.forEach { dest ->
                                    NavigationRailItem(
                                        selected = currentRoute == dest.route,
                                        onClick = { navController.navigateToTab(dest.route) },
                                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                                        label = { Text(dest.label) },
                                    )
                                }
                            }
                        }
                        NavHost(
                            navController = navController,
                            startDestination = startRoute,
                            modifier = Modifier.fillMaxSize().weight(1f),
                        ) {
                            composable(ROUTE_HOME) {
                                HomeRoute(
                                    container = container,
                                    onOpenPlayerDetail = { detailUid = it },
                                )
                            }
                            composable(ROUTE_RANK) {
                                RankRoute(
                                    container = container,
                                    onOpenPlayerDetail = { detailUid = it },
                                )
                            }
                            composable(ROUTE_CARD_STATS) {
                                CardStatsRoute(
                                    container = container,
                                    onShowToast = { toastController.show(it) },
                                )
                            }
                            composable(ROUTE_CARD_WIKI) {
                                CardWikiRoute(
                                    container = container,
                                    onOpenCover = { coverId = it },
                                    onShowToast = { toastController.show(it) },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (queryOpen) {
            PlayerQueryDialog(
                onSubmit = { uid ->
                    queryOpen = false
                    detailUid = uid
                },
                onClose = { queryOpen = false },
            )
        }
        PlayerDetailDialog(uid = detailUid, onClose = { detailUid = null })
        CardCoverSheet(contentId = coverId?.toInt(), onDismiss = { coverId = null })
        if (aboutOpen) {
            AboutDialog(onClose = { aboutOpen = false })
        }
        if (logoutConfirmOpen) {
            AlertDialog(
                onDismissRequest = { logoutConfirmOpen = false },
                title = { Text("退出当前账户？") },
                text = { Text("退出后将删除本机保存的当前账户凭据，其他账户不受影响。") },
                dismissButton = {
                    TextButton(onClick = { logoutConfirmOpen = false }) { Text("取消") }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            logoutConfirmOpen = false
                            actions.logout()
                        },
                    ) { Text("退出") }
                },
            )
        }
    }
}

// §4.2 状态保持：切 tab 保存/恢复各自 back stack，页面筛选与滚动状态不丢
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
