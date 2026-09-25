package com.boxhub.app.data.site

import androidx.compose.ui.graphics.toArgb
import com.boxhub.app.ui.theme.BrandEnshan
import com.boxhub.app.ui.theme.BrandHassbian
import com.boxhub.app.ui.theme.BrandHistb
import com.boxhub.app.ui.theme.BrandKaixin
import com.boxhub.app.ui.theme.BrandKanxue
import com.boxhub.app.ui.theme.BrandMydigit
import com.boxhub.app.ui.theme.BrandV2ex
import com.boxhub.app.ui.theme.BrandZnds

/**
 * 五站注册表 —— M0 探测结果回填（见 probes/M0-SUMMARY.md）。
 * 全部 Discuz X3.4/X3.5、全 UTF-8、主题行统一 `tbody[id^=normalthread_]`。
 */
object SiteRegistry {

    val enshan = SiteConfig(
        id = "enshan",
        displayName = "恩山无线",
        baseUrl = "https://www.right.com.cn/forum/",
        domains = setOf("right.com.cn", "www.right.com.cn"),
        cookiePrefix = null, // 游客首页不发 Discuz cookie，登录时自动发现（参考实现: rHEX_2132）
        threadsPerPage = 20, // M0 实测 fid=176 第一页 20 行
        threadUrlPrefix = "thread",
        boardUrlPrefix = "forum", // 版块也是 rewrite: forum-{fid}-{p}.html
        mobileApi = MobileApiSupport.NONE, // forumindex 返回手机门户页
        brandColor = BrandEnshan.toArgb(),
        quirks = setOf(SiteQuirk.MOBILE_BIND_REQUIRED_FOR_PM, SiteQuirk.REWRITE_URLS, SiteQuirk.DESKTOP_UA_ONLY, SiteQuirk.FLAKY_WAF),
        probeNote = "阿里云 ESA/WAF (acw_tc)；发 PM 需手机绑定",
    )

    val hassbian = SiteConfig(
        id = "hassbian",
        displayName = "瀚思彼岸",
        baseUrl = "https://bbs.hassbian.com/",
        domains = setOf("bbs.hassbian.com", "hassbian.com", "hasstatic.com"), // hasstatic = 头像/附件 CDN
        cookiePrefix = "gXRl_2132", // Set-Cookie 实证
        threadsPerPage = 30, // M0 实测 ~29-30 行
        mobileApi = MobileApiSupport.FULL, // forumindex + forumdisplay 均通
        brandColor = BrandHassbian.toArgb(),
        quirks = setOf(SiteQuirk.REWRITE_URLS),
        probeNote = "HomeAssistant/智能家居论坛；无 WAF，最干净的一站",
    )

    val znds = SiteConfig(
        id = "znds",
        displayName = "ZNDS 智能电视网",
        baseUrl = "https://www.znds.com/",
        domains = setOf("znds.com", "www.znds.com", "dangbei.net"), // zndsimg.dangbei.net 图床
        cookiePrefix = "s9it_2132", // mobile API cookiepre 实证
        threadsPerPage = 50, // M0 实测 50 行
        threadUrlPrefix = "tv", // 自定义 rewrite: tv-{tid}-{p}-1.html
        mobileApi = MobileApiSupport.FULL,
        brandColor = BrandZnds.toArgb(),
        quirks = setOf(SiteQuirk.REWRITE_URLS, SiteQuirk.FLAKY_WAF),
        probeNote = "腾讯 EdgeOne 按 TLS 指纹拦 urllib(567)；浏览器指纹应放行，真机验证；头像走 uc.znds.com",
    )

    val kaixin = SiteConfig(
        id = "kaixin",
        displayName = "开心电视",
        baseUrl = "https://www.kaixindianshi.com/",
        domains = setOf("kaixindianshi.com", "www.kaixindianshi.com"),
        cookiePrefix = "ok8J_2132", // Set-Cookie 实证
        threadsPerPage = 20, // M0 实测 20 行
        mobileApi = MobileApiSupport.NONE, // CF 拦 API 路径
        brandColor = BrandKaixin.toArgb(),
        quirks = setOf(SiteQuirk.REWRITE_URLS, SiteQuirk.FLAKY_WAF),
        probeNote = "Cloudflare 间歇挑战；cf_clearance 可随 WebView 登录采集复用",
    )

    val mydigit = SiteConfig(
        id = "mydigit",
        displayName = "数码之家",
        baseUrl = "https://www.mydigit.cn/",
        domains = setOf("mydigit.cn", "www.mydigit.cn"),
        cookiePrefix = "VhUn_2132", // mobile API cookiepre 实证
        threadsPerPage = 50, // M2 实测 fid=2/48/51/56 四板块第一页均 50 行
        mobileApi = MobileApiSupport.FULL,
        brandColor = BrandMydigit.toArgb(),
        quirks = setOf(SiteQuirk.REWRITE_URLS),
        probeNote = "安全狗 security_session；fid=37 为空/分类板块",
    )

