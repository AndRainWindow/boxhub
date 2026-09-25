package com.boxhub.app.core.discuz.parse

import com.boxhub.app.data.site.SiteRegistry
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 吾爱破解（GBK）fixture 测试。
 * 关键：fixture 是**原始 GBK 字节**，必须按 GBK 解码——验证网关编码约定与解析器协同。
 */
class PojieGbkFixturesTest {

    private fun gbkFixture(path: String): org.jsoup.nodes.Document? =
        javaClass.classLoader.getResourceAsStream(path)?.use {
            Jsoup.parse(it, "GB18030", "https://www.52pojie.cn/")
        }

    @Test
    fun `forumdisplay parses under gbk`() {
        val doc = gbkFixture("52pojie/forumdisplay_p1.html") ?: error("fixture missing")
        val config = SiteRegistry.byId("52pojie")!!
        assertEquals("GBK", config.charset)

        val expected = doc.select("tbody[id^=normalthread_], tbody[id^=stickthread_]").size
        assertTrue("fixture 应有主题行，实际 $expected", expected >= 30)

        val (threads, totalPages) = DiscuzParsers.threadList(doc, config, "52pojie", "4", 1)
        assertEquals(expected, threads.size)

        val first = threads.first()
        assertTrue("tid: ${first.tid}", first.tid.matches(Regex("\\d+")))
        assertTrue("title: ${first.title}", first.title.isNotBlank())
        // GBK 解码正确性：标题应是可读中文而非乱码/替换符
        assertTrue(
            "GBK 标题应为可读中文: ${first.title}",
            first.title.contains(Regex("[\\u4e00-\\u9fff]")),
        )
        assertTrue("url rewrite 形态", first.url.contains("thread-${first.tid}"))
        totalPages?.let { assertTrue(it >= 2) }
    }

    @Test
    fun `viewthread parses floors under gbk`() {
        val doc = gbkFixture("52pojie/viewthread_p1.html") ?: error("fixture missing")
        val config = SiteRegistry.byId("52pojie")!!
        val detail = DiscuzParsers.threadDetail(doc, config, "52pojie", "2080091", 1)

        assertTrue("posts=${detail.posts.size}", detail.posts.size >= 5)
        assertTrue("title=${detail.title}", detail.title.isNotBlank())
        assertTrue("GBK 标题含中文", detail.title.contains(Regex("[\\u4e00-\\u9fff]")))
        val p0 = detail.posts.first()
        assertEquals("首楼 floor=1", 1, p0.floor)
        assertTrue("正文非空", p0.bodyHtml.isNotBlank())
        assertTrue("作者非空", p0.author.isNotBlank())
    }
}
