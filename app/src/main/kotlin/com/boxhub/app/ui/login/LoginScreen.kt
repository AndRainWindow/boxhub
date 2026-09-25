package com.boxhub.app.ui.login

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boxhub.app.data.site.SiteRegistry
import com.boxhub.app.data.session.AccountManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay

/**
 * 站点登录页：内嵌 WebView，用户自行输入账号密码（凭证不经过 App）。
 * 轮询 CookieManager 抓取 `{prefix}_auth` → AccountManager 校验 → 成功自动返回。
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    val accounts: AccountManager,
) : ViewModel() {
    fun site(siteId: String) = SiteRegistry.byId(siteId)
}

enum class LoginPhase { WAITING, CAPTURING, VALIDATING, DONE }

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    siteId: String,
    onBack: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val site = viewModel.site(siteId) ?: run { onBack(); return }
    val accountState by viewModel.accounts.states.collectAsStateWithLifecycle()
    var phase by remember { mutableStateOf(LoginPhase.WAITING) }
    var pageTitle by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    val webViewRef = remember { arrayOfNulls<WebView>(1) }

    // 轮询采集（onPageFinished 之外的兜底：JS 延迟写 cookie、验证码回调等）
    LaunchedEffect(siteId) {
        val cm = CookieManager.getInstance()
        var stable = 0
        while (true) {
            delay(1_500)
            if (phase == LoginPhase.VALIDATING) {
                // 校验窗口：成功则下方 effect 返回；8s 未过 = 采集早于真正登录
                // （如看雪 bbs_sid 游客也有）→ 回退轮询，真登录完成后下一轮补采
                var waited = 0
                while (phase == LoginPhase.VALIDATING && waited < 8_000) {
                    delay(500)
                    waited += 500
                }
                if (phase == LoginPhase.VALIDATING) {
                    stable = 0
                    phase = LoginPhase.WAITING
                }
                continue
            }
            if (phase != LoginPhase.WAITING) continue
            val header = cm.getCookie(site.baseUrl) ?: continue
            if (viewModel.accounts.capturedEnough(siteId, header)) {
                stable++
                if (stable >= 2) { // 连续两次命中，防抖
                    phase = LoginPhase.CAPTURING
                    val ok = viewModel.accounts.onWebCaptured(siteId, header)
                    if (ok) {
                        phase = LoginPhase.VALIDATING
                    } else {
                        stable = 0
                        phase = LoginPhase.WAITING
                    }
                }
            } else {
                stable = 0
            }
        }
    }

    // 校验完成（状态变为 LoggedIn/Done）→ 返回
    LaunchedEffect(accountState) {
        if (phase == LoginPhase.VALIDATING &&
            accountState[siteId] is com.boxhub.app.data.session.AccountState.LoggedIn
        ) {
            phase = LoginPhase.DONE
            delay(600)
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("${site.displayName} · 登录", style = MaterialTheme.typography.titleLarge)
                        Text(
                            when (phase) {
                                LoginPhase.WAITING -> "请在页面中完成登录"
                                LoginPhase.CAPTURING -> "已检测到登录，采集凭据…"
                                LoginPhase.VALIDATING -> "正在校验…"
                                LoginPhase.DONE -> "登录成功 ✓"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = when (phase) {
                                LoginPhase.DONE -> MaterialTheme.colorScheme.primary
                                LoginPhase.WAITING -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.tertiary
                            },
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
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewRef[0] = this
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.setSupportZoom(true)
                        // 桌面 UA：恩山等站移动 UA 会给残缺登录页
                        settings.userAgentString = site.userAgent
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                pageTitle = view?.title ?: ""
                            }

                            @Deprecated("Deprecated in Java")
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                            ): Boolean = false // 站内跳转留在 WebView
                        }
                        loadUrl(site.baseUrl + site.loginPath)
                    }
                },
            )
        }
    }
}
