package com.boxhub.app.core.driver

import com.boxhub.app.core.discuz.ThreadListPage
import com.boxhub.app.core.discuz.parse.DiscuzParsers
import com.boxhub.app.core.model.Board
import com.boxhub.app.core.model.Post
import com.boxhub.app.core.model.ThreadDetail
import com.boxhub.app.core.model.ThreadSummary
import org.json.JSONArray
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * V2EX HTML 解析（Phase C；fixture 目录 fixtures&#47;v2ex）。
 *
 * 结构实测（2026-09，build/dom_v2ex*.txt）：
 *  - 列表行 = `div.cell`（类名常带 `t_{tid}`），标题 `a.topic-link[href=/t/{tid}#replyN]`，
 *    作者/末回复人在 `span.topic_info`，时间是 `[title="2026-09-25 14:01:34 +08:00"]`，
 *    回复数 `.count_livid|.count_orange`；首页与节点页同构（首页 topic-link 有重复，按 tid 去重）。
 *  - 帖子页：主楼在 `div.header`（h1 + small.gray 作者/时间/浏览）+ `div.topic_content`；
 *    回复是 `div#r_{pid}`，楼层号 `span.no`（回复从 1 计 → 模型楼层 = no + 1），
 *    时间 `span.ago[title]`，正文 `div.reply_content`。
 *  - 翻页 `?p=N`（仅 >100 回复时出现）；节点列表 JSON: /api/nodes/list.json。
 */
object V2exParsers {

    /** /api/nodes/list.json → 版块目录（按 topics 降序，截前 100 防设置页爆炸） */
    fun nodes(json: String, baseUrl: String): List<Board> {
        val arr = JSONArray(json)
        val out = ArrayList<Board>(minOf(arr.length(), 100))
        for (i in 0 until arr.length()) {
            if (out.size >= 100) break
            val n = arr.optJSONObject(i) ?: continue
            val name = n.optString("name")
            if (name.isEmpty()) continue
            out += Board(
                fid = name,
                name = n.optString("title").ifEmpty { name },
                description = "",
                threads = n.optInt("topics", 0),
                url = "${baseUrl}go/$name",
            )
        }
        return out
    }

    /**
     * 列表页（首页 fid="" / 节点页 /go/{fid} 同构）→ 主题行 + 总页数。
     * @return threads + totalPages（无 ?p= 翻页 = null，如首页）
     */
    fun threadList(doc: Document, siteId: String, fid: String, page: Int, baseUrl: String): ThreadListPage {
        val seen = LinkedHashSet<String>()
        val threads = ArrayList<ThreadSummary>()
        for (a in doc.select("a.topic-link")) {
            val href = a.attr("href")
            val tid = TID.find(href)?.groupValues?.get(1) ?: continue
            if (!seen.add(tid)) continue
            val cell = a.closest("div.cell") ?: a.parent()?.closest("div.cell") ?: continue
            threads += ThreadSummary(
                siteId = siteId,
                tid = tid,
                title = a.text().trim(),
                author = cellAuthor(cell),
                replies = cell.selectFirst(".count_livid, .count_orange")?.text()?.trim()?.toIntOrNull() ?: 0,
                lastPostAt = cellTime(cell),
                lastPoster = cellLastPoster(cell),
                fid = fid.ifEmpty { null },
                typeName = cell.selectFirst("a.node")?.text()?.trim()?.ifEmpty { null },
                url = "${baseUrl}t/$tid",
            )
        }
        return ThreadListPage(threads, page, totalPages(doc))
    }

    /** 帖子详情（page 1 全量；?p=N 时为该页） */
    fun threadDetail(doc: Document, siteId: String, tid: String, page: Int, baseUrl: String): ThreadDetail {
        val header = doc.selectFirst("div.header")
        val opAuthor = header?.selectFirst("small.gray a[href^=/member/], small a[href^=/member/]")
            ?.text()?.trim()?.ifEmpty { null }
        val opTime = header?.select("span[title]")
            ?.firstNotNullOfOrNull { DiscuzParsers.parseDateTimeText(it.attr("title")) }
        val posts = ArrayList<Post>()
        // 主楼：pid 用 tid（V2EX 无独立首楼 pid）
        doc.selectFirst("div.topic_content")?.let { content ->
            posts += Post(
                pid = tid,
                floor = 1,
                author = opAuthor ?: "",
                authorUid = header?.selectFirst("small a[href^=/member/]")?.attr("href")
                    ?.substringAfterLast("/")?.ifEmpty { null },
                bodyHtml = content.html(),
                postedAt = opTime,
                avatarUrl = header?.selectFirst(".fr img.avatar")?.attr("src")?.ifEmpty { null },
                isOp = true,
            )
        }
        for (cell in doc.select("div[id^=r_]")) {
            val pid = cell.id().removePrefix("r_")
            val floorNo = cell.selectFirst("span.no")?.text()?.trim()?.toIntOrNull() ?: continue
            val authorEl = cell.selectFirst("strong a[href^=/member/], a.dark[href^=/member/]")
            posts += Post(
                pid = pid,
                floor = floorNo + 1, // span.no 从 1 计（首楼 OP 不占号）→ 模型楼层 2L 起
                author = authorEl?.text()?.trim().orEmpty(),
                authorUid = authorEl?.attr("href")?.substringAfterLast("/")?.ifEmpty { null },
                bodyHtml = cell.selectFirst("div.reply_content")?.html().orEmpty(),
                postedAt = cell.selectFirst("span.ago[title], span[title]")?.let { DiscuzParsers.parseDateTime(it) },
                avatarUrl = cell.selectFirst("td img.avatar")?.attr("src")?.ifEmpty { null },
                isOp = false,
            )
        }
        val total = totalPages(doc)
        return ThreadDetail(
            siteId = siteId,
            tid = tid,
            title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.title().substringBefore(" - ").trim(),
            author = opAuthor,
            totalFloors = if (total == null) posts.size else null,
            page = page,
            totalPages = total,
            posts = posts,
        )
    }

    /** 登录态解析（调用方先查 A2 cookie）。游客首页有 `a[href=/signin]` → null */
    fun memberName(doc: Document): String? {
        if (doc.selectFirst("a[href=/signin]") != null) return null
        val href = doc.select("a[href^=/member/]").firstOrNull()?.attr("href") ?: return null
        return href.substringAfterLast("/").takeIf { it.isNotEmpty() && !it.contains('$') }
    }

    /** 翻页：?p=N 链接取最大页；无翻页（首页）→ null */
    private fun totalPages(doc: Document): Int? =
        doc.select("a[href*=p=]").mapNotNull { a ->
            PAGE.find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull()

    private fun cellAuthor(cell: Element): String =
        cell.selectFirst("span.topic_info strong a[href^=/member/]")?.text()?.trim().orEmpty()

    private fun cellLastPoster(cell: Element): String? =
        cell.select("span.topic_info a[href^=/member/]")
            .drop(1) // 第一个是楼主
            .lastOrNull()?.text()?.trim()?.ifEmpty { null }

    private fun cellTime(cell: Element): Long? =
        cell.select("span.topic_info span[title], span[title]")
            .firstNotNullOfOrNull { el ->
                DiscuzParsers.parseDateTimeText(el.attr("title"))
            }

    private val TID = Regex("""/t/(\d+)""")
    private val PAGE = Regex("""[?&]p=(\d+)""")
}
