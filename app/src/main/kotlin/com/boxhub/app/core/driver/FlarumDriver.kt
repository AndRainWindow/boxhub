package com.boxhub.app.core.driver

import com.boxhub.app.core.discuz.ThreadListPage
import com.boxhub.app.core.discuz.result.DiscuzResult
import com.boxhub.app.core.discuz.result.ErrorKind
import com.boxhub.app.core.model.Board
import com.boxhub.app.core.model.ThreadDetail
import com.boxhub.app.core.network.SiteHttpGateway
import com.boxhub.app.data.site.SiteConfig

/**
 * 海纳斯（Flarum JSON:API）驱动（Phase C，只读 + 登录）。
 * 凭证 = forum_session cookie（WebView /login 采集）。
 */
class FlarumDriver(
    override val config: SiteConfig,
    private val http: SiteHttpGateway,
) : ForumDriver {

    override suspend fun boards(): DiscuzResult<List<Board>> =
        getJson(config.baseUrl + "api/tags")?.let { (code, body) ->
            if (code != 200) return DiscuzResult.Failed(ErrorKind.Network, "tags HTTP $code", retryable = true)
            runCatching { FlarumParsers.boards(body, config.baseUrl) }
                .fold(
                    { b -> if (b.size <= 1) DiscuzResult.Failed(ErrorKind.Parse, "no tags", retryable = true) else DiscuzResult.Ok(b) },
                    { DiscuzResult.Failed(ErrorKind.Parse, it.message, retryable = true) },
                )
        } ?: DiscuzResult.Failed(ErrorKind.Network, "network error", retryable = true)

    override suspend fun threadList(fid: String, page: Int): DiscuzResult<ThreadListPage> {
        val offset = (page - 1) * 20
        val url = buildString {
            append(config.baseUrl).append("api/discussions?page%5Blimit%5D=20&page%5Boffset%5D=").append(offset)
            append("&include=users,tags,firstPost")
            if (fid.isNotEmpty()) append("&filter%5Btag%5D=").append(fid)
        }
        val (code, body) = getJson(url) ?: return DiscuzResult.Failed(ErrorKind.Network, "network error", retryable = true)
        if (code == 404) return DiscuzResult.Failed(ErrorKind.Parse, "404", retryable = false)
        if (code != 200) return DiscuzResult.Failed(ErrorKind.Network, "HTTP $code", retryable = true)
        return runCatching { FlarumParsers.threadList(body, config.id, fid, page, config.baseUrl) }
            .fold(
                { parsed ->
                    when {
                        parsed.threads.isNotEmpty() -> DiscuzResult.Ok(parsed)
                        page > 1 -> DiscuzResult.Ok(parsed)
                        else -> DiscuzResult.Failed(ErrorKind.Parse, "empty discussion list", retryable = true)
                    }
                },
                { DiscuzResult.Failed(ErrorKind.Parse, it.message, retryable = true) },
            )
    }

    override suspend fun threadDetail(tid: String, page: Int): DiscuzResult<ThreadDetail> {
        val url = if (page <= 1) {
            "${config.baseUrl}api/discussions/$tid?include=posts,user,users,tags"
        } else {
            val offset = (page - 1) * 20
            "${config.baseUrl}api/posts?filter%5Bdiscussion%5D=$tid&page%5Blimit%5D=20&page%5Boffset%5D=$offset"
        }
        val (code, body) = getJson(url) ?: return DiscuzResult.Failed(ErrorKind.Network, "network error", retryable = true)
        if (code == 404) return DiscuzResult.Failed(ErrorKind.Parse, "404 thread")
        if (code != 200) return DiscuzResult.Failed(ErrorKind.Network, "HTTP $code", retryable = true)
        val parsed = runCatching {
            if (page <= 1) FlarumParsers.threadDetail(body, config.id, tid, page)
            else FlarumParsers.threadDetailFromPosts(body, config.id, tid, page)
        }.getOrElse { return DiscuzResult.Failed(ErrorKind.Parse, it.message, retryable = true) }
        return if (parsed.posts.isEmpty()) {
            DiscuzResult.Failed(ErrorKind.Parse, "no posts parsed", retryable = true)
        } else {
            DiscuzResult.Ok(parsed)
        }
    }

    /** GET /api/me：200 → 用户名；401 → 游客 null */
    override suspend fun loginUsername(): String? = runCatching {
        val (code, body) = getJson(config.baseUrl + "api/me") ?: return null
        if (code != 200) return null
        FlarumParsers.usernameFromMe(body)
    }.getOrNull()

    private suspend fun getJson(url: String): Pair<Int, String>? = runCatching {
        val page = http.get(url)
        page.code to page.text()
    }.getOrNull()
}
