package com.boxhub.app.ui.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.boxhub.app.core.discuz.result.DiscuzResult
import com.boxhub.app.core.model.ThreadSummary
import com.boxhub.app.data.repo.BrowseRepository
import com.boxhub.app.data.repo.SiteRepository
import com.boxhub.app.data.site.SiteConfig
import com.boxhub.app.ui.components.LetterAvatar
import com.boxhub.app.ui.components.Pill
import com.boxhub.app.ui.components.SiteDot
import com.boxhub.app.ui.components.relativeTime
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** 聚合流条目：线程 + 来源站/版块展示信息 */
data class FeedItem(
    val thread: ThreadSummary,
    val site: SiteConfig,
    val boardName: String,
)

data class FeedUiState(
    val loading: Boolean = false,
    val items: List<FeedItem> = emptyList(),
    val error: String? = null,
    val loadedSites: Int = 0,
)

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val repo: BrowseRepository,
    private val sites: SiteRepository,
    private val accounts: com.boxhub.app.data.session.AccountManager,
) : ViewModel() {

    private val _state = MutableStateFlow(FeedUiState())
    val state = _state

    fun refresh() {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val siteResults = coroutineScope {
                sites.all().filter { it.enabled }.map { site ->
                    async {
                        // 每站取第一个版块的最新一页（v1 聚合源；M5 引入订阅源管理）
                        runCatching {
                            val boards = when (val b = repo.boards(site.id)) {
                                is DiscuzResult.Ok -> b.value
                                else -> emptyList()
                            }
                            val board = boards.firstOrNull() ?: return@runCatching emptyList()
                            when (val t = repo.threadList(site.id, board.fid, 1)) {
                                is DiscuzResult.Ok -> t.value.threads.map {
                                    FeedItem(it, site, board.name)
                                }
                                else -> emptyList()
                            }
                        }.getOrDefault(emptyList())
                    }
                }.awaitAll()
            }
            val merged = siteResults.flatten()
                .sortedByDescending { it.thread.lastPostAt ?: 0L }
                .take(80)
            _state.value = _state.value.copy(
                loading = false,
                items = merged,
                loadedSites = siteResults.count { it.isNotEmpty() },
                error = if (merged.isEmpty()) "全部站点加载失败" else null,
            )
            // 聚合流加载完 → 后台校验已登录站的会话（失效则根部横幅）
            accounts.validateAllLoggedIn()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    onOpenThread: (siteId: String, tid: String) -> Unit,
    onOpenBoards: () -> Unit,
    onOpenSettings: () -> Unit = {},
    viewModel: FeedViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { if (state.items.isEmpty()) viewModel.refresh() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("BoxHub", style = MaterialTheme.typography.headlineLarge) },
            actions = {
                IconButton(onClick = onOpenBoards) { Pill("版块") }
                IconButton(onClick = {}) {
                    Icon(Icons.Filled.Notifications, contentDescription = "通知")
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "设置")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )

        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            if (state.items.isEmpty() && state.error != null) {
                Text(
                    state.error ?: "",
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        state.items,
                        key = { it.site.id + it.thread.tid },
                    ) { item ->
                        FeedRow(item, onClick = { onOpenThread(item.site.id, item.thread.tid) })
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 68.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                            thickness = 0.5.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedRow(item: FeedItem, onClick: () -> Unit) {
    val t = item.thread
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LetterAvatar(t.author)
        Column(Modifier.weight(1f)) {
            // 来源行：站点色点 + 站点名 · 版块
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SiteDot(Color(item.site.brandColor), 7)
                Text(
                    "${item.site.displayName} · ${item.boardName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (t.pinned) Pill("顶")
            }
            Text(
                t.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (t.highlightColor != null) Color(t.highlightColor!!)
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "${t.author} · 💬 ${t.replies}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(200.dp),
            )
        }
        Text(
            relativeTime(t.lastPostAt),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
