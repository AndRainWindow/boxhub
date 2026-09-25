package com.boxhub.app.core.driver

import com.boxhub.app.core.discuz.ThreadListPage
import com.boxhub.app.core.model.Board
import com.boxhub.app.core.model.Post
import com.boxhub.app.core.model.ThreadDetail
import com.boxhub.app.core.model.ThreadSummary
import java.time.OffsetDateTime
import org.json.JSONArray
import org.json.JSONObject

/**
 * 海纳斯（bbs.histb.com, Flarum 2.x JSON:API）解析（Phase C；fixture 目录 fixtures&#47;histb）。
 *
 * 实测接口（2026-09）：
 *  - 列表: `api/discussions?page[limit]=20&include=users,tags,firstPost`（含 firstPost 展开）
 *    —— 单帖端点 `api/discussions/{id}?include=firstPost,...` 会 400，帖内 include 用 posts。
 *  - 详情: `api/discussions/{id}?include=posts,user,users,tags`（含全部楼层，number 即楼层号）
 *  - 翻页: `api/posts?filter[discussion]={id}&page[limit]=20&page[offset]=N`，JSON:API links.next。
 *  - 版块 = 标签: `api/tags`。
 * 正文为渲染后的 contentHtml；时间 ISO8601 +00:00。
 */
object FlarumParsers {

    private const val PAGE_LIMIT = 20

