package com.boxhub.app.core.driver

import com.boxhub.app.core.discuz.ThreadListPage
import com.boxhub.app.core.discuz.result.DiscuzResult
import com.boxhub.app.core.discuz.result.ErrorKind
import com.boxhub.app.core.model.Board
import com.boxhub.app.core.model.ThreadDetail
import com.boxhub.app.core.network.SiteHttpGateway
import com.boxhub.app.data.site.SiteConfig

/**
 * V2EX 驱动（Phase C，只读 + 登录；写走 [ForumDriver] 默认 NotSupported）。
 * 凭证 = A2 cookie（WebView /signin 采集）。
 */
class V2exDriver(
    override val config: SiteConfig,
    private val http: SiteHttpGateway,
) : ForumDriver {

    override suspend fun boards(): DiscuzResult<List<Board>> = httpGet(config.baseUrl + "api/nodes/list.json?fields=name,title,topics,aliases&sort_by=topics&reverse=1")
        ?.let { resp ->
            if (resp.code != 200) return DiscuzResult.Failed(ErrorKind.Network, "nodes API HTTP ${resp.code}", retryable = true)
            runCatching { V2exParsers.nodes(resp.text(), config.baseUrl) }
                .fold(
                    { boards ->
                        if (boards.isEmpty()) DiscuzResult.Failed(ErrorKind.Parse, "no nodes", retryable = true)
                        else DiscuzResult.Ok(boards)
                    },
                    { DiscuzResult.Failed(ErrorKind.Parse, it.message, retryable = true) },
                )
        } ?: DiscuzResult.Failed(ErrorKind.Network, "network error", retryable = true)

    override suspend fun threadList(fid: String, page: Int): DiscuzResult<ThreadListPage> {
        // 首页（fid=""）无翻页；page>1 直接空页收束
        if (fid.isEmpty() && page > 1) {
            return DiscuzResult.Ok(ThreadListPage(emptyList(), page, 1))
        }
        val url = if (fid.isEmpty()) config.baseUrl else "${config.baseUrl}go/$fid?p=$page"
        val resp = httpGet(url) ?: return DiscuzResult.Failed(ErrorKind.Network, "network error", retryable = true)
        if (resp.code == 404) return DiscuzResult.Failed(ErrorKind.Parse, "404 board", retryable = false)
        if (resp.code != 200) return DiscuzResult.Failed(ErrorKind.Network, "HTTP ${resp.code}", retryable = true)
        return runCatching {
            V2exParsers.threadList(resp.jsoup(), config.id, fid, page, config.baseUrl)
        }.fold(
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
        val url = if (page <= 1) "${config.baseUrl}t/$tid" else "${config.baseUrl}t/$tid?p=$page"
        val resp = httpGet(url) ?: return DiscuzResult.Failed(ErrorKind.Network, "network error", retryable = true)
        if (resp.code == 404) return DiscuzResult.Failed(ErrorKind.Parse, "404 thread")
        if (resp.code != 200) return DiscuzResult.Failed(ErrorKind.Network, "HTTP ${resp.code}", retryable = true)
        return runCatching {
            V2exParsers.threadDetail(resp.jsoup(), config.id, tid, page, config.baseUrl)
        }.fold(
            { detail ->
                if (detail.posts.isEmpty()) DiscuzResult.Failed(ErrorKind.Parse, "no posts parsed", retryable = true)
                else DiscuzResult.Ok(detail)
            },
            { DiscuzResult.Failed(ErrorKind.Parse, it.message, retryable = true) },
        )
    }

    /** A2 cookie 缺席 = 游客（快路径）；在场则解析首页顶栏用户名 */
    override suspend fun loginUsername(): String? = runCatching {
        if (http.cookiesFor(config.baseUrl).keys.none { it == "A2" }) return null
        val doc = http.get(config.baseUrl).jsoup()
        V2exParsers.memberName(doc)
    }.getOrNull()

    private suspend fun httpGet(url: String): SiteHttpGateway.Page? =
        runCatching { http.get(url) }.getOrNull()
}
