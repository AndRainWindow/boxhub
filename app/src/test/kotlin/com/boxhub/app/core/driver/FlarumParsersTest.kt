package com.boxhub.app.core.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 海纳斯（Flarum）解析 fixture 测试（fixtures&#47;histb 目录，2026-09 录制）。
 */
class FlarumParsersTest {

    private val base = "https://bbs.histb.com/"

    private fun text(path: String): String = checkNotNull(
        javaClass.classLoader.getResourceAsStream(path),
    ) { "fixture missing: $path" }.use { String(it.readBytes(), Charsets.UTF_8) }

    // ---------- boards（标签即版块） ----------

    @Test
    fun tags_json_yields_pseudo_board_plus_tags() {
        val boards = FlarumParsers.boards(text("histb/tags.json"), base)
        // [0] 聚合流伪版块
        assertEquals("", boards[0].fid)
        assertEquals("全部讨论", boards[0].name)
        // 9 个实标签
        assertEquals(10, boards.size)
        val first = boards[1]
        assertEquals("histb", first.fid)
        assertEquals("作者固件发布专区", first.name)
        assertEquals(6, first.threads)
    }

    // ---------- 列表 ----------

    @Test
    fun discussions_json_parses_rows() {
        val page = FlarumParsers.threadList(text("histb/discussions.json"), "histb", "", 1, base)
        assertEquals(20, page.threads.size)
        val first = page.threads.first()
        assertEquals("4705", first.tid)
        assertTrue(first.title.contains("歌词魔盒"))
        assertEquals(6, first.replies)
        assertNotNull(first.lastPostAt)
        assertTrue(first.author.isNotBlank())
        assertNotNull(first.typeName)
        assertTrue(first.url.startsWith("${base}d/4705-"))
        // links.next 在场 → 后页未定（null = 允许继续下一页）
        assertNull(page.totalPages)
        // 置顶帖实测 isSticky=true
        assertTrue(page.threads.any { it.pinned })
    }

    // ---------- 详情（page1 = include=posts） ----------

    @Test
    fun detail_json_parses_all_floors() {
        val detail = FlarumParsers.threadDetail(text("histb/discussion_detail.json"), "histb", "4705", 1)
        assertTrue(detail.title.contains("歌词魔盒"))
        assertEquals(7, detail.totalFloors)
        assertEquals(1, detail.totalPages)
        assertEquals(7, detail.posts.size)
        // 楼层连续 1..7
        assertEquals((1..7).toList(), detail.posts.map { it.floor })
        val op = detail.posts.first()
        assertTrue(op.isOp)
        assertEquals("16412", op.pid)
        assertEquals("XtianUncle", op.author)
        assertNotNull(op.avatarUrl)
        assertNotNull(op.postedAt)
        assertTrue(op.bodyHtml.contains("歌词魔盒"))
        // number=1 即首楼（commentCount=6 → 总 7 楼）
        assertNull(detail.fid) // 只读引擎：无回帖入口
        assertEquals("XtianUncle", detail.author)
    }

    // ---------- /api/me ----------

    @Test
    fun me_json_yields_username_or_null() {
        assertEquals(
            "tester",
            FlarumParsers.usernameFromMe("""{"data":{"type":"users","id":"1","attributes":{"username":"tester","displayName":"Tester"}}}"""),
        )
        assertNull(FlarumParsers.usernameFromMe("""{"errors":[{"status":"401"}]}"""))
        assertNull(FlarumParsers.usernameFromMe("not json"))
    }
}
