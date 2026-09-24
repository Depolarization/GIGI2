package com.gigi.tcg.ui.navigation

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gigi.tcg.ui.screens.cardstats.CardStatsScreen
import com.gigi.tcg.ui.screens.cardwiki.CardWikiScreen
import com.gigi.tcg.ui.screens.home.HomeScreen
import com.gigi.tcg.ui.screens.rank.RankScreen

private data class GigiDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val destinations = listOf(
    GigiDestination("home", "首页", Icons.Filled.Home),
    GigiDestination("rank", "排行榜", Icons.Filled.EmojiEvents),
    GigiDestination("cardstats", "卡牌详情", Icons.Filled.FilterList),
    GigiDestination("cardwiki", "卡面下载", Icons.Filled.Download),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GigiNavHost(navController: NavHostController = rememberNavController()) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("GIGI") }) },
    ) { innerPadding ->
        BoxWithConstraints(modifier = Modifier.padding(innerPadding)) {
            val useRail = maxWidth >= 840.dp
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = backStackEntry?.destination

            if (useRail) {
                Row(modifier = Modifier.fillMaxSize()) {
                    NavigationRail {
                        destinations.forEach { dest ->
                            NavigationRailItem(
                                selected = currentDestination?.hierarchy
                                    ?.any { it.route == dest.route } == true,
                                onClick = { navController.navigateTo(dest.route) },
                                icon = { Icon(dest.icon, contentDescription = dest.label) },
                                label = { Text(dest.label) },
                            )
                        }
                    }
                    NavHost(
                        navController = navController,
                        startDestination = "home",
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        gigiScreens()
                    }
                }
            } else {
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            destinations.forEach { dest ->
                                NavigationBarItem(
                                    selected = currentDestination?.hierarchy
                                        ?.any { it.route == dest.route } == true,
                                    onClick = { navController.navigateTo(dest.route) },
                                    icon = { Icon(dest.icon, contentDescription = dest.label) },
                                    label = { Text(dest.label) },
                                )
                            }
                        }
                    },
                ) { bottomPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "home",
                        modifier = Modifier.padding(bottomPadding),
                    ) {
                        gigiScreens()
                    }
                }
            }
        }
    }
}

private fun NavHostController.navigateTo(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun NavGraphBuilder.gigiScreens() {
    composable("home") { HomeScreen() }
    composable("rank") { RankScreen() }
    composable("cardstats") { CardStatsScreen() }
    composable("cardwiki") { CardWikiScreen() }
}

