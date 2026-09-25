package com.boxhub.app.ui.reply

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.boxhub.app.core.discuz.result.DiscuzResult
import com.boxhub.app.core.model.Attachment
import com.boxhub.app.core.model.ReplyContext
import com.boxhub.app.data.repo.BrowseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 本地待上传图片 */
data class PickedImage(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val name: String,
    val mime: String,
    val uploaded: Attachment? = null,
    val uploading: Boolean = false,
    val error: String? = null,
)

data class ReplyUiState(
    val loadingForm: Boolean = true,
    val submitting: Boolean = false,
    val ctx: ReplyContext? = null,
    val message: String = "",
    val images: List<PickedImage> = emptyList(),
    val error: String? = null,
    /** 需要验证码（弹窗） */
    val captchaNeeded: Boolean = false,
    val captchaAnswer: String = "",
    val captchaRefreshing: Boolean = false,
    val success: Boolean = false,
)

@HiltViewModel
class ReplyViewModel @Inject constructor(
    private val repo: BrowseRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ReplyUiState())
    val state = _state

    private var siteId = ""
    private var tid = ""
    private var quotePid: String? = null
    private var fid: String = ""

    fun open(site: String, threadId: String, pid: String?, forumId: String) {
        if (siteId == site && tid == threadId && fid == forumId && _state.value.ctx != null) return
        siteId = site
        tid = threadId
        quotePid = pid
        fid = forumId
        _state.value = ReplyUiState(loadingForm = true)
        viewModelScope.launch {
            when (val r = repo.prepareReply(site, threadId, pid, forumId)) {
                is DiscuzResult.Ok -> _state.value = _state.value.copy(
                    loadingForm = false,
                    ctx = r.value,
                    // Discuz repquote 已把引文预填进 textarea，保留为初始内容
                    message = r.value.prefillMessage,
                )
                is DiscuzResult.Failed -> _state.value = _state.value.copy(
                    loadingForm = false,
                    error = r.rawMessage ?: r.kind.name,
                )
                DiscuzResult.SessionExpired -> _state.value = _state.value.copy(
                    loadingForm = false,
                    error = "登录已过期，请到设置页重新登录",
                )
            }
        }
    }

    fun onMessageChange(v: String) {
        _state.value = _state.value.copy(message = v, error = null)
    }

    fun pickImage(uri: Uri, name: String, mime: String) {
        val img = PickedImage(uri = uri, name = name, mime = mime, uploading = true)
        _state.value = _state.value.copy(images = _state.value.images + img)
        upload(img)
    }

    private fun upload(img: PickedImage) {
        val ctx = _state.value.ctx ?: return
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    repo.readBytes(siteId, img.uri)
                }.getOrNull()
            }
            if (bytes == null) {
                markImage(img.id, uploading = false, error = "读取图片失败")
                return@launch
            }
            when (val r = repo.uploadImage(siteId, ctx, bytes, img.name, img.mime)) {
                is DiscuzResult.Ok -> markImage(img.id, uploading = false, uploaded = r.value)
                is DiscuzResult.Failed -> markImage(
                    img.id, uploading = false,
                    error = r.rawMessage ?: r.kind.name,
                )
                else -> markImage(img.id, uploading = false, error = "登录已过期")
            }
        }
    }

    fun retryImage(id: String) {
        val img = _state.value.images.find { it.id == id } ?: return
        markImage(id, uploading = true, error = null)
        upload(img.copy(uploading = true, error = null))
    }

    fun removeImage(id: String) {
        _state.value = _state.value.copy(
            images = _state.value.images.filterNot { it.id == id },
        )
    }

    private fun markImage(
        id: String,
        uploading: Boolean,
        uploaded: Attachment? = null,
        error: String? = null,
    ) {
        _state.value = _state.value.copy(
            images = _state.value.images.map {
                if (it.id == id) it.copy(
                    uploading = uploading,
                    uploaded = uploaded ?: it.uploaded,
                    error = error,
                ) else it
            },
        )
    }

    /** 发送（验证码场景携带答案重提） */
    fun send() {
        val s = _state.value
        val ctx = s.ctx ?: return
        if (s.submitting) return
        if (s.message.isBlank() && s.images.none { it.uploaded != null }) {
            _state.value = s.copy(error = "内容不能为空")
            return
        }
        val pendingUpload = s.images.any { it.uploading }
        if (pendingUpload) {
            _state.value = s.copy(error = "图片还在上传中…")
            return
        }
        val failed = s.images.any { it.error != null && it.uploaded == null }
        if (failed) {
            _state.value = s.copy(error = "有图片上传失败，可删除后重试")
            return
        }

        _state.value = s.copy(submitting = true, error = null)
        viewModelScope.launch {
            val captcha = if (s.captchaNeeded && s.captchaAnswer.isNotBlank()) {
                com.boxhub.app.core.model.CaptchaInput(
                    mapOf(ctx.seccodeField to s.captchaAnswer.trim()),
                )
            } else null
            val uploaded = s.images.mapNotNull { it.uploaded }
            when (val r = repo.submitReply(siteId, ctx, s.message, uploaded, captcha)) {
                is DiscuzResult.Ok -> _state.value = _state.value.copy(
                    submitting = false, success = true, captchaNeeded = false,
                )
                is DiscuzResult.Failed -> {
                    if (r.kind == com.boxhub.app.core.discuz.result.ErrorKind.SeccodeRequired) {
                        _state.value = _state.value.copy(
                            submitting = false,
                            captchaNeeded = true,
                            captchaAnswer = "",
                            error = null,
                        )
                    } else {
                        _state.value = _state.value.copy(
                            submitting = false,
                            error = (r.rawMessage ?: r.kind.name) +
                                if (r.retryable) "（可重试）" else "",
                        )
                    }
                }
                DiscuzResult.SessionExpired -> _state.value = _state.value.copy(
                    submitting = false,
                    error = "登录已过期，请到设置页重新登录",
                )
            }
        }
    }

    fun onCaptchaAnswer(v: String) {
        _state.value = _state.value.copy(captchaAnswer = v)
    }

    fun submitCaptcha() {
        if (_state.value.captchaAnswer.isBlank()) return
        _state.value = _state.value.copy(captchaNeeded = false)
        send()
    }

    fun dismissCaptcha() {
        _state.value = _state.value.copy(captchaNeeded = false, captchaAnswer = "")
    }

    fun seccodeImageUrl(): String? =
        _state.value.ctx?.seccodeImageUrl(repo.baseUrl(siteId))
}

