package com.boxhub.app.core.discuz.parse

import com.boxhub.app.core.discuz.Endpoints
import com.boxhub.app.core.model.Attachment
import com.boxhub.app.core.model.Board
import com.boxhub.app.core.model.Post
import com.boxhub.app.core.model.ThreadDetail
import com.boxhub.app.core.model.ThreadSummary
import com.boxhub.app.data.site.SiteConfig
import java.time.LocalDateTime
import java.time.ZoneId
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Discuz HTML 解析（纯函数，JVM 可单测；fixtures 见 /fixtures/<site>/）。
 *
 * M0 实测的跨站契约：
 *  - 主题行: tbody[id^=normalthread_]（tid 在 id 里）；标题 a.xst
 *  - 楼层:   div[id^=post_] 包 table#pid{n}；正文 [id^=postmessage_{n}]
 *  - 楼层号: a[id^=postnum] > em；时间 em[id^=authorposton] 的 [title] 属性（绝对时间优先）
 *  - 标题:   #thread_subject（兜底 <title> 首段）
 */
object DiscuzParsers {

    // ---------- 主题列表 ----------

    fun threadList(
        doc: Document,
        config: SiteConfig,
        siteId: String,
        fid: String,
        page: Int,
    ): Pair<List<ThreadSummary>, Int?> {
        val rows = doc.select("tbody[id^=normalthread_], tbody[id^=stickthread_]")
        val threads = rows.mapNotNull { row -> parseThreadRow(row, config, siteId, fid, page) }
        return threads to parsePagerTotal(doc)
    }

    /** 定制皮肤统计图标计数：span 内含 i.{iconClass} → 取其中数字 */
    private fun iconCount(scope: Element?, iconClass: String): Int? {
        val el = scope?.selectFirst("span:has(i.$iconClass)") ?: return null
        return el.text().filter { it.isDigit() }.toIntOrNull()
    }

    private fun parseThreadRow(
        row: Element,
        config: SiteConfig,
        siteId: String,
        fid: String,
        page: Int,
    ): ThreadSummary? {
        val id = row.id().substringAfterLast('_')
        if (id.isEmpty()) return null
        val titleEl = row.selectFirst("a.xst") ?: row.selectFirst("strong.xst a") ?: return null
        val title = titleEl.text().trim()
        if (title.isEmpty()) return null

        val byCells = row.select("td.by")
        val authorCell = byCells.getOrNull(0)
        val lastCell = byCells.getOrNull(1)
        val th = row.selectFirst("th")

        // 定制皮肤（开心电视 dingzhi_2024）：无 td.by/td.num，作者时间在 th 的 .list_au_info
        val infoCell = th?.selectFirst(".list_au_info")

        val authorLink = authorCell?.selectFirst("cite a")
            ?: infoCell?.selectFirst("a[href*=space-uid]")
        val authorDateEl = authorCell?.selectFirst("em") ?: infoCell?.selectFirst("em")

        val numCell = row.selectFirst("td.num")
        // 皮肤差异：新皮肤 num = a.xi2(回复) + em(查看)；老皮肤 = em(回复) + strong(查看)；
        // 定制皮肤 = th .text-muted 内 fa-comment(回复) / fa-eye(查看)
        val replies = numCell?.let { n ->
            n.selectFirst("a.xi2")?.text()?.toIntOrNull()
                ?: n.selectFirst("em")?.text()?.toIntOrNull()
        } ?: iconCount(th, "fa-comment") ?: 0
        val views = numCell?.let { n ->
            n.selectFirst("strong")?.text()?.toIntOrNull()
                ?: n.selectFirst("em")?.text()?.takeIf { n.selectFirst("a.xi2") != null }?.toIntOrNull()
        } ?: iconCount(th, "fa-eye")
        val icn = row.selectFirst("td.icn")
        val icnTitle = (icn?.selectFirst("img")?.attr("alt") ?: "") + (icn?.selectFirst("img")?.attr("title") ?: "")

        return ThreadSummary(
            siteId = siteId,
            tid = id,
            title = title,
            author = authorLink?.text()?.trim() ?: "",
            authorUid = authorLink?.let { uidFromHref(it.attr("href")) },
            replies = replies,
            views = views,
            lastPostAt = lastCell?.selectFirst("em")?.let { parseDateTime(it) }
                ?: authorDateEl?.let { parseDateTime(it) },
            lastPoster = lastCell?.selectFirst("cite a")?.text()?.trim(),
            fid = fid,
            typeName = th?.selectFirst("em a[href*=typeid]")?.text(),
            pinned = row.id().startsWith("stickthread") || icnTitle.contains("置顶"),
            locked = icnTitle.contains("锁"),
            hasImage = th?.select(
                "img[alt=attach_img], img[src*=image_s.gif], i.fico-image, .vote_tip, img[title*=图片]"
            )?.isNotEmpty() ?: false,
            hasAttachment = th?.select("img[alt=attach_img], i.fico-attach, img[title*=附件]")
                ?.isNotEmpty() ?: false,
            highlightColor = parseHexColor(titleEl.attr("style")),
            url = Endpoints.absolute(config, titleEl.attr("href")),
        )
    }

