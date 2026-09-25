package com.boxhub.app.core.driver

import java.time.Instant
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V2EX 解析 fixture 测试（fixtures&#47;v2ex 目录，2026-09 录制）。
 */
class V2exParsersTest {

    private val base = "https://www.v2ex.com/"

    private fun stream(path: String) = checkNotNull(
        javaClass.classLoader.getResourceAsStream(path),
    ) { "fixture missing: $path" }

    private fun doc(path: String): Document =
        Jsoup.parse(stream(path).use { it.readBytes() }.inputStream(), "UTF-8", base)

    private fun text(path: String): String =
        stream(path).use { String(it.readBytes(), Charsets.UTF_8) }

    // ---------- boards ----------

    @Test
    fun nodes_json_yields_top100_by_topics() {
        val boards = V2exParsers.nodes(text("v2ex/nodes.json"), base)
        assertTrue("size=${boards.size}", boards.size in 1..100)
        // fixture 为 sort_by=topics 降序
        assertEquals("qna", boards[0].fid)
        assertEquals("问与答", boards[0].name)
        assertEquals(241884, boards[0].threads)
        assertTrue(boards[0].threads >= boards[1].threads)
        assertEquals("${base}go/qna", boards[0].url)
    }

    // ---------- 列表 ----------

    @Test
    fun go_page_parses_rows() {
        val page = V2exParsers.threadList(doc("v2ex/list_p1.html"), "v2ex", "v2ex", 1, base)
        // 节点页 20 行/页（fixture 41 处 topic-link = 20 class + 20 id + 1 脚本引用）
        assertEquals(20, page.threads.size)
        val first = page.threads.first()
        assertEquals("1244715", first.tid)
        assertTrue(first.title.contains("passkey"))
        assertEquals("unused", first.author)
        assertEquals("v2ex", first.siteId)
        assertEquals("${base}t/1244715", first.url)
        assertNotNull(first.lastPostAt)
        // 2026-09-25 14:01:34 +08:00（系统时区解析）
        val year = Instant.ofEpochSecond(first.lastPostAt!!).atZone(java.time.ZoneId.systemDefault()).year
        assertEquals(2026, year)
        // 节点页有 ?p=N 翻页
        assertNotNull(page.totalPages)
        assertTrue(page.totalPages!! >= 5)
    }

    @Test
    fun home_page_dedupes_and_has_no_pager() {
        val page = V2exParsers.threadList(doc("v2ex/home_p1.html"), "v2ex", "", 1, base)
        // 首页 101 个 topic-link（含重复）→ 按 tid 去重
        assertTrue("threads=${page.threads.size}", page.threads.size in 30..60)
        assertEquals(page.threads.size, page.threads.map { it.tid }.distinct().size)
        assertNull(page.totalPages) // 首页无翻页
        assertTrue(page.threads.all { it.url.startsWith("${base}t/") })
    }

    // ---------- 帖子详情 ----------

    @Test
    fun thread_parses_op_and_replies() {
        val detail = V2exParsers.threadDetail(doc("v2ex/thread_p1.html"), "v2ex", "1244674", 1, base)
        assertTrue(detail.title.contains("ApiCatcher"))
        assertEquals("wujiuye99", detail.author)
        assertTrue("posts=${detail.posts.size}", detail.posts.size >= 5)

        val op = detail.posts.first()
        assertEquals(1, op.floor)
        assertTrue(op.isOp)
        assertEquals("1244674", op.pid)
        assertEquals("wujiuye99", op.author)
        assertNotNull(op.postedAt)
        assertTrue(op.bodyHtml.contains("抓包"))

        // span.no 从 1 计 → 模型楼层 2L 起
        val reply = detail.posts[1]
        assertEquals(2, reply.floor)
        assertEquals("18126180", reply.pid)
        assertEquals("CalledKingsley", reply.author)
        assertNotNull(reply.postedAt)
        assertTrue(reply.bodyHtml.contains("op 你好"))

        // 无翻页 → 全量在页
        assertNull(detail.totalPages)
        assertEquals(detail.posts.size, detail.totalFloors)
    }

    // ---------- 登录 ----------

    @Test
    fun guest_home_yields_null_member() {
        assertNull(V2exParsers.memberName(doc("v2ex/home_p1.html")))
    }
}
