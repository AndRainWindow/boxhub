package com.boxhub.app.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.boxhub.app.core.discuz.result.DiscuzResult
import com.boxhub.app.core.model.Post
import com.boxhub.app.data.repo.BrowseRepository
import com.boxhub.app.data.repo.SiteRepository
import com.boxhub.app.ui.components.HtmlText
import com.boxhub.app.ui.components.LetterAvatar
import com.boxhub.app.ui.components.PageCapsule
import com.boxhub.app.ui.components.Pill
import com.boxhub.app.ui.components.relativeTime
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class ThreadUiState(
    val loading: Boolean = false,
    val title: String = "",
    val posts: List<Post> = emptyList(),
    val page: Int = 1,
    val totalPages: Int? = null,
    val error: String? = null,
    /** 所属版块（回帖必需；从楼层页面包屑解析） */
    val fid: String? = null,
)

@HiltViewModel
class ThreadViewModel @Inject constructor(
    private val repo: BrowseRepository,
    private val sites: SiteRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ThreadUiState())
    val state = _state

    private var siteId = ""
    private var tid = ""

    fun open(site: String, threadId: String, page: Int) {
        siteId = site
        tid = threadId
        loadPage(page)
    }

    fun refresh() = loadPage(_state.value.page)

    fun goPage(page: Int) {
        if (page < 1) return
        val total = _state.value.totalPages
        if (total != null && page > total) return
        loadPage(page)
    }

    private fun loadPage(page: Int) {
        if (_state.value.loading || siteId.isEmpty()) return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            when (val r = repo.threadDetail(siteId, tid, page)) {
                is DiscuzResult.Ok -> _state.value = _state.value.copy(
                    loading = false,
                    title = r.value.title,
                    posts = r.value.posts,
                    page = r.value.page,
                    totalPages = r.value.totalPages,
                    fid = r.value.fid ?: _state.value.fid,
                )
                is DiscuzResult.Failed -> _state.value = _state.value.copy(
                    loading = false,
                    error = r.rawMessage ?: r.kind.name,
                )
                else -> _state.value = _state.value.copy(loading = false, error = "登录已过期")
            }
        }
    }

    fun siteName(): String = sites.byId(siteId)?.displayName ?: ""
    fun siteBaseUrl(): String = sites.byId(siteId)?.baseUrl ?: ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    siteId: String,
    tid: String,
    onBack: () -> Unit,
    /** 回帖成功返回后的刷新信号（AppRoot 用 savedStateHandle 传递） */
    refreshSignal: Boolean = false,
    onRefreshConsumed: () -> Unit = {},
    onReplyTopic: (fid: String) -> Unit,
    onReplyFloor: (pid: String, fid: String) -> Unit,
    viewModel: ThreadViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 内置图片查看器状态（图片列表 + 起始下标）
    var viewer by remember { mutableStateOf<Pair<List<com.boxhub.app.core.model.Attachment>, Int>?>(null) }

    LaunchedEffect(siteId, tid) { viewModel.open(siteId, tid, 1) }
    LaunchedEffect(refreshSignal) {
        if (refreshSignal) {
            viewModel.refresh()
            onRefreshConsumed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.title,
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
            if (state.posts.isNotEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
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
        floatingActionButton = {
            val fid = state.fid
            if (state.posts.isNotEmpty() && fid != null) {
                androidx.compose.material3.FloatingActionButton(
                    onClick = { onReplyTopic(fid) },
                    containerColor = MaterialTheme.colorScheme.tertiary,
                ) {
                    Text(
                        "回复",
                        color = MaterialTheme.colorScheme.onTertiary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state.error != null && state.posts.isEmpty() -> Text(
                    "加载失败：${state.error}",
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.error,
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(vertical = 4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val baseUrl = viewModel.siteBaseUrl()
                    val replyFid = state.fid // 只读引擎（V2EX/海纳斯/看雪）无 fid → 隐藏回复入口
                    itemsIndexed(state.posts, key = { _, p -> p.pid }) { _, post ->
                        PostCard(
                            post = post,
                            siteBaseUrl = baseUrl,
                            onOpenImage = { imgs, idx -> viewer = imgs to idx },
                            onReply = replyFid?.let { fid -> { onReplyFloor(post.pid, fid) } },
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }

        // 内置图片查看器（全屏覆盖）
        viewer?.let { (imgs, idx) ->
            com.boxhub.app.ui.components.ImageViewer(
                images = imgs,
                startIndex = idx,
                onClose = { viewer = null },
            )
        }
        }
    }
}

@Composable
private fun PostCard(
    post: Post,
    siteBaseUrl: String,
    onOpenImage: (List<com.boxhub.app.core.model.Attachment>, Int) -> Unit,
    onReply: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        // 楼层头：头像 + 作者 + 楼层号/时间
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            LetterAvatar(post.author, size = 36)
            Column(Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        post.author,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (post.isOp) Pill("楼主")
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        listOfNotNull(
                            "#${post.floor}",
                            relativeTime(post.postedAt).takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (onReply != null) {
                        androidx.compose.material3.TextButton(
                            onClick = onReply,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 6.dp, vertical = 0.dp,
                            ),
                        ) {
                            Text(
                                "回复",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }

        // 正文
        HtmlText(
            post.bodyHtml,
            baseUrl = siteBaseUrl,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp, top = 8.dp, end = 8.dp),
        )

        // 附件图片（游客缩略图直出；点击进入内置查看器）
        val imageAtts = post.attachments.filter { it.isImage }
        if (imageAtts.isNotEmpty()) {
            Column(
                Modifier.padding(start = 48.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                imageAtts.forEachIndexed { idx, att ->
                    AsyncImage(
                        model = att.url,
                        contentDescription = att.description,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onOpenImage(imageAtts, idx) },
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
    }
}
