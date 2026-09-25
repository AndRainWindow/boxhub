package com.boxhub.app.core.driver

import com.boxhub.app.core.discuz.ThreadListPage
import com.boxhub.app.core.discuz.parse.DiscuzParsers
import com.boxhub.app.core.model.Board
import com.boxhub.app.core.model.Post
import com.boxhub.app.core.model.ThreadDetail
import com.boxhub.app.core.model.ThreadSummary
import java.time.LocalDateTime
import java.time.ZoneId
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 看雪（bbs.kanxue.com, Xiuno BBS）解析（Phase C；fixture: fixtures/kanxue_*）。
 *
 * 结构实测（2026-09，build/dom_kanxue.txt）：
 *  - 列表行 `tr.thread[data-tid]`（置顶带 top_N 类），标题 `div.subject a[href=thread-{tid}.htm]`，
 *    作者/末回复人 `a.username`，绝对时间 `span.date`（"・2026-1-6 13:20" 不补零），
 *    右侧计数 [浏览(12w 万缩写), 回复]；翻页 `ul.pagination`（forum-{fid}-{p}.htm / new--{p} 混排）。
 *  - 帖子页：主楼独立 `div.card.message_card`（无 pid，用 tid 作 pid），楼层 `tr.post[data-pid]`，
 *    楼层号 `span.floor`，时间 `span.date` 为相对时间（2天前 / 21小时前）。
 *  - 首页：最新列表入口 `new.htm`（与版块页同构 tr.thread），板块卡 `a[href^=forum-]`。
 *  - 登录态：服务端注入 `var islogin = '1'`（模板同时渲染 user-login/user-logout JS，不可作判据）。
 */
object KanxueParsers {

    /** 首页 → 版块目录：[0]=「最新主题」伪版块（聚合流源）+ 首页板块卡 */
    fun homeBoards(doc: Document, baseUrl: String): List<Board> {
        val out = mutableListOf(
            Board(fid = "new", name = "最新主题", description = "全站最新", url = baseUrl + "new.htm"),
        )
        val seen = mutableSetOf("new")
        for (a in doc.select("a[href^=forum-]")) {
            val fid = FORUM_ID.find(a.attr("href"))?.groupValues?.get(1) ?: continue
            if (!seen.add(fid)) continue
            val name = a.text().trim()
            if (name.isEmpty()) continue
            out += Board(fid = fid, name = name, url = baseUrl + "forum-$fid.htm")
        }
        return out
    }

    /** 列表页（new.htm / forum-{fid}-{p}.htm 同构） */
    fun threadList(doc: Document, siteId: String, fid: String, page: Int, baseUrl: String): ThreadListPage {
        val threads = ArrayList<ThreadSummary>()
        for (row in doc.select("tr.thread[data-tid]")) {
            val tid = row.attr("data-tid")
            if (tid.isEmpty()) continue
            val titleEl = row.select("div.subject a[href^=thread-]")
                .firstOrNull { it.text().trim().isNotEmpty() } ?: continue
            val counts = row.select("td.td-subject .text-right span")
                .map { it.text().trim() }
                .filter { COUNT_RE.matches(it) }
            val usernames = row.select("a.username")
            val dateText = row.selectFirst("td.td-subject span.date")?.text().orEmpty()
                .removePrefix("・").trim()
            threads += ThreadSummary(
                siteId = siteId,
                tid = tid,
                title = titleEl.text().trim(),
                author = usernames.firstOrNull()?.text()?.trim().orEmpty(),
                replies = counts.getOrNull(1)?.let { parseCount(it) } ?: 0,
                views = counts.getOrNull(0)?.let { parseCount(it) },
                lastPostAt = DiscuzParsers.parseDateTimeText(dateText),
                lastPoster = usernames.lastOrNull()?.text()?.trim()
                    ?.removePrefix("@")?.ifEmpty { null }
                    ?.takeIf { usernames.size > 1 },
                fid = fid.ifEmpty { null },
                pinned = row.classNames().any { it.startsWith("top_") },
                url = baseUrl + "thread-$tid.htm",
            )
        }
        return ThreadListPage(threads, page, totalPages(doc))
    }

