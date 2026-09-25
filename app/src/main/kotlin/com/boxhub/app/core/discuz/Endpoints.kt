package com.boxhub.app.core.discuz

import com.boxhub.app.data.site.SiteConfig
import java.net.URLEncoder

/**
 * Discuz URL 构造（读路径）。
 * 三种站点形态统一在此收敛：
 *  - rewrite:   {prefix}-{id}-{page}-1.html（恩山 thread/forum；ZNDS thread 前缀为 tv）
 *  - query:     forum.php?mod=...&fid=..&page=..
 *  - mobile API: api/mobile/index.php?module=..&version=4
 */
object Endpoints {

    /** 版块主题列表页 */
    fun board(config: SiteConfig, fid: String, page: Int = 1): String =
        if (config.boardUrlPrefix.isNotEmpty() && config.quirks.contains(com.boxhub.app.data.site.SiteQuirk.REWRITE_URLS)) {
            "${config.baseUrl}${config.boardUrlPrefix}-$fid-$page.html"
        } else {
            "${config.baseUrl}forum.php?mod=forumdisplay&fid=$fid&page=$page"
        }

    /** 帖子页（rewrite 第 n 页） */
    fun thread(config: SiteConfig, tid: String, page: Int = 1): String =
        if (config.threadUrlPrefix.isNotEmpty() && config.quirks.contains(com.boxhub.app.data.site.SiteQuirk.REWRITE_URLS)) {
            "${config.baseUrl}${config.threadUrlPrefix}-$tid-$page-1.html"
        } else {
            "${config.baseUrl}forum.php?mod=viewthread&tid=$tid&page=$page"
        }

    /** 帖子页（按页内链接原样拼接，M2 主用） */
    fun absolute(config: SiteConfig, href: String): String = when {
        href.startsWith("http") -> href
        href.startsWith("/") -> origin(config) + href
        else -> config.baseUrl + href
    }

    /** 站点源（scheme://host[:port]，不含路径） */
    fun origin(config: SiteConfig): String {
        val rest = config.baseUrl.substringAfter("://")
        val host = rest.substringBefore("/").substringBefore("?")
        return "${config.baseUrl.substringBefore("://")}://$host"
    }

    /** 论坛首页 */
    fun home(config: SiteConfig): String = config.baseUrl

    /** 分组页（恩山板块目录在 gid 页） */
    fun group(config: SiteConfig, gid: String): String =
        "${config.baseUrl}forum.php?gid=$gid"

    /** mobile API */
    fun mobileApi(config: SiteConfig, module: String, params: Map<String, String> = emptyMap()): String =
        buildString {
            append(config.baseUrl).append("api/mobile/index.php?module=")
            append(module).append("&version=4")
            params.forEach { (k, v) -> append('&').append(k).append('=').append(encode(v)) }
        }

    /** 通知列表 */
    fun notices(config: SiteConfig, page: Int = 1): String =
        "${config.baseUrl}home.php?mod=space&do=notice&page=$page"

    /** 回帖表单页（拿 formhash/posttime/验证码 idhash） */
    fun replyForm(config: SiteConfig, fid: String, tid: String): String =
        "${config.baseUrl}forum.php?mod=post&action=reply&fid=$fid&tid=$tid"

    /** 发新帖表单页 */
    fun newThreadForm(config: SiteConfig, fid: String): String =
        "${config.baseUrl}forum.php?mod=post&action=newthread&fid=$fid"

    /**
     * 头像（UCenter 公式）：uid 左补零 9 位按 3/2/2/2 分段。
     * @param big true = big 后缀（默认 middle）
     */
    fun avatar(config: SiteConfig, uid: String, big: Boolean = false): String? {
        val u = uid.filter { it.isDigit() }
        if (u.isEmpty()) return null
        val padded = u.padStart(9, '0')
        val seg = "${padded.substring(0, 3)}/${padded.substring(3, 5)}/${padded.substring(5, 7)}/${padded.substring(7, 9)}"
        val ucBase = if (config.baseUrl.contains("/forum/")) {
            // 恩山等：uc_server 与 forum 同级（域名根）
            "${config.baseUrl.substringBefore("forum/")}uc_server/"
        } else {
            config.baseUrl + "uc_server/"
        }
        return "${ucBase}data/avatar/${seg}_avatar_${if (big) "big" else "middle"}.jpg"
    }

    fun encode(v: String): String = URLEncoder.encode(v, "UTF-8")
}
