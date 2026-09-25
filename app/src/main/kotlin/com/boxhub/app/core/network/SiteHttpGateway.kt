package com.boxhub.app.core.network

import com.boxhub.app.data.site.DesktopChromeUa
import com.boxhub.app.data.site.SiteConfig
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup

/**
 * 每站一个网关：独立 OkHttp client / UA / 超时；cookie 走 [SharedCookieStore]
 * （与 Coil 图片加载、WebView 登录采集共用同一实例）。
 * 编码处理的唯一收敛点（GBK 等站点差异只在这里出没）。
 */
class SiteHttpGateway(
    val config: SiteConfig,
    private val cookies: SharedCookieStore,
) {

    data class Page(
        val bytes: ByteArray,
        val code: Int,
        val finalUrl: String,
        val contentType: String?,
        /** 站点配置编码（Content-Type 缺失时兜底） */
        val defaultCharset: Charset,
    ) {
        /** 内容编码：Content-Type 声明优先，站点配置兜底 */
        fun charset(): Charset {
            val fromHeader = contentType
                ?.substringAfter("charset=", "")?.substringBefore(";")?.trim()
                ?.takeIf { it.isNotEmpty() }
            return try {
                Charset.forName(fromHeader ?: defaultCharset.name())
            } catch (e: Exception) {
                defaultCharset
            }
        }

        fun text(): String = String(bytes, charset())

        fun jsoup(): org.jsoup.nodes.Document =
            Jsoup.parse(bytes.inputStream(), charset().name(), finalUrl)
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .cookieJar(cookies)
        .addInterceptor(Interceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", config.userAgent.ifEmpty { DesktopChromeUa })
                .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build()
            chain.proceed(req)
        })
        .build()

    /** GET HTML/JSON 页面；网络异常向上抛，由调用方映射 DiscuzResult */
    suspend fun get(url: String, referer: String? = null): Page = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url).apply {
            if (referer != null) addHeader("Referer", referer)
        }.build()
        client.newCall(b).execute().use { resp -> toPage(resp, url) }
    }

    suspend fun getBytes(url: String, referer: String? = null): ByteArray = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url).apply {
            if (referer != null) addHeader("Referer", referer)
        }.build()
        client.newCall(b).execute().use { it.body?.bytes() ?: ByteArray(0) }
    }

    /**
     * 表单 POST（写操作唯一入口）。
     * **手工构造 body**：OkHttp FormBody 硬编码 UTF-8，GBK 站（吾爱）必须按 config.charset
     * 逐字段编码，且 Content-Type 声明同一 charset —— GBK 三坑收敛于此。
     */
    suspend fun postForm(
        url: String,
        fields: List<Pair<String, String>>,
        referer: String? = null,
    ): Page = withContext(Dispatchers.IO) {
        val charset = Charset.forName(config.charset)
        val bodyText = fields.joinToString("&") { (k, v) ->
            "${enc(k, charset)}=${enc(v, charset)}"
        }
        val body = bodyText.toRequestBody(
            "application/x-www-form-urlencoded; charset=${config.charset}".toMediaType(),
        )
        val req = Request.Builder()
            .url(url)
            .post(body)
            .apply { if (referer != null) addHeader("Referer", referer) }
            .build()
        client.newCall(req).execute().use { toPage(it, url) }
    }

    /** multipart 文件上传（swfupload 等）；文本字段固定 UTF-8（实际均为 ASCII 的 uid/hash） */
    suspend fun postMultipart(
        url: String,
        fields: Map<String, String>,
        fileField: String,
        filename: String,
        mime: String,
        fileBytes: ByteArray,
        referer: String? = null,
    ): Page = withContext(Dispatchers.IO) {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        fields.forEach { (k, v) -> builder.addFormDataPart(k, v) }
        builder.addFormDataPart(
            fileField, filename,
            fileBytes.toRequestBody(mime.toMediaType()),
        )
        val req = Request.Builder()
            .url(url)
            .post(builder.build())
            .apply { if (referer != null) addHeader("Referer", referer) }
            .build()
        client.newCall(req).execute().use { toPage(it, url) }
    }

    private fun enc(v: String, charset: Charset): String =
        URLEncoder.encode(v, charset.name())

    private fun toPage(resp: Response, fallbackUrl: String): Page {
        val body = resp.body?.bytes() ?: ByteArray(0)
        return Page(
            bytes = body,
            code = resp.code,
            finalUrl = resp.request.url.toString().ifEmpty { fallbackUrl },
            contentType = resp.header("Content-Type"),
            defaultCharset = Charset.forName(config.charset),
        )
    }

    /** 当前对 host 有效的 cookie（登录态检测用） */
    fun cookiesFor(baseUrl: String): Map<String, String> {
        val url = baseUrl.toHttpUrlOrNull() ?: return emptyMap()
        return cookies.loadForRequest(url).associate { it.name to it.value }
    }
}
