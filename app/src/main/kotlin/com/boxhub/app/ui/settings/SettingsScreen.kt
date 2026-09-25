package com.boxhub.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.boxhub.app.data.session.AccountManager
import com.boxhub.app.data.session.AccountState
import com.boxhub.app.data.site.SiteConfig
import com.boxhub.app.data.repo.SiteRepository
import com.boxhub.app.ui.components.SiteDot
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val accounts: AccountManager,
    sites: SiteRepository,
) : ViewModel() {
    val siteList: List<SiteConfig> = sites.all()

    fun validateAll() = accounts.validateAllLoggedIn()
    fun logout(siteId: String) = accounts.logout(siteId)
    fun validate(siteId: String) {
        viewModelScope.launch { accounts.validate(siteId) }
    }
}

/** 设置/账号页：各站登录状态一览 + 登录/退出入口 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenLogin: (siteId: String) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val states by viewModel.accounts.states.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.validateAll() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", style = MaterialTheme.typography.headlineMedium) },
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
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "账号",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            items(viewModel.siteList, key = { it.id }) { site ->
                val state = states[site.id] ?: AccountState.LoggedOut
                AccountRow(
                    site = site,
                    state = state,
                    onClick = { onOpenLogin(site.id) },
                    onLogout = { viewModel.logout(site.id) },
                    onRevalidate = { viewModel.validate(site.id) },
                )
            }
            item {
                Text(
                    "登录说明：在内置页面中输入站点账号密码，BoxHub 只保存登录 cookie" +
                        "（加密存储），不接触也不上传你的密码。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun AccountRow(
    site: SiteConfig,
    state: AccountState,
    onClick: () -> Unit,
    onLogout: () -> Unit,
    onRevalidate: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SiteDot(Color(site.brandColor), 9)
            Column(Modifier.weight(1f)) {
                Text(
                    site.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                when (state) {
                    is AccountState.LoggedIn -> Text(
                        "@${state.username}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    is AccountState.Expired -> Text(
                        "⚠ 登录已过期",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    AccountState.LoggedOut -> Text(
                        "未登录 · 点击登录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when (state) {
                is AccountState.LoggedIn -> TextButton(onClick = onLogout) {
                    Text("退出", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is AccountState.Expired -> TextButton(onClick = onRevalidate) {
                    Text("重新校验")
                }
                AccountState.LoggedOut -> TextButton(onClick = onClick) {
                    Text("登录")
                }
            }
        }
    }
}
