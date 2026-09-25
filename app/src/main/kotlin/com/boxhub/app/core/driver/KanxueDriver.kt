package com.boxhub.app.core.driver

import com.boxhub.app.core.discuz.ThreadListPage
import com.boxhub.app.core.discuz.result.DiscuzResult
import com.boxhub.app.core.discuz.result.ErrorKind
import com.boxhub.app.core.model.Board
import com.boxhub.app.core.model.ThreadDetail
import com.boxhub.app.core.network.SiteHttpGateway
import com.boxhub.app.data.site.SiteConfig

/**
 * 看雪（Xiuno BBS）驱动（Phase C，只读 + 登录）。
 * 凭证 = bbs_sid（会话 cookie，登录经 passport.kanxue.com；采集判定见 [KanxueParsers.memberName]）。
 */
class KanxueDriver(
    override val config: SiteConfig,
    private val http: SiteHttpGateway,
) : ForumDriver {

    override suspend fun boards(): DiscuzResult<List<Board>> {
        val resp = httpGet(config.baseUrl) ?: return DiscuzResult.Failed(ErrorKind.Network, "network error", retryable = true)
        if (resp.code != 200) return DiscuzResult.Failed(ErrorKind.Network, "HTTP ${resp.code}", retryable = true)
        return runCatching { KanxueParsers.homeBoards(resp.jsoup(), config.baseUrl) }
            .fold(
                { boards ->
                    if (boards.size <= 1) DiscuzResult.Failed(ErrorKind.Parse, "no forum links", retryable = true)
                    else DiscuzResult.Ok(boards)
                },
                { DiscuzResult.Failed(ErrorKind.Parse, it.message, retryable = true) },
            )
    }

    override suspend fun threadList(fid: String, page: Int): DiscuzResult<ThreadListPage> {
        val url = when {
            fid == "new" && page <= 1 -> config.baseUrl + "new.htm"
            fid == "new" -> config.baseUrl + "new-$page.htm"
            else -> "${config.baseUrl}forum-$fid-$page.htm"
        }
        val resp = httpGet(url) ?: return DiscuzResult.Failed(ErrorKind.Network, "network error", retryable = true)
        if (resp.code == 404) return DiscuzResult.Failed(ErrorKind.Parse, "404 board", retryable = false)
        if (resp.code != 200) return DiscuzResult.Failed(ErrorKind.Network, "HTTP ${resp.code}", retryable = true)
        return runCatching { KanxueParsers.threadList(resp.jsoup(), config.id, fid, page, config.baseUrl) }
            .fold(
                { parsed ->
                    when {
                        parsed.threads.isNotEmpty() -> DiscuzResult.Ok(parsed)
                        page > 1 -> DiscuzResult.Ok(parsed)
                        else -> DiscuzResult.Failed(ErrorKind.Parse, "empty thread list", retryable = true)
                    }
                },
                { DiscuzResult.Failed(ErrorKind.Parse, it.message, retryable = true) },
            )
    }

    override suspend fun threadDetail(tid: String, page: Int): DiscuzResult<ThreadDetail> {
        val url = if (page <= 1) "${config.baseUrl}thread-$tid.htm"
        else "${config.baseUrl}thread-$tid-$page.htm"
        val resp = httpGet(url) ?: return DiscuzResult.Failed(ErrorKind.Network, "network error", retryable = true)
        if (resp.code == 404) return DiscuzResult.Failed(ErrorKind.Parse, "404 thread")
        if (resp.code != 200) return DiscuzResult.Failed(ErrorKind.Network, "HTTP ${resp.code}", retryable = true)
        return runCatching { KanxueParsers.threadDetail(resp.jsoup(), config.id, tid, page, config.baseUrl) }
            .fold(
                { detail ->
                    if (detail.posts.isEmpty()) DiscuzResult.Failed(ErrorKind.Parse, "no posts parsed", retryable = true)
                    else DiscuzResult.Ok(detail)
                },
                { DiscuzResult.Failed(ErrorKind.Parse, it.message, retryable = true) },
            )
    }

    /** 首页 `var islogin` 判登录；游客 → null */
    override suspend fun loginUsername(): String? = runCatching {
        val doc = http.get(config.baseUrl).jsoup()
        KanxueParsers.memberName(doc)
    }.getOrNull()

    private suspend fun httpGet(url: String): SiteHttpGateway.Page? =
        runCatching { http.get(url) }.getOrNull()
}
