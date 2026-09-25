package com.boxhub.app.ui.boards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.boxhub.app.core.model.Board
import com.boxhub.app.data.repo.BrowseRepository
import com.boxhub.app.data.site.SiteConfig
import com.boxhub.app.ui.components.Pill
import com.boxhub.app.ui.components.SiteDot
import com.boxhub.app.ui.components.UiState
import com.boxhub.app.ui.components.update
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class BoardsUiState(
    val selectedSite: SiteConfig? = null,
    val loading: Boolean = false,
    val boards: List<Board> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class BoardsViewModel @Inject constructor(
    private val repo: BrowseRepository,
    private val sitesRepo: com.boxhub.app.data.repo.SiteRepository,
) : ViewModel() {
    val siteList: List<SiteConfig> = sitesRepo.all()


    private val _state = MutableStateFlow(BoardsUiState())
    val state = _state

    fun selectSite(siteId: String) {
        val site = sitesRepo.byId(siteId) ?: return
        _state.value = _state.value.copy(selectedSite = site, error = null)
        load()
    }

    fun load() {
        val site = _state.value.selectedSite ?: sitesRepo.all().firstOrNull() ?: return
        _state.value = _state.value.copy(selectedSite = site, loading = true, error = null)
        viewModelScope.launch {
            when (val r = repo.boards(site.id)) {
                is com.boxhub.app.core.discuz.result.DiscuzResult.Ok ->
                    _state.value = _state.value.copy(loading = false, boards = r.value)
                is com.boxhub.app.core.discuz.result.DiscuzResult.Failed ->
                    _state.value = _state.value.copy(loading = false, error = r.rawMessage ?: r.kind.name)
                else ->
                    _state.value = _state.value.copy(loading = false, error = "登录已过期")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BoardsScreen(
    onOpenBoard: (siteId: String, fid: String, name: String) -> Unit,
    viewModel: BoardsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sites = viewModel.siteList

    LaunchedEffect(Unit) {
        if (state.selectedSite == null) viewModel.selectSite("enshan")
    }

    Column(Modifier.fillMaxSize()) {
        // 站点切换 chips
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sites.forEach { site ->
                FilterChip(
                    selected = state.selectedSite?.id == site.id,
                    onClick = { viewModel.selectSite(site.id) },
                    label = {
                        Text(site.displayName, style = MaterialTheme.typography.labelMedium)
                    },
                    leadingIcon = {
                        SiteDot(Color(site.brandColor), 7)
                    },
                )
            }
        }

        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { viewModel.load() },
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state.error != null && state.boards.isEmpty() -> Text(
                    "加载失败：${state.error}\n下拉重试",
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.boards.isEmpty() && state.loading -> Unit
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.boards, key = { it.fid }) { board ->
                        BoardCard(
                            board = board,
                            brandColor = Color(state.selectedSite?.brandColor ?: 0),
                            onClick = {
                                state.selectedSite?.let {
                                    onOpenBoard(it.id, board.fid, board.name)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BoardCard(board: Board, brandColor: Color, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SiteDot(brandColor, 9)
            Column(Modifier.weight(1f)) {
                Text(
                    board.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (board.description.isNotBlank()) {
                    Text(
                        board.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            if (board.threads > 0) Pill("${board.threads}")
        }
    }
}
