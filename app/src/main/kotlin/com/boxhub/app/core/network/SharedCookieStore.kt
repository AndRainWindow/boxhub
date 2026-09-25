package com.boxhub.app.core.network

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * 全局共享 cookie 存储 —— 登录态的单一事实源。
 *
 * 三方共用（同一实例）：
 *  1. 各站 [SiteHttpGateway] 的 CookieJar（Discuz HTML 请求）
 *  2. Coil 图片加载器的 OkHttpClient（正文内联图/附件/头像，登录后可看原图）
 *  3. WebView 登录页采集导入（[importFromHeader]）
 *
 * 持久化：SharedPreferences 存 JSON blob，整体 Android Keystore AES-GCM 加密，
 * 进程启动同步恢复；过期 cookie 恢复时剔除。测试可用 [inMemory] 构造（无持久化）。
 */
@Singleton
class SharedCookieStore private constructor(
    private val prefs: SharedPreferences?,
) : CookieJar {

    /** host → (name → Cookie) */
    private val store = ConcurrentHashMap<String, ConcurrentHashMap<String, Cookie>>()

    init {
        prefs?.let { restore(it) }
    }

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    // ---------- CookieJar ----------

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val host = url.host
        val map = store.getOrPut(host) { ConcurrentHashMap() }
        cookies.forEach { map[it.name] = it }
        persist()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val map = store[url.host] ?: return emptyList()
        val now = System.currentTimeMillis()
        return map.values.filter { it.expiresAt > now && it.matches(url) }
    }

    // ---------- WebView 采集导入 ----------

    /**
     * 导入 CookieManager.getCookie() 的输出（"a=b; c=d"）到 [baseUrl] 所属 host。
     * @return 是否存在 Discuz 登录凭证（auth cookie）
     */
    fun importFromHeader(baseUrl: String, header: String): Boolean {
        val url = baseUrl.toHttpUrlOrNull() ?: return false
        val host = url.host
        val map = store.getOrPut(host) { ConcurrentHashMap() }
        var hasAuth = false
        header.split(";").forEach { part ->
            val i = part.indexOf('=')
            if (i <= 0) return@forEach
            val name = part.substring(0, i).trim()
            val value = part.substring(i + 1).trim()
            if (value.isEmpty()) return@forEach
            val cookie = Cookie.Builder()
                .name(name).value(value)
                .domain(host).path("/")
                .expiresAt(Long.MAX_VALUE - 1) // 会话级；Discuz auth 本身长效
                .build()
            map[name] = cookie
            if (AUTH_COOKIE.matches(name)) hasAuth = true
        }
        persist()
        return hasAuth
    }

    /** 清除一组 domain 下的全部 cookie（登出用；域名按后缀匹配 host） */
    fun clearDomains(domains: Set<String>) {
        val hosts = store.keys.filter { host ->
            domains.any { d -> host == d || host.endsWith(".$d") || d.endsWith(host) }
        }
        hosts.forEach { store.remove(it) }
        persist()
    }

    fun hasAuthCookie(domains: Set<String>): Boolean = store.any { (host, map) ->
        (domains.any { d -> host == d || host.endsWith(".$d") || d.endsWith(host) }) &&
            map.keys.any { AUTH_COOKIE.matches(it) }
    }

    /** 持久化（prefs=null 时为空操作；apply() 异步落盘） */
    private fun persist() {
        val p = prefs ?: return
        try {
            val arr = JSONArray()
            store.forEach { (host, map) ->
                map.forEach { (_, c) ->
                    arr.put(JSONObject().apply {
                        put("host", host)
                        put("name", c.name)
                        put("value", c.value)
                        put("path", c.path)
                        put("expiresAt", c.expiresAt)
                        put("secure", c.secure)
                        put("httpOnly", c.httpOnly)
                    })
                }
            }
            val plain = arr.toString().toByteArray(Charsets.UTF_8)
            p.edit().putString(KEY_BLOB, android.util.Base64.encodeToString(
                KeystoreCipher.encrypt(plain), android.util.Base64.NO_WRAP,
            )).apply()
        } catch (e: Exception) {
            // 持久化失败不阻断会话（内存态仍可用）
        }
    }

    private fun restore(p: SharedPreferences) {
        try {
            val blob = p.getString(KEY_BLOB, null) ?: return
            val plain = KeystoreCipher.decrypt(
                android.util.Base64.decode(blob, android.util.Base64.NO_WRAP),
            )
            val arr = JSONArray(String(plain, Charsets.UTF_8))
            val now = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val expires = o.optLong("expiresAt", Long.MAX_VALUE - 1)
                if (expires <= now) continue
                val c = Cookie.Builder()
                    .name(o.getString("name"))
                    .value(o.getString("value"))
                    .domain(o.getString("host"))
                    .path(o.optString("path", "/"))
                    .expiresAt(expires)
                    .apply { if (o.optBoolean("secure")) secure() ; if (o.optBoolean("httpOnly")) httpOnly() }
                    .build()
                store.getOrPut(o.getString("host")) { ConcurrentHashMap() }[c.name] = c
            }
        } catch (e: Exception) {
            // 解密失败（如密钥失效）→ 丢弃持久化态，重新登录即可
        }
    }

    companion object {
        private const val PREFS_NAME = "boxhub_cookies"
        private const val KEY_BLOB = "blob"

        /** Discuz 登录凭证 cookie 名：{prefix}_auth */
        val AUTH_COOKIE: Regex = Regex("""^\w+?_\d{4}_auth$""")

        /**
         * 从 cookie 串中发现 auth 前缀（如 "rHEX_2132_auth=..." → "rHEX_2132"）。
         * 供 SiteConfig.cookiePrefix 为 null 的站（恩山）登录后回填。
         */
        fun discoverAuthPrefix(cookieHeader: String): String? =
            Regex("""(\w+?_\d{4})_auth=""").find(cookieHeader)?.groupValues?.get(1)

        /** 无持久化实例（单测用） */
        fun inMemory(): SharedCookieStore = SharedCookieStore(null)
    }
}

/** Keystore AES-GCM（仅设备执行；单测路径不会触达） */
internal object KeystoreCipher {
    private const val ALIAS = "boxhub_cookie_key"

    fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val out = cipher.doFinal(data)
        return iv + out
    }

    fun decrypt(data: ByteArray): ByteArray {
        val iv = data.copyOfRange(0, 12)
        val payload = data.copyOfRange(12, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(payload)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }
}
