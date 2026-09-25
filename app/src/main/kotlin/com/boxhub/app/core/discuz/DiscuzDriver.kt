package com.boxhub.app.core.discuz

import com.boxhub.app.core.discuz.parse.DiscuzParsers
import com.boxhub.app.core.model.Board
import com.boxhub.app.core.model.ThreadDetail
import com.boxhub.app.core.model.ThreadSummary
import com.boxhub.app.core.network.SiteHttpGateway
import com.boxhub.app.data.site.MobileApiSupport
import com.boxhub.app.data.site.SiteConfig
import com.boxhub.app.core.discuz.result.DiscuzResult
import com.boxhub.app.core.discuz.result.ErrorKind
import org.json.JSONObject

data class ThreadListPage(
    val threads: List<ThreadSummary>,
    val page: Int,
    val totalPages: Int?,
)

/**
 * Discuz 驱动（M2: 读取半边）。
 * L3 钩子：子类可覆盖 [interpretFailure]（如恩山「手机绑定」）、[parseThreadDetail] 等。
 */
open class DiscuzDriver(
    val config: SiteConfig,
    protected val http: SiteHttpGateway,
) {

    // ---------- 版块目录 ----------

    open suspend fun boards(): DiscuzResult<List<Board>> = runCatching {
        // 路径 A: mobile API（FULL 站：结构化、快）
        if (config.mobileApi == MobileApiSupport.FULL) {
            val page = http.get(Endpoints.mobileApi(config, "forumindex"))
            val parsed = parseForumIndexJson(page.text())
            if (parsed.isNotEmpty()) return DiscuzResult.Ok(parsed)
        }
        // 路径 B: HTML 首页直取
        val home = http.get(config.baseUrl)
        val homeDoc = home.jsoup()
        val seen = LinkedHashMap<String, Board>()
        DiscuzParsers.boardLinks(homeDoc, config).forEach { b -> seen.putIfAbsent(b.fid, b) }
        // 路径 C: gid 分组页（恩山板块目录在此）——与 B 合并而非提前 return，
        // 首页有链接时分组页也可能藏着首页没列出的板块
        for (gid in DiscuzParsers.gids(homeDoc).take(8)) {
            val gp = http.get(Endpoints.group(config, gid))
            DiscuzParsers.boardLinks(gp.jsoup(), config).forEach { b -> seen.putIfAbsent(b.fid, b) }
        }
        if (seen.isNotEmpty()) DiscuzResult.Ok(seen.values.toList())
        else DiscuzResult.Failed(ErrorKind.Parse, "no board links found", retryable = true)
    }.unwrapError()

    protected open fun parseForumIndexJson(text: String): List<Board> {
        val json = JSONObject(text)
        val list = json.optJSONObject("Variables")?.optJSONArray("forumlist") ?: return emptyList()
        val out = ArrayList<Board>(list.length())
        for (i in 0 until list.length()) {
            val f = list.getJSONObject(i)
            out += Board(
                fid = f.optString("fid"),
                name = f.optString("name"),
                description = f.optString("description"),
                threads = f.optString("threads").toIntOrNull() ?: 0,
                url = Endpoints.board(config, f.optString("fid")),
                // mobile API 是一层平铺；sub list 通过 fid 父子关系缺省为顶级展示
            )
        }
        return out
    }

    // ---------- 主题列表 ----------

    open suspend fun threadList(fid: String, page: Int): DiscuzResult<ThreadListPage> = runCatching {
        val resp = http.get(Endpoints.board(config, fid, page))
        if (resp.code == 404) return DiscuzResult.Failed(ErrorKind.Parse, "404 board", retryable = false)
        val (threads, total) = DiscuzParsers.threadList(resp.jsoup(), config, config.id, fid, page)
        if (threads.isEmpty() && page > 1) {
            DiscuzResult.Ok(ThreadListPage(emptyList(), page, total))
        } else if (threads.isEmpty()) {
            DiscuzResult.Failed(ErrorKind.Parse, "empty thread list", retryable = true)
        } else {
            DiscuzResult.Ok(ThreadListPage(threads, page, total))
        }
    }.unwrapError()

    // ---------- 帖子页 ----------

    open suspend fun threadDetail(tid: String, page: Int): DiscuzResult<ThreadDetail> = runCatching {
        val resp = http.get(Endpoints.thread(config, tid, page))
        if (resp.code == 404) return DiscuzResult.Failed(ErrorKind.Parse, "404 thread")
        val detail = DiscuzParsers.threadDetail(resp.jsoup(), config, config.id, tid, page)
        if (detail.posts.isEmpty()) {
            DiscuzResult.Failed(ErrorKind.Parse, "no posts parsed", retryable = true)
        } else {
            DiscuzResult.Ok(detail)
        }
    }.unwrapError()

    // ---------- 认证 ----------

    /** formhash（每次写操作前现取；M2 先不缓存） */
    open suspend fun formHash(): DiscuzResult<String> = runCatching {
        val resp = http.get(config.baseUrl)
        DiscuzParsers.formHash(resp.jsoup())?.let { DiscuzResult.Ok(it) }
            ?: DiscuzResult.Failed(ErrorKind.FormHashStale, "formhash not found", retryable = true)
    }.unwrapError()

    /** 当前登录用户名，null=游客 */
    open suspend fun loginUsername(): String? = runCatching {
        val cookieNames = http.cookiesFor(config.baseUrl).keys
        val resp = http.get(config.baseUrl)
        val doc = resp.jsoup()
        val name = DiscuzParsers.loggedInUsername(doc)
        val text = doc.text()
        android.util.Log.d(
            "BoxHubAcct",
            "loginUsername ${config.id}: code=${resp.code} finalUrl=${resp.finalUrl} " +
                "name=$name vwmy=${doc.selectFirst("a.vwmy") != null} " +
                "umenu=${doc.selectFirst("div#umenu") != null} " +
                "profileLink=${doc.selectFirst("a[href*=spacecp]") != null} " +
                "welcome=${text.contains("欢迎")} greet=${text.contains("欢迎您回来")} " +
                "logout=${text.contains("退出")} title=${doc.title()?.take(30)}",
        )
        name
    }.getOrNull()

    // ---------- L3 钩子 ----------

    /** 网络/解析异常 → 语义错误（子类可改判，如 WAF 特征文案） */
    protected open fun interpretFailure(e: Throwable): DiscuzResult.Failed =
        DiscuzResult.Failed(ErrorKind.Network, e.message, retryable = true)

    private fun <T> Result<DiscuzResult<T>>.unwrapError(): DiscuzResult<T> =
        fold({ it }, { interpretFailure(it) })
}
