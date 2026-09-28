// 主界面导航骨架（设计 §4.1/§4.2/§4.5）：Scaffold + TopAppBar + NavigationBar（宽屏 ≥840dp 换 NavigationRail）。
// 顶栏 actions：玩家查询 / 服务器切换（Menu 单选）/ 关于 / 退出登录（LocalLogout）。
// 全局弹窗（PlayerQuery/PlayerDetail/CardCover/About）与 ToastController 统一挂载在本层，四页面共享。
// 服务器切换：key(server) 整体重建导航图（含各页 ViewModel），对齐 web App.tsx key={server.id} 重挂载语义，
// 旧服务器数据态不带入新服务器；startRoute 记住当前 tab，重建后停留原页。

package com.gigi.tcg.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gigi.tcg.GigiApp
import com.gigi.tcg.R
import com.gigi.tcg.i18n.LocaleStrings
import com.gigi.tcg.i18n.displayNameSync
import com.gigi.tcg.ui.components.LocalToast
import com.gigi.tcg.ui.components.ToastController
import com.gigi.tcg.ui.components.ToastHost
import com.gigi.tcg.ui.components.openInGallery
import com.gigi.tcg.ui.dialogs.about.AboutDialog
import com.gigi.tcg.ui.dialogs.cardcover.CardCoverSheet
import com.gigi.tcg.ui.dialogs.playerdetail.PlayerDetailDialog
import com.gigi.tcg.ui.dialogs.playerdetail.PlayerDetailTarget
import com.gigi.tcg.ui.dialogs.playerdetail.PlayerQueryDialog
import com.gigi.tcg.ui.screens.cardstats.CardStatsRoute
import com.gigi.tcg.ui.screens.cardwiki.CardWikiRoute
import com.gigi.tcg.ui.screens.home.HomeRoute
import com.gigi.tcg.ui.screens.my.MyCardBacksPage
import com.gigi.tcg.ui.screens.my.MyChallengePage
import com.gigi.tcg.ui.screens.my.MyDecksPage
import com.gigi.tcg.ui.screens.my.MyFavoritesPage
import com.gigi.tcg.ui.screens.my.MyRoute
import com.gigi.tcg.ui.screens.rank.RankRoute

private const val ROUTE_HOME = "home"
private const val ROUTE_RANK = "rank"
private const val ROUTE_CARD_STATS = "cardstats"
private const val ROUTE_CARD_WIKI = "cardwiki"
private const val ROUTE_MY = "my"

// 「我的」页的四个二级页（P2 真实数据页，设计 §3.3）：挂在 my/ 下 ⇒
// 底部导航/Rail 的选中态与顶栏标题按一级段（substringBefore('/')）归属到「我的」。
private const val ROUTE_MY_DECK = "my/deck"
private const val ROUTE_MY_CARDBACK = "my/cardback"
private const val ROUTE_MY_FAVORITES = "my/favorites"
private const val ROUTE_MY_CHALLENGE = "my/challenge"

private data class GigiDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

private val destinations = listOf(
    GigiDestination(ROUTE_HOME, R.string.nav_home, Icons.Outlined.Home),
    GigiDestination(ROUTE_RANK, R.string.nav_rank, Icons.Outlined.EmojiEvents),
    GigiDestination(ROUTE_CARD_STATS, R.string.nav_card_stats, Icons.Outlined.BarChart),
    GigiDestination(ROUTE_CARD_WIKI, R.string.nav_card_wiki, Icons.Outlined.Style),
    GigiDestination(ROUTE_MY, R.string.nav_my, Icons.Outlined.Person),
)

