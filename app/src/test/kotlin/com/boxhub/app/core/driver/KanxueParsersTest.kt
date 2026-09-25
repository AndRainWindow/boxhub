package com.boxhub.app.core.driver

import java.time.Instant
import java.time.ZoneId
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 看雪（Xiuno BBS）解析 fixture 测试（fixtures/kanxue_*，2026-09 录制）。
 */
class KanxueParsersTest {

    private val base = "https://bbs.kanxue.com/"

    private fun doc(path: String): Document {
        val bytes = checkNotNull(javaClass.classLoader.getResourceAsStream(path)) {
            "fixture missing: $path"
        }.use { it.readBytes() }
        return Jsoup.parse(bytes.inputStream(), "UTF-8", base)
    }

    // ---------- 首页版块目录 ----------

    @Test
    fun home_yields_new_feed_plus_forums() {
        val boards = KanxueParsers.homeBoards(doc("kanxue_home.htm"), base)
        // [0] 聚合流伪版块「最新主题」
        assertEquals("new", boards[0].fid)
        assertEquals("最新主题", boards[0].name)
        assertTrue("forums=${boards.size}", boards.size >= 15)
        val f170 = boards.firstOrNull { it.fid == "170" }
        assertNotNull(f170)
        assertTrue(f170!!.name.isNotBlank())
        // fid 唯一
        assertEquals(boards.size, boards.map { it.fid }.distinct().size)
    }

    // ---------- 列表 ----------

    @Test
    fun forum170_list_parses_rows_counts_and_time() {
        val page = KanxueParsers.threadList(doc("kanxue_forum170.htm"), "kanxue", "170", 1, base)
        assertEquals(31, page.threads.size)
        // pager 实测 forum-170-1/2 → 至少 2 页
        assertTrue("totalPages=${page.totalPages}", (page.totalPages ?: 0) >= 2)

        val sticky = page.threads.first()
        assertEquals("289688", sticky.tid)
        assertTrue(sticky.pinned)
        assertTrue(sticky.title.contains("SDC 2026"))
        assertEquals("kanxue", sticky.author)
        assertEquals(129, sticky.replies)
        assertEquals(120_000, sticky.views) // "12w" 万缩写展开
        assertNotNull(sticky.lastPostAt)
        // "2026-1-6 13:20" 不补零日期
        val y = Instant.ofEpochSecond(sticky.lastPostAt!!).atZone(ZoneId.systemDefault()).year
        assertEquals(2026, y)
        assertEquals("170", sticky.fid)
        assertTrue(sticky.url.endsWith("thread-289688.htm"))

        // 非置顶行也解析
        assertTrue(page.threads.count { !it.pinned } >= 25)
        assertEquals(page.threads.size, page.threads.map { it.tid }.distinct().size)
    }

    @Test
    fun new_list_parses_with_pager() {
        val page = KanxueParsers.threadList(doc("kanxue_new.htm"), "kanxue", "new", 1, base)
        assertTrue("threads=${page.threads.size}", page.threads.size >= 20)
        assertTrue(page.threads.all { it.tid.isNotBlank() && it.title.isNotBlank() })
        // new--N 翻页形态
        assertTrue("totalPages=${page.totalPages}", (page.totalPages ?: 0) >= 5)
    }

    // ---------- 帖子详情 ----------

    @Test
    fun thread_p1_parses_op_card_and_floors() {
        val detail = KanxueParsers.threadDetail(doc("kanxue_thread_p1.htm"), "kanxue", "293023", 1, base)
        assertTrue(detail.title.contains("Droid ASC"))
        // 1L 主楼 + 24 楼层（p1 实测到 25F）
        assertEquals(25, detail.posts.size)
        assertEquals((1..25).toList(), detail.posts.map { it.floor })

        val op = detail.posts.first()
        assertTrue(op.isOp)
        assertEquals(1, op.floor)
        assertEquals("293023", op.pid) // 主楼无 data-pid，约定用 tid
        assertTrue(op.author.isNotBlank())
        assertTrue(op.bodyHtml.contains("ASC"))
        assertNotNull(op.postedAt) // "2天前" 相对时间

        val floor2 = detail.posts[1]
        assertEquals("1897459", floor2.pid)
        assertEquals(2, floor2.floor)
        val floor3 = detail.posts[2]
        assertTrue(floor3.bodyHtml.contains("6666666"))
        assertEquals(2, detail.totalPages)
        assertNull(detail.totalFloors) // p1 ≠ 末页，总数未知
        assertNull(detail.fid) // 只读引擎
    }

    @Test
    fun thread_p2_reaches_last_floor() {
        val detail = KanxueParsers.threadDetail(doc("kanxue_thread_p2.htm"), "kanxue", "293023", 2, base)
        assertEquals(25, detail.posts.size)
        assertEquals(26, detail.posts.first().floor)
        assertEquals(50, detail.posts.last().floor)
        assertEquals(2, detail.totalPages)
        assertEquals(50, detail.totalFloors) // 末页可推总楼数
        assertTrue(detail.title.contains("Droid ASC")) // 页头 h3
        assertNull(detail.posts.firstOrNull { it.isOp }) // p2 无主楼卡
    }

    // ---------- 登录 ----------

    @Test
    fun guest_home_islogin_zero_yields_null() {
        assertNull(KanxueParsers.memberName(doc("kanxue_home.htm")))
    }
}