    /** api/tags → 版块目录；[0] 为聚合流用的「全部讨论」伪版块（fid=""）。兼容裸数组与 {"data":[]} 包装 */
    fun boards(json: String, baseUrl: String): List<Board> {
        val arr = if (json.trimStart().startsWith("[")) JSONArray(json)
        else JSONObject(json).optJSONArray("data") ?: JSONArray()
        val out = ArrayList<Board>(arr.length() + 1)
        out += Board(fid = "", name = "全部讨论", description = "站点最新", url = baseUrl)
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            if (t.optString("type") != "tags") continue
            val a = t.optJSONObject("attributes") ?: continue
            val slug = a.str("slug") ?: continue
            out += Board(
                fid = slug,
                name = a.str("name") ?: slug,
                description = a.str("description").orEmpty(),
                threads = a.str("discussionCount")?.toIntOrNull() ?: 0,
                url = baseUrl,
            )
        }
        return out
    }

    /** discussions 列表 JSON → 主题行 */
    fun threadList(json: String, siteId: String, fid: String, page: Int, baseUrl: String): ThreadListPage {
        val root = JSONObject(json)
        val data = root.optJSONArray("data") ?: JSONArray()
        val inc = root.optJSONArray("included") ?: JSONArray()
        val users = usersById(inc)
        val tags = tagsById(inc)
        val threads = ArrayList<ThreadSummary>(data.length())
        for (i in 0 until data.length()) {
            val d = data.optJSONObject(i) ?: continue
            if (d.optString("type") != "discussions") continue
            val id = d.optString("id")
            val a = d.optJSONObject("attributes") ?: continue
            val user = d.relId("user")?.let { users[it] }
            val lastBy = d.relId("lastPostedUser")?.let { users[it] }
            val tagId = d.relIds("tags").firstOrNull()
            threads += ThreadSummary(
                siteId = siteId,
                tid = id,
                title = a.str("title").orEmpty(),
                author = user?.name.orEmpty(),
                replies = a.str("commentCount")?.toIntOrNull() ?: 0,
                lastPostAt = isoToEpoch(a.str("lastPostedAt")),
                lastPoster = lastBy?.name,
                fid = fid.ifEmpty { null },
                typeName = tagId?.let { tags[it] },
                pinned = a.bool("isSticky"),
                locked = a.bool("isLocked"),
                url = "${baseUrl}d/${a.str("slug") ?: id}",
            )
        }
        // links.next 在场 = 后页未定（PageCapsule 对 null 恒许下一页）；缺席 = 本页即末页
        val hasNext = root.optJSONObject("links")?.has("next") == true
        return ThreadListPage(threads, page, if (hasNext) null else page)
    }

    /** 详情 JSON（page 1，include=posts）→ 帖子详情 */
    fun threadDetail(json: String, siteId: String, tid: String, page: Int): ThreadDetail {
        val root = JSONObject(json)
        val d = root.optJSONObject("data")
            ?: return ThreadDetail(siteId = siteId, tid = tid, title = "", page = page)
        val a = d.optJSONObject("attributes") ?: JSONObject()
        val inc = root.optJSONArray("included") ?: JSONArray()
        val users = usersById(inc)
        val posts = ArrayList<Post>()
        for (i in 0 until inc.length()) {
            val p = inc.optJSONObject(i) ?: continue
            if (p.optString("type") != "posts") continue
            val pa = p.optJSONObject("attributes") ?: continue
            val uid = p.relId("user")
            val user = uid?.let { users[it] }
            val number = pa.str("number")?.toIntOrNull() ?: continue
            posts += Post(
                pid = p.optString("id"),
                floor = number,
                author = user?.name.orEmpty(),
                authorUid = uid,
                bodyHtml = pa.str("contentHtml").orEmpty(),
                postedAt = isoToEpoch(pa.str("createdAt")),
                avatarUrl = user?.avatar,
                isOp = number == 1,
            )
        }
        posts.sortBy { it.floor }
        val totalPosts = (a.str("commentCount")?.toIntOrNull()?.plus(1)) ?: posts.size
        return ThreadDetail(
            siteId = siteId,
            tid = tid,
            title = a.str("title").orEmpty(),
            fid = null, // 只读引擎：无回帖入口（ThreadScreen FAB 依赖 fid）
            author = d.relId("user")?.let { users[it]?.name },
            totalFloors = totalPosts,
            page = page,
            totalPages = pageCount(totalPosts),
            posts = posts,
        )
    }

    /** 翻页 JSON（api/posts，page>1）→ 该页楼层；讨论元数据从 included.discussions 补 */
    fun threadDetailFromPosts(json: String, siteId: String, tid: String, page: Int): ThreadDetail {
        val root = JSONObject(json)
        val inc = root.optJSONArray("included") ?: JSONArray()
        val users = usersById(inc)
        var title = ""
        var totalPosts: Int? = null
        var author: String? = null
        for (i in 0 until inc.length()) {
            val d = inc.optJSONObject(i) ?: continue
            if (d.optString("type") != "discussions") continue
            val da = d.optJSONObject("attributes") ?: continue
            title = da.str("title").orEmpty()
            totalPosts = (da.str("commentCount")?.toIntOrNull()?.plus(1))
            author = d.relId("user")?.let { users[it]?.name } ?: author
        }
        val data = root.optJSONArray("data") ?: JSONArray()
        val posts = ArrayList<Post>(data.length())
        for (i in 0 until data.length()) {
            val p = data.optJSONObject(i) ?: continue
            if (p.optString("type") != "posts") continue
            val pa = p.optJSONObject("attributes") ?: continue
            val uid = p.relId("user")
            val user = uid?.let { users[it] }
            val number = pa.str("number")?.toIntOrNull() ?: continue
            posts += Post(
                pid = p.optString("id"),
                floor = number,
                author = user?.name.orEmpty(),
                authorUid = uid,
                bodyHtml = pa.str("contentHtml").orEmpty(),
                postedAt = isoToEpoch(pa.str("createdAt")),
                avatarUrl = user?.avatar,
                isOp = number == 1,
            )
        }
        posts.sortBy { it.floor }
        return ThreadDetail(
            siteId = siteId,
            tid = tid,
            title = title,
            fid = null,
            author = author,
            totalFloors = totalPosts,
            page = page,
            totalPages = totalPosts?.let { pageCount(it) },
            posts = posts,
        )
    }

    /** GET /api/me → 用户名；401/游客 → null */
    fun usernameFromMe(json: String): String? =
        runCatching {
            JSONObject(json).optJSONObject("data")?.optJSONObject("attributes")?.str("username")
        }.getOrNull()

    // ---------- helpers ----------

    private data class User(val name: String?, val avatar: String?)

    private fun usersById(inc: JSONArray): Map<String, User> {
        val out = HashMap<String, User>(8)
        for (i in 0 until inc.length()) {
            val u = inc.optJSONObject(i) ?: continue
            if (u.optString("type") != "users") continue
            val a = u.optJSONObject("attributes") ?: continue
            out[u.optString("id")] = User(
                name = a.str("displayName") ?: a.str("username"),
                avatar = a.str("avatarUrl")?.let { if (it.startsWith("//")) "https:$it" else it },
            )
        }
        return out
    }

    private fun tagsById(inc: JSONArray): Map<String, String> {
        val out = HashMap<String, String>(4)
        for (i in 0 until inc.length()) {
            val t = inc.optJSONObject(i) ?: continue
            if (t.optString("type") != "tags") continue
            val a = t.optJSONObject("attributes") ?: continue
            out[t.optString("id")] = a.str("name") ?: continue
        }
        return out
    }

    /** JSON:API 关系在 `relationships.{key}.data`（不是顶层键） */
    private fun JSONObject.rel(key: String): JSONObject? =
        optJSONObject("relationships")?.optJSONObject(key)

    private fun JSONObject.relId(key: String): String? =
        rel(key)?.optJSONObject("data")?.optString("id")?.takeIf { it.isNotEmpty() }

    private fun JSONObject.relIds(key: String): List<String> {
        val arr = rel(key)?.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotEmpty() } }
    }

    /** Flarum 属性可能是字符串/数字/布尔（站点与版本差异）→ 统一取串 */
    private fun JSONObject.str(key: String): String? = when (val v = opt(key)) {
        null, JSONObject.NULL -> null
        is String -> v.takeIf { it.isNotEmpty() }
        is Number -> v.toString()
        else -> v.toString()
    }

    private fun JSONObject.bool(key: String): Boolean = when (val v = opt(key)) {
        is Boolean -> v
        is String -> v.equals("true", true) || v == "1"
        else -> false
    }

    private fun isoToEpoch(s: String?): Long? = s?.let {
        runCatching { OffsetDateTime.parse(it).toEpochSecond() }.getOrNull()
    }

    private fun pageCount(totalPosts: Int): Int = (totalPosts + PAGE_LIMIT - 1) / PAGE_LIMIT
}
