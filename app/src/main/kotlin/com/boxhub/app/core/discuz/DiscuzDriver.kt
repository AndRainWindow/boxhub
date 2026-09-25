package com.boxhub.app.core.discuz

import com.boxhub.app.core.discuz.parse.DiscuzParsers
import com.boxhub.app.core.driver.ForumDriver
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
    override val config: SiteConfig,
    protected val http: SiteHttpGateway,
) : ForumDriver {

    // ---------- 版块目录 ----------

    override suspend fun boards(): DiscuzResult<List<Board>> = runCatching {
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

    override suspend fun threadList(fid: String, page: Int): DiscuzResult<ThreadListPage> = runCatching {
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

    override suspend fun threadDetail(tid: String, page: Int): DiscuzResult<ThreadDetail> = runCatching {
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
    override suspend fun loginUsername(): String? = runCatching {
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

    // ---------- M4 写操作 ----------

    /** 客户端发帖冷却（每站实例独立） */
    private var lastSubmitAt = 0L

    /**
     * 回帖表单：GET 表单页 → 解析全部提交要素。
     * @param pid 非空 = 引用该楼（Discuz repquote 会在表单里预填引用三兄弟）
     */
    override suspend fun prepareReply(
        fid: String,
        tid: String,
        pid: String?,
    ): DiscuzResult<com.boxhub.app.core.model.ReplyContext> = runCatching {
        val url = Endpoints.replyForm(config, fid, tid, pid)
        val resp = http.get(url, referer = Endpoints.thread(config, tid, 1))
        val html = resp.text()
        when {
            html.contains("您需要先登录") || html.contains("请先登录后") ->
                return DiscuzResult.SessionExpired
            html.contains("waf_slider_verify") || html.contains("enable JavaScript and refresh") ->
                return DiscuzResult.Failed(
                    com.boxhub.app.core.discuz.result.ErrorKind.RateLimited,
                    "站点防护挑战，请稍后重试或通过登录页过盾",
                    retryable = true,
                )
        }
        val ctx = DiscuzParsers.parseReplyForm(resp.jsoup(), fid, tid)
            ?: return DiscuzResult.Failed(
                com.boxhub.app.core.discuz.result.ErrorKind.FormHashStale,
                "回帖表单解析失败（缺少 formhash/posttime）",
                retryable = true,
            )
        DiscuzResult.Ok(ctx)
    }.unwrapError()

    /** 回帖提交。含 15s 客户端冷却；响应经三态判读（成功/需验证码/错误分类）。 */
    override suspend fun submitReply(
        ctx: com.boxhub.app.core.model.ReplyContext,
        message: String,
        images: List<com.boxhub.app.core.model.Attachment>,
        captcha: com.boxhub.app.core.model.CaptchaInput?,
    ): DiscuzResult<com.boxhub.app.core.model.PostReceipt> = runCatching {
        val elapsed = System.currentTimeMillis() - lastSubmitAt
        val cooldownMs = config.postCooldownSeconds * 1000L
        if (lastSubmitAt > 0 && elapsed < cooldownMs) {
            return DiscuzResult.Failed(
                com.boxhub.app.core.discuz.result.ErrorKind.Cooldown,
                "两次发送需间隔 ${config.postCooldownSeconds} 秒（还差 ${(cooldownMs - elapsed + 999) / 1000}s）",
                retryable = true,
            )
        }

        val fields = mutableListOf(
            "formhash" to ctx.formhash,
            "posttime" to ctx.posttime,
            "wysiwyg" to "0",
            "message" to message,
            "replysubmit" to "yes",
        )
        ctx.noticeAuthor?.let { fields += "noticeauthor" to it }
        ctx.noticeTrimStr?.let { fields += "noticetrimstr" to it }
        ctx.noticeAuthoMsg?.let { fields += "noticeauthormsg" to it }
        captcha?.fields?.forEach { (k, v) -> if (v.isNotBlank()) fields += k to v }
        images.mapNotNull { it.aid }.forEach { aid ->
            fields += "attachnew[$aid][description]" to ""
        }

        val resp = http.postForm(
            url = Endpoints.replySubmit(config, ctx.fid, ctx.tid),
            fields = fields,
            referer = Endpoints.replyForm(config, ctx.fid, ctx.tid),
        )
        if (resp.code == 413) {
            return DiscuzResult.Failed(
                com.boxhub.app.core.discuz.result.ErrorKind.ContentBlocked,
                "内容过大（413）",
            )
        }
        when (val outcome = DiscuzParsers.interpretWriteResponse(resp.text())) {
            is DiscuzParsers.WriteOutcome.Success -> {
                lastSubmitAt = System.currentTimeMillis()
                DiscuzResult.Ok(
                    com.boxhub.app.core.model.PostReceipt(outcome.pid, "回复发布成功"),
                )
            }
            is DiscuzParsers.WriteOutcome.NeedsCaptcha -> {
                lastSubmitAt = System.currentTimeMillis()
                DiscuzResult.Failed(
                    com.boxhub.app.core.discuz.result.ErrorKind.SeccodeRequired,
                    outcome.message,
                    retryable = true,
                )
            }
            is DiscuzParsers.WriteOutcome.Fail -> DiscuzResult.Failed(
                outcome.kind, outcome.message,
                retryable = outcome.kind == com.boxhub.app.core.discuz.result.ErrorKind.Cooldown,
            )
            DiscuzParsers.WriteOutcome.SessionExpired -> DiscuzResult.SessionExpired
        }
    }.unwrapError()

    /** 图片上传（swfupload）；uid/uploadHash 来自 ReplyContext */
    override suspend fun uploadImage(
        ctx: com.boxhub.app.core.model.ReplyContext,
        bytes: ByteArray,
        filename: String,
        mime: String,
    ): DiscuzResult<com.boxhub.app.core.model.Attachment> = runCatching {
        val uid = ctx.uid
        val hash = ctx.uploadHash
        if (uid.isNullOrBlank() || hash.isNullOrBlank()) {
            return DiscuzResult.Failed(
                com.boxhub.app.core.discuz.result.ErrorKind.Parse,
                "缺少上传令牌（uid/hash），该站可能不支持附件上传",
            )
        }
        val resp = http.postMultipart(
            url = Endpoints.uploadImage(config, uid, hash),
            fields = mapOf("uid" to uid, "hash" to hash),
            fileField = "Filedata",
            filename = filename,
            mime = mime,
            fileBytes = bytes,
            referer = config.baseUrl,
        )
        if (resp.code == 413) {
            return DiscuzResult.Failed(
                com.boxhub.app.core.discuz.result.ErrorKind.ContentBlocked,
                "图片过大（413）",
            )
        }
        val text = resp.text()
        val (aid, errOrPath) = DiscuzParsers.parseUploadResponse(text)
        when {
            aid != null -> DiscuzResult.Ok(
                com.boxhub.app.core.model.Attachment(
                    url = "${config.baseUrl}forum.php?mod=attachment&aid=$aid",
                    aid = aid, isImage = true, description = filename,
                ),
            )
            errOrPath?.startsWith("PATH:") == true -> DiscuzResult.Ok(
                com.boxhub.app.core.model.Attachment(
                    url = Endpoints.absolute(config, errOrPath.removePrefix("PATH:")),
                    isImage = true, description = filename,
                ),
            )
            errOrPath?.startsWith("URL:") == true -> DiscuzResult.Ok(
                com.boxhub.app.core.model.Attachment(
                    url = errOrPath.removePrefix("URL:"),
                    isImage = true, description = filename,
                ),
            )
            else -> DiscuzResult.Failed(
                com.boxhub.app.core.discuz.result.ErrorKind.Parse,
                "上传响应无法解析: ${text.take(120)}",
                retryable = true,
            )
        }
    }.unwrapError()

    // ---------- L3 钩子 ----------

    /** 网络/解析异常 → 语义错误（子类可改判，如 WAF 特征文案） */
    protected open fun interpretFailure(e: Throwable): DiscuzResult.Failed =
        DiscuzResult.Failed(ErrorKind.Network, e.message, retryable = true)

    private fun <T> Result<DiscuzResult<T>>.unwrapError(): DiscuzResult<T> =
        fold({ it }, { interpretFailure(it) })
}