/** 回帖编辑页 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplyScreen(
    siteId: String,
    tid: String,
    quotePid: String?,
    fid: String,
    onBack: () -> Unit,
    onSent: () -> Unit,
    viewModel: ReplyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(siteId, tid, quotePid, fid) {
        viewModel.open(siteId, tid, quotePid, fid)
    }

    // 发送成功 → 通知父页刷新并返回
    LaunchedEffect(state.success) {
        if (state.success) onSent()
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            val name = "img_${System.currentTimeMillis()}.jpg"
            viewModel.pickImage(uri, name, "image/*")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (quotePid != null) "回复楼层" else "回复主题",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.send() },
                        enabled = !state.submitting && !state.loadingForm,
                    ) {
                        if (state.submitting) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            when {
                state.loadingForm -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text(
                        "正在获取回帖表单…",
                        modifier = Modifier.padding(top = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.error != null && state.ctx == null -> {
                    // 表单都没拿到：致命错误（登录墙/表单解析）
                    Text(
                        state.error ?: "",
                        modifier = Modifier.padding(top = 16.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                else -> {
                    // 已上传图片预览条
                    if (state.images.isNotEmpty()) {
                        LazyRow(
                            contentPadding = PaddingValues(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.images, key = { it.id }) { img ->
                                Box {
                                    AsyncImage(
                                        model = img.uri,
                                        contentDescription = img.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                    )
                                    if (img.uploading) {
                                        Box(
                                            Modifier
                                                .size(72.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            CircularProgressIndicator(
                                                Modifier.size(22.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.surface,
                                            )
                                        }
                                    }
                                    if (img.error != null) {
                                        TextButton(
                                            onClick = { viewModel.retryImage(img.id) },
                                            modifier = Modifier.align(Alignment.Center),
                                        ) {
                                            Text("重试", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                    IconButton(
                                        onClick = { viewModel.removeImage(img.id) },
                                        modifier = Modifier.align(Alignment.TopEnd),
                                    ) {
                                        Text("✕", color = MaterialTheme.colorScheme.surface)
                                    }
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = state.message,
                        onValueChange = viewModel::onMessageChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .padding(top = 8.dp),
                        placeholder = { Text("写下你的回复…（支持 Discuz BBCode）") },
                        minLines = 8,
                    )

                    state.error?.let { err ->
                        Text(
                            err,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    // 工具栏：选图
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = {
                            picker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        }) {
                            Icon(Icons.Filled.Image, contentDescription = null, Modifier.size(18.dp))
                            Text("  图片")
                        }
                        Text(
                            "发送后自动返回",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    // 验证码弹窗
    if (state.captchaNeeded) {
        AlertDialog(
            onDismissRequest = viewModel::dismissCaptcha,
            title = { Text("验证码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "本站回帖需要验证码，请输入下图字符",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val imgUrl = viewModel.seccodeImageUrl()
                    if (imgUrl != null) {
                        AsyncImage(
                            model = imgUrl,
                            contentDescription = "验证码",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 96.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                    }
                    OutlinedTextField(
                        value = state.captchaAnswer,
                        onValueChange = viewModel::onCaptchaAnswer,
                        singleLine = true,
                        placeholder = { Text("输入验证码") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::submitCaptcha) { Text("提交") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissCaptcha) { Text("取消") }
            },
        )
    }
}