    val pojie = SiteConfig(
        id = "52pojie",
        displayName = "吾爱破解",
        baseUrl = "https://www.52pojie.cn/",
        domains = setOf("52pojie.cn", "www.52pojie.cn"),
        charset = "GBK", // 实测 meta charset=gbk —— 网关编码单点首个实战站
        cookiePrefix = "htVC_2132", // 参考实现同款
        threadsPerPage = 50, // fid=2/4/6 实测 43-48 行 + 分页 → 50
        mobileApi = MobileApiSupport.NONE,
        brandColor = 0xFF1E6FB8.toInt(), // README 蓝
        quirks = setOf(SiteQuirk.REWRITE_URLS, SiteQuirk.FLAKY_WAF),
        probeNote = "GBK；部分帖子需阅读权限 10（游客不可见）；WAF/JS challenge 走登录页过盾",
    )

    val chiphell = SiteConfig(
        id = "chiphell",
        displayName = "Chiphell",
        baseUrl = "https://www.chiphell.com/",
        domains = setOf("chiphell.com", "www.chiphell.com"),
        cookiePrefix = "v2x4_48dd", // 参考实现同款
        threadsPerPage = 30, // 参考实现：30F/页
        mobileApi = MobileApiSupport.NONE,
        brandColor = 0xFFA71F25.toInt(), // README 红
        quirks = setOf(SiteQuirk.REWRITE_URLS, SiteQuirk.FLAKY_WAF, SiteQuirk.DESKTOP_UA_ONLY),
        probeNote = "参考实现标 forumdefstyle=yes + Cloudflare；当前网络三连 000 不可达，" +
            "暂禁用聚合加载，待 WebView 登录页过盾后启用",
        enabled = false, // 不可达：不进聚合流（避免每次刷新 +20s 超时尾巴）
    )

    // ---------- Phase C 三新站（非 Discuz 引擎，只读 + 登录） ----------

    val v2ex = SiteConfig(
        id = "v2ex",
        displayName = "V2EX",
        baseUrl = "https://www.v2ex.com/",
        domains = setOf("v2ex.com", "www.v2ex.com"),
        engine = Engine.V2EX,
        authCookiePattern = "A2", // V2EX 登录凭证 cookie（参考实现同款）
        loginPath = "signin", // 服务端渲染登录页
        threadsPerPage = 0, // 首页/节点页行数不固定，驱动不换算
        brandColor = BrandV2ex.toArgb(),
        quirks = setOf(SiteQuirk.FLAKY_WAF),
        probeNote = "自研 HTML（fixture 2026-09）；列表 div.cell + a.topic-link，帖子 div#r_{pid}；" +
            "节点页 ?p=N 翻页；机房 IP 常被拦，失败走代理重试",
    )

    val histb = SiteConfig(
        id = "histb",
        displayName = "海纳思",
        baseUrl = "https://bbs.histb.com/",
        domains = setOf("bbs.histb.com", "histb.com"),
        engine = Engine.FLARUM,
        authCookiePattern = "forum_session", // Flarum 标准会话（参考实现同款）
        loginPath = "login", // 客户端路由登录页
        threadsPerPage = 20, // Flarum api page[limit]=20 实测
        brandColor = BrandHistb.toArgb(),
        probeNote = "Flarum JSON:API：discussions/posts/tags/me；标签即版块；正文 contentHtml；" +
            "单帖 include=firstPost 会 400（用 posts）",
    )

    val kanxue = SiteConfig(
        id = "kanxue",
        displayName = "看雪",
        baseUrl = "https://bbs.kanxue.com/",
        domains = setOf("bbs.kanxue.com", "kanxue.com", "www.kanxue.com", "passport.kanxue.com"),
        engine = Engine.KANXUE,
        authCookiePattern = "bbs_sid", // 会话 cookie（游客也有；登录判定靠 islogin/用户名校验兜底）
        loginPath = "user-login.htm", // 302 → passport.kanxue.com 统一登录
        threadsPerPage = 25, // 实测 1L+24F/页（p1 主楼+24，p2 25 行）
        brandColor = BrandKanxue.toArgb(),
        quirks = setOf(SiteQuirk.REWRITE_URLS),
        probeNote = "Xiuno BBS（fixture 2026-09）：tr.thread[data-tid] / tr.post[data-pid]；" +
            "最新流 new.htm；楼层时间相对（2天前）；浏览数 12w 万缩写",
    )

    val all: List<SiteConfig> = listOf(
        enshan, hassbian, znds, kaixin, mydigit, pojie, chiphell,
        v2ex, histb, kanxue,
    )

    fun byId(id: String): SiteConfig? = all.firstOrNull { it.id == id }
}
