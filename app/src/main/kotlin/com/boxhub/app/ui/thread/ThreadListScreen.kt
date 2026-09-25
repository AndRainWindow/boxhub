package com.boxhub.app.ui.thread

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.boxhub.app.ui.components.LetterAvatar
import com.boxhub.app.ui.components.PageCapsule
import com.boxhub.app.ui.components.Pill
import com.boxhub.app.ui.components.SiteDot
import com.boxhub.app.ui.components.relativeTime
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class ThreadListUiState(
    val siteId: String = "",
    val fid: String = "",
    val boardName: String = "",
    val loading: Boolean = false,
    val threads: List<ThreadSummary> = emptyList(),
    val page: Int = 1,
    val totalPages: Int? = null,
    val error: String? = null,
)

@HiltViewModel
class ThreadListViewModel @Inject constructor(
    private val repo: BrowseRepository,
    private val sites: SiteRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ThreadListUiState())
    val state = _state

    fun open(siteId: String, fid: String, boardName: String) {
        _state.value = ThreadListUiState(siteId = siteId, fid = fid, boardName = boardName)
        loadPage(1)
    }

    fun refresh() = loadPage(_state.value.page)

    fun goPage(page: Int) {
        if (page < 1) return
        val total = _state.value.totalPages
        if (total != null && page > total) return
        loadPage(page)
    }

    private fun loadPage(page: Int) {
        val s = _state.value
        if (s.loading) return
        _state.value = s.copy(loading = true, error = null)
        viewModelScope.launch {
            when (val r = repo.threadList(s.siteId, s.fid, page)) {
                is DiscuzResult.Ok -> _state.value = _state.value.copy(
                    loading = false,
                    threads = r.value.threads,
                    page = r.value.page,
                    totalPages = r.value.totalPages,
                )
                is DiscuzResult.Failed -> _state.value = _state.value.copy(
                    loading = false,
                    error = r.rawMessage ?: r.kind.name,
                )
                else -> _state.value = _state.value.copy(
                    loading = false,
                    error = "登录已过期",
                )
            }
        }
    }

    fun siteBrandColor(): Color {
        val id = _state.value.siteId
        return sites.byId(id)?.let { Color(it.brandColor) } ?: Color.Gray
    }

    fun siteName(): String = sites.byId(_state.value.siteId)?.displayName ?: ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadListScreen(
    siteId: String,
    fid: String,
    boardName: String,
    onBack: () -> Unit,
    onOpenThread: (tid: String) -> Unit,
    viewModel: ThreadListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(siteId, fid) { viewModel.open(siteId, fid, boardName) }

    val brandColor = viewModel.siteBrandColor()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.boardName.ifBlank { boardName },
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            viewModel.siteName(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            if (state.threads.isNotEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    PageCapsule(
                        page = state.page,
                        totalPages = state.totalPages,
                        enabled = !state.loading,
                        onPrev = { viewModel.goPage(state.page - 1) },
                        onNext = { viewModel.goPage(state.page + 1) },
                        onRefresh = { viewModel.refresh() },
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            when {
                state.error != null && state.threads.isEmpty() -> Text(
                    "加载失败：${state.error}",
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.error,
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.threads, key = { it.siteId + it.tid + it.pageKey() }) { t ->
                        ThreadRow(t, brandColor, onClick = { onOpenThread(t.tid) })
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

/** 置顶行跨页复用 key 冲突兜底 */
private fun ThreadSummary.pageKey(): String = if (pinned) "pin" else ""

@Composable
private fun ThreadRow(t: ThreadSummary, brandColor: Color, onClick: () -> Unit) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (t.pinned) Pill("置顶")
                if (t.hasImage) Pill("图")
                if (t.locked) Pill("锁")
                if (t.typeName != null) Pill(t.typeName!!)
            }
            Text(
                t.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (t.pinned) FontWeight.Bold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (t.highlightColor != null) Color(t.highlightColor!!)
                else MaterialTheme.colorScheme.onSurface,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SiteDot(brandColor, 6)
                Text(
                    t.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(140.dp),
                )
                Text(
                    "💬 ${t.replies}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            relativeTime(t.lastPostAt),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
