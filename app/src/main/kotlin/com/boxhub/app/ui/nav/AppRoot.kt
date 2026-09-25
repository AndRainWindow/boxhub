package com.boxhub.app.ui.nav

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.boxhub.app.R
import com.boxhub.app.data.session.AccountManager
import com.boxhub.app.data.session.AccountState
import com.boxhub.app.data.site.SiteRegistry
import com.boxhub.app.ui.boards.BoardsScreen
import com.boxhub.app.ui.feed.FeedScreen
import com.boxhub.app.ui.login.LoginScreen
import com.boxhub.app.ui.notices.NoticesScreen
import com.boxhub.app.ui.settings.SettingsScreen
import com.boxhub.app.ui.thread.ThreadListScreen
import com.boxhub.app.ui.thread.ThreadScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

private data class TopTab(val route: String, val labelRes: Int, val icon: ImageVector)

private val topTabs = listOf(
    TopTab("feed", R.string.tab_feed, Icons.Filled.Home),
    TopTab("boards", R.string.tab_boards, Icons.AutoMirrored.Filled.List),
    TopTab("notices", R.string.tab_notices, Icons.Filled.Email),
)

@HiltViewModel
class AppRootViewModel @Inject constructor(
    val accounts: AccountManager,
) : ViewModel()

/**
 * 根导航。
 * 顶层三 tab（feed/boards/notices）+ 覆盖式路由（threads/thread/settings/login）。
 * 根部监听账号状态：出现 Expired 站显示会话失效横幅（点击重登）。
 */
@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val vm: AppRootViewModel = hiltViewModel()
    val accountStates by vm.accounts.states.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTopTab = topTabs.any { it.route == currentRoute }

    // 已 dismiss 的过期提示（siteId 集合）
    var dismissed by remember { mutableStateOf(setOf<String>()) }
    val expiredSites = accountStates.filterValues { it is AccountState.Expired }.keys
        .filter { it !in dismissed }

    fun openThreadFromFeed(siteId: String, tid: String) {
        navController.navigate("thread/$siteId/$tid")
    }

    Column(Modifier.fillMaxSize()) {
        // 会话失效横幅（仅顶层 tab 页面显示，覆盖式页面自带顶栏不重复）
        if (expiredSites.isNotEmpty() && isTopTab) {
            val first = expiredSites.first()
            val name = SiteRegistry.byId(first)?.displayName ?: first
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.fillMaxWidth().clickable {
                        navController.navigate("login/$first")
                    }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "$name 登录已过期，点击查看",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { dismissed = expiredSites.toSet() }) {
                        Text("忽略", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

        Scaffold(
            modifier = Modifier.weight(1f),
            bottomBar = {
                if (isTopTab) {
                    NavigationBar {
                        topTabs.forEach { tab ->
                            NavigationBarItem(
                                selected = currentRoute == tab.route,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(stringResource(tab.labelRes)) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = "feed",
                modifier = Modifier.padding(innerPadding),
            ) {
                composable("feed") {
                    FeedScreen(
                        onOpenThread = ::openThreadFromFeed,
                        onOpenBoards = { navController.navigate("boards") },
                        onOpenSettings = { navController.navigate("settings") },
                    )
                }
                composable("boards") {
                    BoardsScreen(
                        onOpenBoard = { siteId, fid, name ->
                            navController.navigate("threads/$siteId/$fid/${Uri.encode(name)}")
                        },
                    )
                }
                composable("notices") { NoticesScreen() }

                composable("settings") {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenLogin = { siteId -> navController.navigate("login/$siteId") },
                    )
                }

                composable(
                    route = "login/{siteId}",
                    arguments = listOf(
                        navArgument("siteId") { type = NavType.StringType },
                    ),
                ) { entry ->
                    val siteId = entry.arguments?.getString("siteId") ?: return@composable
                    LoginScreen(
                        siteId = siteId,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(
                    route = "threads/{siteId}/{fid}/{name}",
                    arguments = listOf(
                        navArgument("siteId") { type = NavType.StringType },
                        navArgument("fid") { type = NavType.StringType },
                        navArgument("name") { type = NavType.StringType },
                    ),
                ) { entry ->
                    val siteId = entry.arguments?.getString("siteId") ?: return@composable
                    val fid = entry.arguments?.getString("fid") ?: return@composable
                    val name = Uri.decode(entry.arguments?.getString("name") ?: "")
                    ThreadListScreen(
                        siteId = siteId,
                        fid = fid,
                        boardName = name,
                        onBack = { navController.popBackStack() },
                        onOpenThread = { tid -> navController.navigate("thread/$siteId/$tid") },
                    )
                }

                composable(
                    route = "thread/{siteId}/{tid}",
                    arguments = listOf(
                        navArgument("siteId") { type = NavType.StringType },
                        navArgument("tid") { type = NavType.StringType },
                    ),
                ) { entry ->
                    val siteId = entry.arguments?.getString("siteId") ?: return@composable
                    val tid = entry.arguments?.getString("tid") ?: return@composable
                    ThreadScreen(
                        siteId = siteId,
                        tid = tid,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}