    /** 帖子页（p1 含 message_card 主楼；p2+ 仅 tr.post 楼层） */
    fun threadDetail(doc: Document, siteId: String, tid: String, page: Int, baseUrl: String): ThreadDetail {
        val posts = ArrayList<Post>()
        // 主楼卡每页都渲染（p2 也含），仅 p1 收进 posts，保持翻页语义
        if (page <= 1) doc.selectFirst("div.card.message_card")?.let { card ->
            // 首个 user-home 链接是头像锚点（文本为空），取第一个带文本的
            val authorEl = card.select("a[href^=user-home-]").firstOrNull { it.text().isNotBlank() }
            posts += Post(
                pid = tid, // 主楼无 data-pid 锚点
                floor = 1,
                author = authorEl?.text()?.trim().orEmpty(),
                authorUid = authorEl?.attr("href")?.let { UID.find(it)?.groupValues?.get(1) },
                bodyHtml = card.selectFirst("div.message[isfirst], div.message")?.html().orEmpty(),
                postedAt = card.selectFirst("span.date")?.text()?.let { parseRelativeTime(it) },
                avatarUrl = card.selectFirst("a img")?.attr("src")?.let { abs(it, baseUrl) },
                isOp = true,
            )
        }
        for (row in doc.select("tr.post[data-pid]")) {
            val authorEl = row.selectFirst("a.username_box")
            val floor = row.selectFirst("span.floor")?.text()?.trim()?.toIntOrNull() ?: continue
            posts += Post(
                pid = row.attr("data-pid"),
                floor = floor,
                author = authorEl?.text()?.trim().orEmpty(),
                authorUid = authorEl?.attr("data-uid")?.ifEmpty { null }
                    ?: authorEl?.attr("href")?.let { UID.find(it)?.groupValues?.get(1) },
                bodyHtml = row.selectFirst("div.message.mt-2")?.html().orEmpty(),
                postedAt = row.selectFirst("span.date")?.text()?.let { parseRelativeTime(it) },
                avatarUrl = row.selectFirst("td.vtop img.avatar-3")?.attr("src")?.let { abs(it, baseUrl) },
                isOp = false,
            )
        }
        val total = totalPages(doc)
        val totalFloors = when {
            total == null && page <= 1 -> posts.size
            total != null && page >= total -> posts.lastOrNull()?.floor
            else -> null
        }
        val title = doc.selectFirst("h3.subject")?.text()?.trim()
            ?: doc.selectFirst("h3")?.text()?.trim()
            ?: ""
        return ThreadDetail(
            siteId = siteId,
            tid = tid,
            title = title,
            fid = null, // 只读引擎：无回帖入口
            author = posts.firstOrNull()?.takeIf { it.isOp }?.author?.ifEmpty { null },
            totalFloors = totalFloors,
            page = page,
            totalPages = total,
            posts = posts,
        )
    }

    /**
     * 登录态：`var islogin = '1'`（服务端按会话渲染）→ 解析导航区用户名；
     * 未登录/解析失败 → null。
     */
    fun memberName(doc: Document): String? {
        if (!ISLOGIN_ON.containsMatchIn(doc.html())) return null
        val el = doc.selectFirst(".nav_user_item a[href^=user-home-], nav a[href^=user-home-]")
        return el?.text()?.trim()?.ifEmpty { null }
    }

    /** 翻页：`-{p}.htm`（forum/thread）与 `--{p}`（new）两形态取最大页；无翻页 → null */
    private fun totalPages(doc: Document): Int? =
        doc.select("ul.pagination a[href]").mapNotNull { a ->
            val href = a.attr("href").substringBefore("?")
            PAGE_HTM.findAll(href).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
                ?: PAGE_DASH.findAll(href).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull()

    /** "12w"/"1.5万"/"129" → 展开整数 */
    private fun parseCount(s: String): Int? {
        val m = WAN_RE.find(s) ?: return s.toIntOrNull()
        val base = m.groupValues[1].toDoubleOrNull() ?: return null
        return (base * 10_000).toInt()
    }

    /** 绝对（不补零）或相对（21小时前/2天前/刚刚）→ epoch 秒 */
    private fun parseRelativeTime(text: String): Long? {
        val t = text.removePrefix("发表于:").trim().removePrefix("・").trim()
        DiscuzParsers.parseDateTimeText(t)?.let { return it }
        DAY_AGO.find(t)?.let { m ->
            val days = m.groupValues[1].toLong()
            return LocalDateTime.now().minusDays(days)
                .atZone(ZoneId.systemDefault()).toEpochSecond()
        }
        return null
    }

    private fun abs(src: String, baseUrl: String): String? = when {
        src.isEmpty() -> null
        src.startsWith("http") -> src
        src.startsWith("//") -> "https:$src"
        else -> baseUrl.trimEnd('/') + "/" + src.removePrefix("/")
    }?.ifEmpty { null }

    private val FORUM_ID = Regex("""forum-(\d+)""")
    private val UID = Regex("""user-home-(\d+)""")
    private val COUNT_RE = Regex("""^\d+(?:\.\d+)?[wW万]?$""")
    private val WAN_RE = Regex("""^(\d+(?:\.\d+)?)[wW万]$""")
    private val PAGE_HTM = Regex("""-(\d+)\.htm""")
    private val PAGE_DASH = Regex("""--(\d+)$""")
    private val DAY_AGO = Regex("""(\d+)\s*天前""")
    private val ISLOGIN_ON = Regex("""var islogin\s*=\s*['"]1['"]""")
}