    // ---------- 帖子页 ----------

    fun threadDetail(
        doc: Document,
        config: SiteConfig,
        siteId: String,
        tid: String,
        page: Int,
    ): ThreadDetail {
        val title = doc.selectFirst("#thread_subject")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.trim()?.substringBefore(" - ")?.substringBefore("｜")
            ?: ""

        val fid = doc.select("a[href*=forumdisplay]")
            .mapNotNull { a -> FORUMDISPLAY_FID.find(a.attr("href"))?.groupValues?.get(1) }
            .lastOrNull()

        val bodies = doc.select("[id^=postmessage_]")
        val posts = bodies.mapIndexed { index, bodyEl -> parsePost(bodyEl, config, index) }

        return ThreadDetail(
            siteId = siteId,
            tid = tid,
            title = title,
            fid = fid,
            author = posts.firstOrNull()?.author,
            totalFloors = null,
            page = page,
            totalPages = parsePagerTotal(doc),
            posts = posts,
        )
    }

    private fun parsePost(bodyEl: Element, config: SiteConfig, index: Int): Post {
        val pid = bodyEl.id().substringAfter("postmessage_")
        val container = bodyEl.closest("div[id^=post_], table[id^=pid]")
            ?: bodyEl.closest("div.viewbox, .plc")
            ?: bodyEl.parent()

        // 楼层号：a[id^=postnum] > em；定制皮肤（开心电视）postnum 文本为「楼主」/「N楼」
        val postnum = container?.selectFirst("a[id^=postnum]")
        val floor = postnum?.selectFirst("em")?.text()?.toIntOrNull()
            ?: postnum?.text()?.filter { it.isDigit() }?.takeIf { it.isNotBlank() }?.toIntOrNull()
            ?: postnum?.takeIf { it.text().contains("楼主") }?.let { 1 }
            ?: container?.selectFirst(".plc .pi strong a em, strong a em")?.text()?.toIntOrNull()
            ?: (index + 1)

        // 作者：按优先级取第一个「有非空文本」的候选（头像链接文本为空会被自然跳过）
        val authorEl = container?.select(
            "a[href*=space-uid], a[href*=?uid=], a[href*=&uid=], a.xw1, a.xw2"
        )?.firstOrNull { it.text().isNotBlank() && it.text() !in ignoredAuthorTexts }
        val uid = authorEl?.let { uidFromHref(it.attr("href")) }

        // 时间：严格按优先级（不可合并为逗号选择器——.pi em 会抢到楼层号）
        val timeEl = sequenceOf(
            container?.selectFirst("em[id^=authorposton]"),
            container?.selectFirst(".pti .authi em"),
            container?.selectFirst(".authi em[title], .authi em"),
            container?.selectFirst("em[title], span[title]"),
        ).filterNotNull()
        val postedAt = timeEl.firstNotNullOfOrNull { el ->
            parseDateTime(el).takeIf { it != null && it > 0 }
        }
        val avatarEl = container?.selectFirst(
            ".avatar img[src], .pls .avatar img, .viewavt img[src], img[src*=avatar]"
        )

        return Post(
            pid = pid,
            floor = floor,
            author = authorEl?.text()?.trim() ?: "",
            authorUid = uid,
            bodyHtml = bodyEl.html(),
            postedAt = postedAt,
            avatarUrl = avatarEl?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("javascript") }
                ?.let { Endpoints.absolute(config, it) }
                ?: uid?.let { Endpoints.avatar(config, it) },
            attachments = parseAttachments(container, config),
            quotePid = container?.let { QUOTE_PID.find(it.html())?.groupValues?.get(1) },
            isOp = floor == 1,
        )
    }

    /**
     * 附件提取，两种皮肤变体：
     *  1. 楼层内 `.pattl`（标准 X3，data/attachment 相对路径）
     *  2. 楼下 `dl.tattl` / `.attm`（瀚思等定制皮肤，图片以附件挂楼下）
     *     游客图 src=none.gif 占位，真实缩略图在 file/makefile 属性；大图在 nothumb 链接
     */
    private fun parseAttachments(container: Element?, config: SiteConfig): List<Attachment> {
        if (container == null) return emptyList()
        val out = LinkedHashMap<String, Attachment>()

        // 变体 2 优先：tattl（含 aimg）
        container.select("dl.tattl, .attm, .pattl").forEach { box ->
            // 大图链接：a[href*=attachment][href*=nothumb]
            val fullUrl = box.selectFirst("a[href*=attachment]")?.let { a ->
                val h = a.attr("abs:href").ifBlank { a.attr("href") }
                h.takeIf { it.isNotBlank() }
            }
            box.select("img").forEach { img ->
                val src = img.attr("src")
                val lazy = img.attr("data-original").ifBlank { img.attr("data-src") }
                val fileAttr = img.attr("file")
                val makefile = img.attr("makefile")
                val real = when {
                    src.isNotBlank() && !src.contains("none.gif") -> src
                    lazy.isNotBlank() -> lazy
                    fileAttr.isNotBlank() -> fileAttr
                    makefile.isNotBlank() -> makefile
                    else -> null
                } ?: return@forEach
                val url = if (real.startsWith("http")) real else Endpoints.absolute(config, real)
                val desc = img.attr("title").ifBlank { img.attr("alt") }
                out.putIfAbsent(
                    url,
                    Attachment(
                        url = url,
                        aid = img.attr("aid").ifBlank { null },
                        isImage = url.matches(IMG_EXT) || url.contains("attachment") ||
                            url.contains("data/") || url.contains("mod=image"),
                        description = desc,
                        fullUrl = fullUrl,
                    ),
                )
            }
            // 变体 1：pattl 内 data/attachment 相对路径
            ATTACH_PATH.findAll(box.html()).forEach { m ->
                val path = m.value.removePrefix("./")
                val url = Endpoints.absolute(config, path)
                out.putIfAbsent(
                    url,
                    Attachment(
                        url = url,
                        isImage = path.contains("gallery") || path.matches(IMG_EXT),
                        fullUrl = fullUrl,
                    ),
                )
            }
        }
        return out.values.toList()
    }

    // ---------- formhash / 登录态 ----------

    fun formHash(doc: Document): String? =
        doc.selectFirst("input[name=formhash]")?.attr("value")?.takeIf { it.isNotBlank() }
            ?: FORMHASH_FALLBACK.find(doc.html())?.groupValues?.get(1)

    /** 页面上是否显示已登录用户名，null=游客 */
    fun loggedInUsername(doc: Document): String? {
        // 首两个是 Discuz 标准登录名链接（参考实现同款，恩山实测必需）
        val candidates = listOf(
            "strong.vwmy a",      // 恩山实测：class 在 strong 上
            "strong.vwmy",
            "a.vwmy",
            "div#um p strong a[href*=space-uid]",  // 通用页头结构兜底
            "div#umenu a[href*=space]",
            "#userlinks a[href*=spacecp]",
            "#hd a[href*=space&do=profile]",
            "a[href*='mod=spacecp&ac=profile']",
            "#lsusername",
        )
        for (sel in candidates) {
            val el = doc.selectFirst(sel)?.text()?.trim()
            if (!el.isNullOrEmpty() && el !in setOf("登录", "注册", "个人中心", "站内搜索")) return el
        }
        return null
    }

    // ---------- 版块目录 ----------

    /** 从首页/分组页提取版块链接（两种形态：rewrite 与 query） */
    fun boardLinks(doc: Document, config: SiteConfig): List<Board> {
        val out = LinkedHashMap<String, Board>()
        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            val fid = BOARD_QUERY.find(href)?.groupValues?.get(1)
                ?: BOARD_REWRITE.find(href)?.groupValues?.get(1)
                ?: continue
            if (fid in out) continue
            val name = a.ownText().trim().ifEmpty { a.text().trim() }
            if (name.isEmpty() || name.length > 40) continue
            out[fid] = Board(
                fid = fid,
                name = name,
                url = Endpoints.absolute(config, href),
                threads = THREAD_COUNT.find(a.parent()?.text() ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            )
        }
        return out.values.toList()
    }

    /** 首页上的分组链接（恩山板块目录在 gid 页） */
    fun gids(doc: Document): List<String> =
        doc.select("a[href*=gid=]")
            .mapNotNull { GID.find(it.attr("href"))?.groupValues?.get(1) }
            .distinct()

    // ---------- 分页 ----------

    /** 解析总页数：label "n/m" → pager 链接最大页码；单页返回 1，未知返回 null */
    fun parsePagerTotal(doc: Document): Int? {
        for (label in doc.select(".pg label, .pgy label, .pages label")) {
            val t = label.text()
            PAGER_FRACTION.find(t)?.let { m ->
                return m.groupValues[2].toIntOrNull()?.takeIf { it >= 1 }
            }
        }
        var maxPage = 1
        for (a in doc.select(".pg a[href], .pgy a[href], .pages a[href]")) {
            val href = a.attr("href")
            val n = PAGE_QUERY.find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: PAGE_REWRITE_TAIL.find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: PAGE_REWRITE_BOARD.find(href)?.groupValues?.get(1)?.toIntOrNull()
            if (n != null && n > maxPage) maxPage = n
        }
        return if (maxPage > 1) maxPage else {
            // 无分页控件 → 单页（返回 1 而非 null 更符合调用方语义）
            if (doc.selectFirst(".pg, .pgy, .pages") == null) 1 else null
        }
    }

    // ---------- 工具 ----------

    fun uidFromHref(href: String): String? =
        UID_QUERY.find(href)?.groupValues?.get(1)
            ?: UID_SPACE.find(href)?.groupValues?.get(1)

    /**
     * Discuz 时间解析。候选优先级：元素 [title] 绝对时间 > 元素文本。
     * 支持: yyyy-M-d[ HH:mm[:ss]] / 今天|昨天|前天 HH:mm / N分钟前 / N小时前 / 刚刚
     */
    fun parseDateTime(el: Element, now: LocalDateTime = LocalDateTime.now()): Long? {
        val title = el.attr("title").trim()
        val text = el.text().trim()
        return parseDateTimeText(title, now) ?: parseDateTimeText(text, now)
    }

    fun parseDateTimeText(s: String?, now: LocalDateTime = LocalDateTime.now()): Long? {
        if (s.isNullOrBlank()) return null
        val t = s.trim()

        ABS_DATE.find(t)?.let { m ->
            val (y, mo, d) = m.groupValues.drop(1).take(3).map { it.toInt() }
            val h = m.groupValues[4].toIntOrNull() ?: 0
            val mi = m.groupValues[5].toIntOrNull() ?: 0
            val sec = m.groupValues[6].toIntOrNull() ?: 0
            return toEpoch(LocalDateTime.of(y, mo, d, h, mi, sec))
        }
        REL_DAY.find(t)?.let { m ->
            val dayOffset = when (m.groupValues[1]) {
                "今天" -> 0; "昨天" -> -1; "前天" -> -2; "大前天" -> -3; else -> return null
            }
            val h = m.groupValues[2].toInt(); val mi = m.groupValues[3].toInt()
            val day = now.toLocalDate().plusDays(dayOffset.toLong())
            return toEpoch(LocalDateTime.of(day, java.time.LocalTime.of(h, mi)))
        }
        MIN_AGO.find(t)?.let { m ->
            return toEpoch(now.minusMinutes(m.groupValues[1].toLong()))
        }
        HOUR_AGO.find(t)?.let { m ->
            return toEpoch(now.minusHours(m.groupValues[1].toLong()))
        }
        if (t.startsWith("刚刚")) return toEpoch(now)
        return null
    }

    private fun toEpoch(ldt: LocalDateTime): Long =
        ldt.atZone(ZoneId.systemDefault()).toEpochSecond()

    /** "#RRGGBB" → 0xFFRRGGBB（纯 Kotlin，JVM 单测可用） */
    fun parseHexColor(style: String): Int? =
        HEX_COLOR.find(style)?.let { m ->
            (0xFF shl 24) or m.value.substring(1).toLong(16).toInt()
        }

    /** 作者候选里应排除的界面词（统计/资料区的链接文本） */
    private val ignoredAuthorTexts = setOf("只看该作者", "查看详细资料", "发送私信", "加为好友", "楼主")

    private val FORUMDISPLAY_FID = Regex("forum\\.php\\?mod=forumdisplay&(?:amp;)?fid=(\\d+)")
    private val BOARD_QUERY = Regex("forum\\.php\\?mod=forumdisplay&(?:amp;)?fid=(\\d+)")
    private val BOARD_REWRITE = Regex("(?:^|/)forum-(\\d+)-\\d+\\.html")
    private val GID = Regex("[?&]gid=(\\d+)")
    private val QUOTE_PID = Regex("pid=(\\d{3,})")
    private val ATTACH_PATH = Regex("(?:\\.\\.?/)?data/attachment/[^\\s\"'<>]+")
    private val IMG_EXT = Regex(".*\\.(?:jpg|jpeg|png|gif|webp|bmp)$", RegexOption.IGNORE_CASE)
    private val FORMHASH_FALLBACK = Regex("formhash['\\\":\\s]+['\\\"]?([a-f0-9]{6,})")
    private val UID_QUERY = Regex("[?&]uid=(\\d+)")
    private val UID_SPACE = Regex("space-uid-(\\d+)")
    private val PAGER_FRACTION = Regex("(\\d+)\\s*/\\s*(\\d+)")
    private val PAGE_QUERY = Regex("[?&]page=(\\d+)")
    private val PAGE_REWRITE_TAIL = Regex("-\\d+-(\\d+)-1\\.html")   // thread-{tid}-{page}-1.html
    private val PAGE_REWRITE_BOARD = Regex("(?:^|/)forum-\\d+-(\\d+)\\.html") // forum-{fid}-{page}.html
    private val THREAD_COUNT = Regex("(\\d{1,7})\\s*(?:主题|帖)")
    private val ABS_DATE = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?")
    private val REL_DAY = Regex("(今天|昨天|前天|大前天)\\s*(\\d{1,2}):(\\d{2})")
    private val MIN_AGO = Regex("(\\d{1,3})\\s*分钟前")
    private val HOUR_AGO = Regex("(\\d{1,3})\\s*小时前")
    private val HEX_COLOR = Regex("#[0-9a-fA-F]{6}")
}