private val RAIL_BREAKPOINT = 840.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GigiNavHost() {
    val container = (LocalContext.current.applicationContext as GigiApp).container
    // 组合期取一次，供「查看相册」等非组合回调使用：
    // LocalContext.current 是 @Composable 读取器，不能在协程/普通 lambda 里调。
    val appContext = LocalContext.current.applicationContext
    val server by container.currentServer.collectAsStateWithLifecycle()
    val sessionUid by container.sessionUid.collectAsStateWithLifecycle()

    var queryOpen by remember { mutableStateOf(false) }
    // 详情目标带一份「入口头像」：列表页（排行榜 / 对局）点进来时能给出头像，
    // 详情接口无权访问（is_shield）时不至于只剩占位（V26）。无入口头像的入口传 null。
    var detailTarget by remember { mutableStateOf<PlayerDetailTarget?>(null) }
    var coverId by remember { mutableStateOf<Long?>(null) }
    var aboutOpen by remember { mutableStateOf(false) }
    val toastController = remember { ToastController() }
    var startRoute by remember { mutableStateOf(ROUTE_HOME) }
    var announcedServer by remember { mutableStateOf(server) }

    LaunchedEffect(sessionUid) {
        queryOpen = false
        detailTarget = null
        coverId = null
        aboutOpen = false
    }

    LaunchedEffect(server) {
        if (server != announcedServer) {
            announcedServer = server
            toastController.show(LocaleStrings.get(R.string.toast_server_switched, server.displayNameSync()))
        }
    }

    CompositionLocalProvider(LocalToast provides { toastController.show(it) }) {
        key(server to sessionUid) {
            val navController = rememberNavController()
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination?.route
            // 一级段：二级页（my/deck 等）的选中态与顶栏标题归属到所属 tab
            val baseRoute = currentRoute?.substringBefore('/')

            // 持续记录当前 tab：账户切换 / 服务器切换触发下方 key() 重建后，startDestination
            // 用最近记录的一级路由 ⇒ 停留在原页（V35 前该记录由顶栏账户菜单写入，菜单迁入
            // 「我的」页后改由导航变化驱动，语义不变）。
            LaunchedEffect(baseRoute) {
                if (baseRoute != null && destinations.any { it.route == baseRoute }) {
                    startRoute = baseRoute
                }
            }

            BoxWithConstraints {
                val useRail = maxWidth >= RAIL_BREAKPOINT

                Scaffold(
                    topBar = {
                        // V35：顶栏不再展示个人 ID（账户按钮与菜单已迁入「我的」页 —— 用户拍板：
                        // 有了「我的」页后底部导航一步可达，顶栏再挂账户名是冗余；
                        // 账户管理（列表/切换/添加/登出）见 MyRoute 分区①）。
                        TopAppBar(
                            title = {
                                Text(destinations.firstOrNull { it.route == baseRoute }?.let { stringResource(it.labelRes) } ?: "GIGI")
                            },
                            actions = {
                                IconButton(onClick = { queryOpen = true }) {
                                    Icon(Icons.Outlined.PersonSearch, contentDescription = stringResource(R.string.query_title))
                                }
                                IconButton(onClick = { aboutOpen = true }) {
                                    Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.cd_about))
                                }
                            },
                        )
                    },
                    bottomBar = {
                        if (!useRail) {
                            NavigationBar {
                                destinations.forEach { dest ->
                                    NavigationBarItem(
                                        selected = baseRoute == dest.route,
                                        onClick = { navController.navigateToTab(dest.route) },
                                        icon = { Icon(dest.icon, contentDescription = stringResource(dest.labelRes)) },
                                        label = {
                                            Text(
                                                stringResource(dest.labelRes),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        alwaysShowLabel = true,
                                        modifier = Modifier.semantics {
                                            role = Role.Tab
                                            this.selected = baseRoute == dest.route
                                        },
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
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 12.dp, bottom = 12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Style,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                destinations.forEach { dest ->
                                    NavigationRailItem(
                                        selected = baseRoute == dest.route,
                                        onClick = { navController.navigateToTab(dest.route) },
                                        icon = { Icon(dest.icon, contentDescription = stringResource(dest.labelRes)) },
                                        label = {
                                            Text(
                                                stringResource(dest.labelRes),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        modifier = Modifier.semantics {
                                            role = Role.Tab
                                            this.selected = baseRoute == dest.route
                                        },
                                    )
                                }
                            }
                            HorizontalDivider(
                                modifier = Modifier.fillMaxHeight(),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                        NavHost(
                            navController = navController,
                            startDestination = startRoute,
                            modifier = Modifier.fillMaxSize().weight(1f),
                        ) {
                            composable(ROUTE_HOME) {
                                HomeRoute(
                                    container = container,
                                    onOpenPlayerDetail = { uid, avatarUrl ->
                                        detailTarget = PlayerDetailTarget(uid, avatarUrl)
                                    },
                                )
                            }
                            composable(ROUTE_RANK) {
                                RankRoute(
                                    container = container,
                                    onOpenPlayerDetail = { uid, avatarUrl ->
                                        detailTarget = PlayerDetailTarget(uid, avatarUrl)
                                    },
                                )
                            }
                            composable(ROUTE_CARD_STATS) {
                                CardStatsRoute(
                                    container = container,
                                    onShowToast = { toastController.show(it) },
                                    // 导出结果带「查看」action：点开系统相册定位刚落盘的那张图。
                                    // uris 为空（全失败 / API 24-28 拿不到 MediaStore uri）时
                                    // 不挂 action，只出纯文本提示。
                                    onShowExportResult = { message, uris ->
                                        val first = uris.firstOrNull()
                                        if (first == null) {
                                            toastController.show(message)
                                        } else {
                                            toastController.showWithAction(
                                                message,
                                                LocaleStrings.get(R.string.action_view),
                                            ) { openInGallery(appContext, first) }
                                        }
                                    },
                                )
                            }
                            composable(ROUTE_CARD_WIKI) {
                                CardWikiRoute(
                                    container = container,
                                    onOpenCover = { coverId = it },
                                    onShowToast = { toastController.show(it) },
                                )
                            }
                            // 「我的」页：四分区 + 账号管理；二级页为真实数据页（P2），列表→详情在页内切换。
                            // 二级页直接 navigate（不经 navigateToTab）：不进 tab 的 saveState 体系，
                            // 返回键逐级回退；底部导航选中态靠 baseRoute 保持「我的」高亮。
                            composable(ROUTE_MY) {
                                MyRoute(
                                    onOpenDeck = { navController.navigate(ROUTE_MY_DECK) },
                                    onOpenCardBack = { navController.navigate(ROUTE_MY_CARDBACK) },
                                    onOpenFavorites = { navController.navigate(ROUTE_MY_FAVORITES) },
                                    onOpenChallenge = { navController.navigate(ROUTE_MY_CHALLENGE) },
                                )
                            }
                            composable(ROUTE_MY_DECK) { MyDecksPage() }
                            composable(ROUTE_MY_CARDBACK) { MyCardBacksPage() }
                            composable(ROUTE_MY_FAVORITES) { MyFavoritesPage() }
                            composable(ROUTE_MY_CHALLENGE) { MyChallengePage() }
                        }
                    }
                }
            }
        }

        if (queryOpen) {
            PlayerQueryDialog(
                onSubmit = { uid ->
                    queryOpen = false
                    // 手输 UID：没有列表入口可给头像，兜底传 null（沿用占位）
                    detailTarget = PlayerDetailTarget(uid)
                },
                onClose = { queryOpen = false },
            )
        }
        PlayerDetailDialog(target = detailTarget, onClose = { detailTarget = null })
        CardCoverSheet(contentId = coverId?.toInt(), onDismiss = { coverId = null })
        if (aboutOpen) {
            AboutDialog(onClose = { aboutOpen = false })
        }
        // 登出确认对话框已随账户管理迁入「我的」页（MyRoute），本层不再持有相关状态。
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
