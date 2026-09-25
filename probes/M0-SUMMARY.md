# M0 站点探测总结（2026-09-25）

> 探测工具: `probe.py`（只读 GET，默认 TLS 证书校验，urllib 被 WAF 拦时回退 curl）
> 详细数据: 各站 `probe.json` / `notes.md`；原始页面: `_raw/`
> **结论: 5/5 站全部可接入**（验收线 ≥4/5），无剔除项。

## 总表

| 站点 | 域名 | Discuz | charset | cookie 前缀 | mobile API | 主题行标记 | 每页行数 | 帖子 URL | 风险 |
|---|---|---|---|---|---|---|---|---|---|
| 恩山 | www.right.com.cn/forum | **X3.5** | UTF-8 | 游客首页不发 Discuz cookie；参考实现为 `rHEX_2132`（登录时实测确认） | ❌ forumindex 返回手机门户页（走 HTML 路径） | `tbody[id^=normalthread_]` | 20 | rewrite: `thread-{tid}-{p}-1.html`，版块也是 rewrite `forum-{fid}-{p}.html` | 阿里云 ESA/WAF（`acw_tc`，间歇） |
| 瀚思彼岸 | bbs.hassbian.com | **X3.5** | UTF-8 | `gXRl_2132`（Set-Cookie 实证） | ✅ forumindex + forumdisplay | `tbody[id^=normalthread_]` | ~30 | rewrite: `thread-{tid}-{p}-1.html` | 无 |
| ZNDS | www.znds.com | **X3.4** | UTF-8 | `s9it_2132`（mobile API 实证） | ✅ forumindex + forumdisplay | `tbody[id^=normalthread_]` | 50 | **自定义 rewrite 前缀 `tv-{tid}-{p}-1.html`** | 腾讯 EdgeOne：按 TLS 指纹拦 Python urllib（HTTP 567），curl/真实浏览器放行 |
| 开心电视 | www.kaixindianshi.com | **X3.4** | UTF-8 | `ok8J_2132`（Set-Cookie 实证） | ❌（CF 拦 API 路径） | `tbody[id^=normalthread_]` | 20 | rewrite: `thread-{tid}-{p}-1.html` | Cloudflare 间歇挑战（curl 多次 000，偶发放行） |
| 数码之家 | www.mydigit.cn | **X3.4** | UTF-8 | `VhUn_2132`（mobile API 实证） | ✅ forumindex + forumdisplay | `tbody[id^=normalthread_]` | 板块而异（fid=78: 47/1页） | rewrite: `thread-{tid}-{p}-1.html` | 安全狗类 `security_session_verify` |

## 关键发现（直接影响 SiteConfig / DiscuzDriver 设计）

1. **全部 UTF-8** —— 无 GBK 站，v1 的 GBK 逻辑保留但暂无实测对象（风险 R3 降级）。
2. **主题行通用锚点 = `tbody id="normalthread_{tid}"`** —— tid 直接内嵌行 id，不依赖 URL 风格，作为解析器主选择器；标题链接取行内 `a.xst` / `a.s.xst`。
3. **帖子 URL 三种风格**：`thread-` 前缀 rewrite（4 站）、`tv-` 前缀 rewrite（ZNDS）、`forum.php?mod=viewthread&tid=` 老式（兜底）。`SiteConfig.threadUrlStyle` 需支持自定义 rewrite 前缀。版块 URL 同理（恩山 `forum-{fid}-{p}.html`）。
4. **每页行数站点化**：恩山 20、瀚思 ~30、ZNDS 50、开心 20、数码之家需按板块实测 → 进 `SiteConfig.threadsPerPage`，分页换算用 `PageCalculator`。
5. **mobile API 可用性分三档**：瀚思/ZNDS/数码之家 全活（forumindex 可直接当版块目录）；恩山/开心 只有 HTML 路径（`mobileApi: PROBE/NONE`）。
6. **WAF 三连**：阿里云（恩山）、腾讯 EdgeOne TLS 指纹（ZNDS）、Cloudflare 间歇（开心）。对真机影响待 M2 验证——OkHttp(平台 TLS)/WebView 指纹与 Python 不同，预期好于 curl；WebView 过盾可复用 cf_clearance 的登录机制兜底。
7. **开心电视 mobile API 不通但 HTML 通** → 纯 HTML 路径接入。
8. **数码之家 fid=37 是空/分类板块**，实测取活跃板块（如 fid=78）。

## 对 SiteRegistry 的回填（M1）

```
enshan   threadsPerPage=20  threadPrefix="thread" boardPrefix="forum"  cookiePrefix=null(待登录实测)  api=NONE
hassbian threadsPerPage=30  threadPrefix="thread" boardPrefix="forum"  cookiePrefix="gXRl_2132"      api=FULL
znds     threadsPerPage=50  threadPrefix="tv"     boardPrefix="forum"  cookiePrefix="s9it_2132"      api=FULL
kaixin   threadsPerPage=20  threadPrefix="thread" boardPrefix="forum"  cookiePrefix="ok8J_2132"      api=NONE
mydigit  threadsPerPage=30  threadPrefix="thread" boardPrefix="forum"  cookiePrefix="VhUn_2132"      api=FULL
```

## M2 需补的探测（带 cookie 的项）

- [ ] 各站发帖页 `swfupload` 的 `hash` 来源（`--cookies` 参数已支持）
- [ ] 瀚思/开心/恩山在登录态下的 mobile API
- [ ] 各站 seccode 验证码开关情况
- [ ] 录制 fixtures：每站 forumdisplay / viewthread 各 ≥1 页真实 HTML
