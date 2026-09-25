package com.boxhub.app.data.session

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import com.boxhub.app.core.network.DriverFactory
import com.boxhub.app.core.network.SharedCookieStore
import com.boxhub.app.data.site.SiteRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 每站账号状态 */
sealed interface AccountState {
    data object LoggedOut : AccountState
    data class LoggedIn(val username: String) : AccountState
    /** 曾登录但校验失败（cookie 失效/被清） */
    data object Expired : AccountState
}

/**
 * 账号状态机：WebView 采集 → 共享 cookie → 后台校验 → 状态流。
 *
 * - 登录方式（用户确认的方案）：WebView 打开站点登录页，用户自行输入账号密码，
 *   App 只通过 [CookieManager] 抓取 `{prefix}_auth`/`_saltkey` cookie，不触碰凭证。
 * - 校验：`DiscuzDriver.loginUsername()` 解析首页登录名；游客化 → Expired。
 * - 持久化：cookie 在 SharedCookieStore（Keystore 加密）；用户名存本 prefs。
 */
@Singleton
class AccountManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cookies: SharedCookieStore,
    private val drivers: DriverFactory,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("boxhub_accounts", Context.MODE_PRIVATE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _states = MutableStateFlow(loadStates())
    val states: StateFlow<Map<String, AccountState>> = _states

    /** 每会话已校验过的站（避免重复网络校验） */
    private val validatedThisSession = mutableSetOf<String>()

    private fun loadStates(): Map<String, AccountState> =
        SiteRegistry.all.associate { site ->
            val username = prefs.getString(keyUser(site.id), null)
            val state = when {
                username != null -> AccountState.LoggedIn(username)
                prefs.getBoolean(keyExpired(site.id), false) -> AccountState.Expired
                else -> AccountState.LoggedOut
            }
            site.id to state
        }

    // ---------- WebView 采集 ----------

    /**
     * 登录页 CookieManager 命中 auth 后调用。
     * @param cookieHeader `CookieManager.getCookie(baseUrl)` 输出（"a=b; c=d"）
     * @return 是否识别到 Discuz auth cookie（识别到才继续校验流程）
     */
    fun onWebCaptured(siteId: String, cookieHeader: String): Boolean {
        val site = SiteRegistry.byId(siteId) ?: return false
        val hasAuth = cookies.importFromHeader(site.baseUrl, cookieHeader)
        if (!hasAuth) return false

        // cookie 前缀自动发现（恩山等 cookiePrefix=null 的站）
        if (site.cookiePrefix == null) {
            SharedCookieStore.discoverAuthPrefix(cookieHeader)?.let { prefix ->
                prefs.edit().putString(keyPrefix(siteId), prefix).apply()
            }
        }
        // 后台校验用户名
        scope.launch {
            val username = runCatching {
                drivers.driver(siteId, site).loginUsername()
            }.getOrNull()
            android.util.Log.d("BoxHubAcct", "capture-validate $siteId -> username=$username")
            if (username != null) {
                prefs.edit().putString(keyUser(siteId), username).apply()
                validatedThisSession.add(siteId)
                update(siteId, AccountState.LoggedIn(username))
            } else {
                // cookie 已导入但首页解析不到登录名：可能是站点响应慢，稍后重试一次
                kotlinx.coroutines.delay(3_000)
                val retry = runCatching {
                    drivers.driver(siteId, site).loginUsername()
                }.getOrNull()
                if (retry != null) {
                    prefs.edit().putString(keyUser(siteId), retry).apply()
                    validatedThisSession.add(siteId)
                    update(siteId, AccountState.LoggedIn(retry))
                }
            }
        }
        return true
    }

    /** 登录采集等待期间的 cookie 头检测（供 LoginScreen 轮询调用） */
    fun capturedEnough(siteId: String, cookieHeader: String): Boolean {
        val site = SiteRegistry.byId(siteId) ?: return false
        val prefix = site.cookiePrefix
            ?: prefs.getString(keyPrefix(siteId), null)
            ?: SharedCookieStore.discoverAuthPrefix(cookieHeader)
            ?: return false
        return cookieHeader.contains("${prefix}_auth=")
    }

    // ---------- 校验 ----------

    /** 校验单站（返回新状态）；原 LoggedIn 但游客化 → Expired */
    suspend fun validate(siteId: String): AccountState {
        val site = SiteRegistry.byId(siteId) ?: return AccountState.LoggedOut
        val current = _states.value[siteId] ?: AccountState.LoggedOut
        if (current is AccountState.LoggedOut && !cookies.hasAuthCookie(site.domains)) return current
        val username = runCatching { drivers.driver(siteId, site).loginUsername() }.getOrNull()
        android.util.Log.d("BoxHubAcct", "validate $siteId -> username=$username (was=$current)")
        val next = if (username != null) {
            validatedThisSession.add(siteId)
            prefs.edit().putString(keyUser(siteId), username)
                .putBoolean(keyExpired(siteId), false).apply()
            AccountState.LoggedIn(username)
        } else {
            // 有 auth cookie 却解析不到登录名 → 会话失效（持久化，重启可出横幅）
            prefs.edit().putBoolean(keyExpired(siteId), true).apply()
            AccountState.Expired
        }
        update(siteId, next)
        return next
    }

    /**
     * Feed 首次加载后调用：校验所有已登录站（每会话一次）。
     * 失效站进入 Expired → AppRoot 显示横幅。
     */
    fun validateAllLoggedIn() {
        val targets = _states.value.filterValues { it is AccountState.LoggedIn }.keys +
            // 自愈：cookie 已采集（auth 在库）但用户名校验曾失败的站
            SiteRegistry.all.filter {
                it.id !in validatedThisSession &&
                    cookies.hasAuthCookie(it.domains) &&
                    _states.value[it.id] !is AccountState.LoggedIn
            }.map { it.id }
        for (siteId in targets) {
            if (siteId in validatedThisSession) continue
            scope.launch { validate(siteId) }
        }
    }

    // ---------- 登出 ----------

    fun logout(siteId: String) {
        val site = SiteRegistry.byId(siteId) ?: return
        cookies.clearDomains(site.domains)
        // WebView 侧同进程共享 CookieManager，按域逐个置空
        CookieManager.getInstance().apply {
            site.domains.forEach { domain ->
                listOf("https://$domain/", "https://www.$domain/").forEach { u ->
                    val header = getCookie(u) ?: return@forEach
                    header.split(";").forEach { part ->
                        val name = part.substringBefore("=").trim()
                        if (name.isNotEmpty()) setCookie(u, "$name=; Max-Age=0")
                    }
                }
            }
            flush()
        }
        prefs.edit().remove(keyUser(siteId)).remove(keyPrefix(siteId)).remove(keyExpired(siteId)).apply()
        validatedThisSession.remove(siteId)
        update(siteId, AccountState.LoggedOut)
    }

    private fun update(siteId: String, state: AccountState) {
        _states.value = _states.value + (siteId to state)
    }

    private fun keyUser(siteId: String) = "user_$siteId"
    private fun keyPrefix(siteId: String) = "prefix_$siteId"
    private fun keyExpired(siteId: String) = "expired_$siteId"

    /** 运行时已发现的 cookie 前缀（登录后回填；供 UI/日志展示） */
    fun discoveredPrefix(siteId: String): String? = prefs.getString(keyPrefix(siteId), null)
}
