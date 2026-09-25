package com.boxhub.app.data.site

import androidx.compose.ui.graphics.toArgb
import com.boxhub.app.ui.theme.BrandEnshan
import com.boxhub.app.ui.theme.BrandHassbian
import com.boxhub.app.ui.theme.BrandKaixin
import com.boxhub.app.ui.theme.BrandMydigit
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

    val all: List<SiteConfig> = listOf(enshan, hassbian, znds, kaixin, mydigit)

    fun byId(id: String): SiteConfig? = all.firstOrNull { it.id == id }
}
