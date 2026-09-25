package com.boxhub.app.data.site

import androidx.compose.runtime.Immutable

/**
 * 站点配置 —— 站点差异的 L1 层（纯数据）。
 * 新增一个站 = 在 [SiteRegistry] 加一条 + 必要时一个 [com.boxhub.app.driver] 子类 + fixtures。
 */
@Immutable
data class SiteConfig(
    /** 稳定标识，如 "enshan"；同时是 Room/存储的 key */
    val id: String,
    val displayName: String,
    /** 含路径的基址，尾部带 "/" */
    val baseUrl: String,
    /** cookie 域与链接归一化（含不带 www 的形态） */
    val domains: Set<String>,
    /** 站点编码：M0 实测五站全为 UTF-8 */
    val charset: String = "UTF-8",
    /** 桌面 UA（伪装；恩山等站移动端 UA 会拿到差页面） */
    val userAgent: String = DesktopChromeUa,
    /**
     * cookie 前缀（如 "rHEX_2132"）。null = 登录时按 (\w+)_(2132|...)_(auth|saltkey) 自动发现。
     * 恩山游客首页不发 Discuz cookie，运行时发现后回填。
     */
    val cookiePrefix: String? = null,
    /** 每页主题行数（M0 实测），驱动分页换算用；0 = 运行时从页面探测 */
    val threadsPerPage: Int = 0,
    /** 帖子 rewrite 前缀：thread / tv / ""（"" = 老式 forum.php?mod=viewthread&tid=） */
    val threadUrlPrefix: String = "thread",
    /** 版块 rewrite 前缀；"" = forum.php?mod=forumdisplay&fid= 形态 */
    val boardUrlPrefix: String = "forum",
    /** mobile API 支持档位 */
    val mobileApi: MobileApiSupport = MobileApiSupport.PROBE,
    /** 登录页路径（WebView 打开 baseUrl + loginPath；五站 M0 实测同为 Discuz 标准登录页） */
    val loginPath: String = "member.php?mod=logging&action=login",
    /** 写操作冷却（秒），账号安全：客户端强制 */
    val postCooldownSeconds: Int = 15,
    /** L2 皮肤差异：选择器按站点覆盖 */
    val selectors: DiscuzSelectors = DiscuzSelectors.x3Default(),
    /** L3 行为差异的声明式开关 */
    val quirks: Set<SiteQuirk> = emptySet(),
    /** 站点品牌色（UI 来源圆点/徽标），android.graphics.Color Int（0xFFRRGGBB） */
    val brandColor: Int,
    /** M0 探测结论备注（人工维护） */
    val probeNote: String = "",
)

enum class MobileApiSupport { NONE, PROBE, FORUMDISPLAY, FULL }

/** 五站通用桌面 UA（M0 探测同款；伪装成桌面 Chrome，规避移动 UA 差页面） */
const val DesktopChromeUa =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

enum class SiteQuirk {
    /** 恩山：发 PM 前置「手机绑定」校验，响应含该文案需映射专用错误 */
    MOBILE_BIND_REQUIRED_FOR_PM,
    /** 整站 rewrite（thread-/forum- 前缀 URL） */
    REWRITE_URLS,
    /** 需要桌面 UA 才有完整页面 */
    DESKTOP_UA_ONLY,
    /** WAF 间歇挑战，请求失败时提示用户重试或走 WebView */
    FLAKY_WAF,
}

/**
 * 解析选择器（L2）：默认 X3 集，站点可对单键覆盖；解析函数内置 fallback 链。
 * 主行锚点 `tbody[id^=normalthread_]` 是 M0 实测的全站通用标记（tid 直接在行 id 里）。
 */
@Immutable
data class DiscuzSelectors(
    /** 主题行：M0 实测五站通用 */
    val threadRow: String = "tbody[id^=normalthread_]",
    /** 主题标题链接（行内） */
    val threadTitleLink: String = "a.xst",
    /** 楼层容器 */
    val postRow: String = "[id^=pid]",
    /** 楼层作者链接 */
    val postAuthor: String = ".pls .xw1",
    /** 楼层正文 */
    val postBody: String = "[id^=postmessage_]",
    /** 附件区 */
    val postAttach: String = ".pattl",
    /** formhash 输入框 */
    val formHash: String = "input[name=formhash]",
) {
    companion object {
        fun x3Default() = DiscuzSelectors()
    }
}
