package com.boxhub.app.core.discuz.parse

import com.boxhub.app.data.site.SiteConfig
import com.boxhub.app.data.site.SiteRegistry
import java.io.InputStreamReader
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析器 fixture 测试：M0/M2 录制的真实页面（/fixtures/<site>/）。
 * 每站至少覆盖 forumdisplay 与 viewthread 各一页。
 */
class ParserFixturesTest {

    private fun fixture(path: String) = checkNotNull(
        javaClass.classLoader.getResourceAsStream(path)
    ) { "fixture missing: $path" }.use { Jsoup.parse(it, "UTF-8", "https://fixture.local/") }

    private fun configOf(siteId: String): SiteConfig = checkNotNull(SiteRegistry.byId(siteId))

    // ---------- 主题列表 ----------

    @Test
    fun enshan_forumdisplay_parses_rows() = assertThreadList("enshan", 23)

    @Test
    fun hassbian_forumdisplay_parses_rows() = assertThreadList("hassbian", 37)

    @Test
    fun znds_forumdisplay_parses_rows() = assertThreadList("znds", 54)

    @Test
    fun kaixin_forumdisplay_parses_rows() = assertThreadList("kaixin", 24)

    @Test
    fun mydigit_forumdisplay_parses_rows() = assertThreadList("mydigit", 50) // fid=78 第一页

    private fun assertThreadList(siteId: String, expectedRows: Int) {
        val doc = fixture("$siteId/forumdisplay_p1.html")
        val config = configOf(siteId)
        val expected = doc.select("tbody[id^=normalthread_], tbody[id^=stickthread_]").size
        assertEquals("主题行数(含置顶)", expectedRows, expected)

        val (threads, totalPages) = DiscuzParsers.threadList(doc, config, siteId, "1", 1)
        assertEquals("解析线程数", expected, threads.size)

        val first = threads.first()
        assertTrue("tid: ${first.tid}", first.tid.matches(Regex("\\d+")))
        assertTrue("title: ${first.title}", first.title.isNotBlank())
        assertTrue("url: ${first.url}", first.url.contains(first.tid))
        assertTrue("author: ${first.author}", first.author.isNotBlank())
        assertTrue("replies >= 0", first.replies >= 0)
        // 时间：多数行应能解析（标题 title 属性或纯文本）
        val withTime = threads.count { it.lastPostAt != null }
        assertTrue("$siteId 解析到时间的行应过半（${withTime}/${threads.size}）", withTime > threads.size / 2)

        totalPages?.let { assertTrue("totalPages >= 1", it >= 1) }
    }

    @Test
    fun mydigit_parses_replies_views_and_highlight() {
        val doc = fixture("mydigit/forumdisplay_p1.html")
        val (threads, _) = DiscuzParsers.threadList(doc, configOf("mydigit"), "mydigit", "78", 1)
        val withReplies = threads.filter { it.replies > 0 }
        assertTrue("应有回复数>0 的帖子", withReplies.isNotEmpty())
        // mydigit 行内标题带 style color → 高亮色
        assertTrue("应解析到部分高亮色", threads.any { it.highlightColor != null })
    }

    @Test
    fun znds_tv_rewrite_urls_resolved() {
        val doc = fixture("znds/forumdisplay_p1.html")
        val (threads, _) = DiscuzParsers.threadList(doc, configOf("znds"), "znds", "556", 1)
        val t = threads.first()
        assertTrue("ZNDS tv- 前缀绝对链接: ${t.url}",
            t.url.startsWith("https://www.znds.com/tv-${t.tid}"))
    }

    // ---------- 帖子页 ----------

    @Test
    fun enshan_viewthread_parses_15_floors() = assertThreadDetail("enshan", 15)

    @Test
    fun kaixin_viewthread_parses_floors() = assertThreadDetail("kaixin", 5)

    @Test
    fun hassbian_viewthread_parses_floors() = assertThreadDetail("hassbian", 2)

    @Test
    fun znds_viewthread_parses_floors() = assertThreadDetail("znds", 3)

    @Test
    fun mydigit_viewthread_parses_floors() = assertThreadDetail("mydigit", 3)

