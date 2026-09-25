package com.boxhub.app.core.network

import com.boxhub.app.data.site.SiteConfig
import java.net.URLEncoder
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 网关行为测试（MockWebServer）：
 *  1. charset 解码（UTF-8 响应 + Content-Type 声明）
 *  2. SharedCookieStore 桥接：Set-Cookie → 下一请求自动携带；importFromHeader 生效
 *  3. 请求头（UA/Accept-Language 注入）
 */
class GatewayMockWebServerTest {

    private lateinit var server: MockWebServer
    private lateinit var store: SharedCookieStore
    private lateinit var gateway: SiteHttpGateway

    private val testConfig = SiteConfig(
        id = "test",
        displayName = "测试站",
        baseUrl = "http://localhost/", // 运行时用 server.url 覆盖 baseUrl 的站点
        domains = setOf("localhost"),
        charset = "UTF-8",
        userAgent = "BoxHubTest/1.0",
        brandColor = 0xFF3A6EA5.toInt(),
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = SharedCookieStore.inMemory()
        // baseUrl 指向 MockWebServer（SiteConfig.baseUrl 必须以 / 结尾且为 http）
        gateway = SiteHttpGateway(testConfig.copy(baseUrl = server.url("/").toString()), store)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `get decodes body with declared charset`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody("中文内容 OK")
                .setHeader("Content-Type", "text/html; charset=UTF-8"),
        )
        val page = gateway.get(server.url("/page").toString())
        assertEquals(200, page.code)
        assertTrue(page.text().contains("中文内容 OK"))
        assertEquals(java.nio.charset.Charset.forName("UTF-8"), page.charset())
    }

    @Test
    fun `cookies from response are sent on next request`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody("ok")
                .addHeader("Set-Cookie", "gXRl_2132_auth=token123; Path=/"),
        )
        gateway.get(server.url("/login").toString())

        server.enqueue(MockResponse().setBody("ok"))
        gateway.get(server.url("/next").toString())

        val req1 = server.takeRequest()
        val req2 = server.takeRequest()
        // 第一次请求无 cookie，第二次自动携带
        assertTrue("req1 不应带 cookie: ${req1.getHeader("Cookie")}",
            req1.getHeader("Cookie") == null)
        val cookie2 = req2.getHeader("Cookie") ?: ""
        assertTrue("req2 应携带 auth: $cookie2", cookie2.contains("gXRl_2132_auth=token123"))
    }

    @Test
    fun `importFromHeader makes auth cookie available`() {
        val base = server.url("/").toString()
        val header = "gXRl_2132_auth=abc; gXRl_2132_saltkey=s1; other=1"
        val hasAuth = store.importFromHeader(base, header)
        assertTrue(hasAuth)
        val cookies = store.loadForRequest(server.url("/"))
        assertTrue(cookies.any { it.name == "gXRl_2132_auth" && it.value == "abc" })
        assertTrue(cookies.any { it.name == "gXRl_2132_saltkey" })
        assertTrue(cookies.any { it.name == "other" })
    }

    @Test
    fun `postForm encodes fields with site charset (GBK roundtrip)`() = runBlocking {
        val gbkConfig = testConfig.copy(
            baseUrl = server.url("/").toString(),
            charset = "GBK",
        )
        val gbkGateway = SiteHttpGateway(gbkConfig, store)
        server.enqueue(MockResponse().setBody("ok"))
        gbkGateway.postForm(
            server.url("/reply").toString(),
            fields = listOf("message" to "中文回帖测试内容", "formhash" to "abc123"),
            referer = server.url("/form").toString(),
        )
        val req = server.takeRequest()
        // body = GBK 字节经百分号编码；按 GBK 解码还原 = 底层字节是 GBK 而非 UTF-8
        val raw = String(req.body.readByteArray(), Charsets.US_ASCII)
        val decoded = java.net.URLDecoder.decode(raw, "GBK")
        assertTrue("decoded=$decoded", decoded.contains("中文回帖测试内容"))
        assertTrue(decoded.contains("formhash=abc123"))
        val ct = req.getHeader("Content-Type") ?: ""
        assertTrue("Content-Type=$ct", ct.contains("charset=GBK"))
        assertEquals(server.url("/form").toString(), req.getHeader("Referer"))
    }

    @Test
    fun `user agent and language headers injected`() = runBlocking {
        server.enqueue(MockResponse().setBody("ok"))
        gateway.get(server.url("/ua").toString())
        val req = server.takeRequest()
        assertEquals("BoxHubTest/1.0", req.getHeader("User-Agent"))
        assertTrue(req.getHeader("Accept-Language")!!.contains("zh-CN"))
    }
}
