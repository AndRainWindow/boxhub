<p align="center"><b>BoxHub</b> — 十个论坛，一个客户端</p>

<p align="center">
恩山 · 瀚思彼岸 · ZNDS · 开心电视 · 数码之家 · 吾爱破解 · V2EX · 海纳思 · 看雪<br>
原生 Kotlin + Compose
</p>

---

## 状态

**v0.1.1**：只读浏览 + WebView 登录 + Discuz 回帖全链路，三新引擎（V2EX/海纳斯/看雪）只读接入。

| 里程碑 | 内容 | 状态 |
|:---:|:---|:---:|
| M0 | 站点探测（可达性/WAF/编码/DOM/mobile API） | ✅ |
| M1 | Gradle 骨架 + 主题 + 三 tab 空壳 + SiteRegistry | ✅ |
| M2 | 只读浏览（网关/驱动/解析器/fixtures/版块/列表/楼层/聚合流） | ✅ |
| M3 | WebView 登录 + cookie 加密持久化 + 会话失效横幅 | ✅ |
| M4 | Discuz 回帖/图片上传/验证码（45 单测） | ✅ |
| Phase C | 引擎泛化 `ForumDriver` + V2EX/海纳斯(Flarum)/看雪(Xiuno) 只读驱动 | ✅ |
| M5 | 通知未读 + 聚合流完整版 | ⏳ |
| M6 | 设置/打磨/内测包 | ⏳ |

## 架构速览

- **四引擎**：`ForumDriver` 统一接口，`Engine.DISCUZ / V2EX / FLARUM / KANXUE` 分发
  （Discuz HTML · V2EX 自研 HTML · 海纳斯 JSON:API · 看雪 Xiuno HTML）
- **差异三级收敛**：L1 `SiteConfig` 纯数据 / L2 选择器 / L3 驱动钩子
- **协议主路径**：Discuz Web HTML（formhash + cookie）；mobile API 仅作加速
- **登录**：WebView 自行输密码（凭证不经过 App），`CookieManager` 采集 → Keystore AES-GCM 持久化
- **网关**：每站独立 OkHttp + 按站 charset 编码（GBK 单点），cookie/Coil 图片/WebView 三方共用

## 构建

```bash
./gradlew testDebugUnitTest   # 解析器 fixture 单测
./gradlew assembleDebug       # 调试包（需 Android SDK，compileSdk 36）
./gradlew assembleRelease     # 发布包（根目录 keystore.properties + *.keystore 签名，不入库）
```

> 依赖走阿里云 Maven 镜像（`settings.gradle.kts`），因 dl.google.com / repo1.maven.org 不可达。

## 目录

```
probes/     站点探测脚本与产物
fixtures/   解析器单测样本（真实页面/JSON 录制，测试资源根）
app/        主模块（core 协议层 / data 持久层 / driver 站点特异 / ui）
```