    private fun assertThreadDetail(siteId: String, minPosts: Int) {
        val doc = fixture("$siteId/viewthread_p1.html")
        val config = configOf(siteId)
        val detail = DiscuzParsers.threadDetail(doc, config, siteId, "1", 1)

        assertTrue("$siteId title: ${detail.title}", detail.title.isNotBlank())
        assertTrue("$siteId posts ${detail.posts.size} >= $minPosts", detail.posts.size >= minPosts)

        val p0 = detail.posts.first()
        assertTrue("pid: ${p0.pid}", p0.pid.matches(Regex("\\d+")))
        assertEquals("首楼 floor=1", 1, p0.floor)
        assertTrue("正文非空", p0.bodyHtml.isNotBlank())
        assertTrue("首楼作者非空: '${p0.author}'", p0.author.isNotBlank())
        assertTrue("avatar 应解析", p0.avatarUrl != null)
    }

    @Test
    fun mydigit_post_time_from_title_attr() {
        val doc = fixture("mydigit/viewthread_p1.html")
        val detail = DiscuzParsers.threadDetail(doc, configOf("mydigit"), "mydigit", "622005", 1)
        val p0 = detail.posts.first()
        assertNotNull("首楼时间", p0.postedAt)
        // fixture 录制于 2026-09-25，首楼 title="2026-9-24 13:29:55"
        val d = java.time.LocalDateTime.ofEpochSecond(p0.postedAt!!, 0, java.time.ZoneId.systemDefault().rules.getOffset(java.time.Instant.ofEpochSecond(p0.postedAt!!)))
        assertEquals(2026, d.year)
        assertEquals(9, d.monthValue)
        assertEquals(24, d.dayOfMonth)
    }

    @Test
    fun znds_post_has_subject_and_pids() {
        val doc = fixture("znds/viewthread_p1.html")
        val detail = DiscuzParsers.threadDetail(doc, configOf("znds"), "znds", "1", 1)
        assertTrue("ZNDS 有 thread_subject", detail.title.isNotBlank())
        val pids = detail.posts.map { it.pid }.toSet()
        assertEquals("pid 唯一", detail.posts.size, pids.size)
    }

    // ---------- 表单与工具 ----------

    @Test
    fun formhash_present_in_pages() {
        for (site in listOf("enshan", "hassbian", "znds", "kaixin", "mydigit")) {
            val doc = fixture("$site/forumdisplay_p1.html")
            val fh = DiscuzParsers.formHash(doc)
            // 列表页通常带 formhash；若无，正则兜底也应为空而非乱码
            fh?.let { assertTrue("$site formhash hex: $it", it.matches(Regex("[a-f0-9]{6,}"))) }
        }
    }

    @Test
    fun datetime_parsing_matrix() {
        val now = java.time.LocalDateTime.of(2026, 9, 25, 12, 0)
        fun p(s: String) = DiscuzParsers.parseDateTimeText(s, now)
        assertNotNull("绝对日期时间", p("2026-9-24 13:29:55"))
        assertNotNull("纯日期", p("2026-9-24"))
        assertNotNull("今天", p("今天 11:30"))
        assertNotNull("昨天", p("昨天 19:28"))
        assertNotNull("分钟前", p("30分钟前"))
        assertNotNull("小时前", p("2小时前"))
        assertNotNull("刚刚", p("刚刚"))
        assertNullish(p("不是时间"))

        val yesterday = p("昨天 19:28")!!
        val expect = java.time.LocalDateTime.of(2026, 9, 24, 19, 28)
            .atZone(java.time.ZoneId.systemDefault()).toEpochSecond()
        assertEquals("昨天 19:28 精确", expect, yesterday)
    }

    private fun assertNullish(v: Long?) = assertTrue("应为 null", v == null)

    @Test
    fun board_links_from_group_page() {
        val doc = fixture("enshan/board_group.html")
        val boards = DiscuzParsers.boardLinks(doc, configOf("enshan"))
        assertTrue("恩山 gid 页应有板块: ${boards.size}", boards.size >= 3)
        assertTrue("板块名非空", boards.all { it.name.isNotBlank() })
        assertTrue("板块 fid 数字", boards.all { it.fid.matches(Regex("\\d+")) })
        assertFalse("不应重复", boards.map { it.fid }.toSet().size != boards.size)
    }

    @Test
    fun uid_extraction_forms() {
        assertEquals("134131", DiscuzParsers.uidFromHref("home.php?mod=space&uid=134131"))
        assertEquals("685610", DiscuzParsers.uidFromHref("space-uid-685610.html"))
        assertEquals("2", DiscuzParsers.uidFromHref("https://www.znds.com/home.php?mod=space&uid=2"))
        assertEquals(null, DiscuzParsers.uidFromHref("forum.php?mod=forumdisplay&fid=38"))
    }
}
