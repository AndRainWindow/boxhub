# TODO

> 2026-09-25 v0.1.1 真机测试反馈 + 已知待办。
> 修复前先跑 `./gradlew testDebugUnitTest` 确认 60/60 基线；每个修复补 fixture/测试防回归。

## 🔴 真机报障（v0.1.1 实测）

### 1. ZNDS 智能电视网 → 404

- **现象**：打开版块报 404（驱动文案 `404 board`）。
- **已确认根因**（2026-09-25 curl 实测）：
  - `Endpoints.board()` 因 `REWRITE_URLS` quirk 拼 `forum-{fid}-{page}.html` → **404**；
  - 变体 `forum-176-1.html` / `forum-{fid}-1-1.html` 也全部 404；
  - 查询形态 `forum.php?mod=forumdisplay&fid=176&page=1` → **200** ✓；
  - 帖子 rewrite `tv-{tid}-{page}-1.html` 是好的（fixture 内真实链接同款），**只有版块页不支持 rewrite**。
- **修复方向**：`SiteRegistry.znds` 置 `boardUrlPrefix = ""`（`Endpoints.board()` 自动回落查询形态）；
  `threadUrlPrefix = "tv"` 不动。
- **验收**：ZNDS 版块页能进、列表解析出楼；加一条 Endpoints URL 构造测试锁住 znds 双形态。

### 2. Chiphell → `no posts parsed`

- **现象**：帖子页报 `no posts parsed`。
- **已确认根因**（M0/Phase C 探测记录）：帖子页固定被 WAF 拦（HTTP 567），返回挑战页而非内容，
  解析自然为空；首页时通时不通。站点已 `enabled = false`（不进聚合流，但版块入口仍可达）。
- **修复方向**：
  1. WebView 打开登录/挑战页过盾 → `CookieManager` 抓 clearance → 复测帖子页；
  2. 通了 → 录 `fixtures/chiphell/viewthread_p1.html` + 解析测试 → `enabled = true` 解禁；
  3. 顺带改错误语义：`DiscuzDriver.threadDetail` 识别 WAF/挑战页特征 → 映射
     `ErrorKind.RateLimited`（提示走 WebView 过盾），别再透出 `no posts parsed`。
- **验收**：帖子页出楼或出明确的「站点防护挑战」提示，二选一，不再是解析错误。

### 3. 海纳斯（Flarum）→ HTTP 400

- **现象**：聚合流/版块加载报 `HTTP 400`。
- **已确认根因**（2026-09-25 curl 实测矩阵）：

  | URL | 结果 |
  |---|---|
  | `api/discussions?page[limit]=20`（不带 include） | **200** 170KB |
  | `api/discussions?page[limit]=20&include=users,tags,firstPost`（当前驱动） | **400** 56B |
  | `api/discussions?page[limit]=20&include=firstPost` | **200** 162KB |

  → 含 **`users`** 的 include 组合被拒（`users` 疑为该站 Flarum 版本的非法 include 名；
  默认 include 已包含 users/tags/posts，见 `fixtures/histb/discussions.json` 的 included）。
  详情页 `include=posts,user,users,tags` 实测 200，**不用动**。
- **修复方向**：`FlarumDriver.threadList` 移除 `include` 参数（依赖默认），保留 `page[limit]/[offset]` 与 `filter[tag]`。
- **验收**：真机聚合流出海纳斯卡片；补一条驱动 URL 构造单测（断言不含 `include=`）防回归。

## 🟡 已知待办

- [ ] **看雪 WebView 登录**：`user-login.htm` → 302 passport 跨子域，登录后 `bbs_sid`
      是否回写 bbs 域未真机验证（采集轮询已有 8s 回退重试兜底）
- [ ] **V2EX / 看雪 / 海纳斯 真机聚合流系统验证**：fixture 层全绿，真机仅零星反馈
      （V2EX 可达性依赖代理节点）
- [ ] **三新引擎回帖**：Round 3 裁剪为只读 + 登录；后续轮次按站开放
      （`ForumDriver` 默认 NotSupported 已兜底，UI 已隐藏入口）
- [ ] **Chiphell 解禁**：见 🔴#2
- [ ] **品牌色核对**：`BrandV2ex` / `BrandHistb` 代码里标了 TODO verify
- [ ] **错误语义统一**：WAF/挑战页 → `RateLimited` 映射（见 🔴#2.3）
- [ ] **M5**：通知未读 + 聚合流订阅源管理（README 里程碑）
- [ ] **M6**：设置打磨 / 内测包
- [ ] **签名备份**：根目录 `release.keystore` + `keystore.properties` 未入库，
      务必离线备份——丢了无法对 0.1.1 做覆盖升级
