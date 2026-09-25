<p align="center"><b>BoxHub</b> — 五个 Discuz 论坛，一个客户端</p>

<p align="center">
恩山 · 瀚思彼岸 · ZNDS · 开心电视 · 数码之家 · 原生 Kotlin + Compose
</p>

---

## 状态

**M1 骨架**（见下），M0 站点探测已完成：5/5 站可接入（[probes/M0-SUMMARY.md](probes/M0-SUMMARY.md)）。

| 里程碑 | 内容 | 状态 |
|:---:|:---|:---:|
| M0 | 站点探测（可达性/WAF/编码/DOM/mobile API） | ✅ |
| M1 | Gradle 骨架 + M3 主题 + 三 tab 空壳 + SiteRegistry | ✅ |
| M2 | 只读浏览（网关/驱动/解析器/fixtures/版块/列表/楼层/聚合流初版） | ⏳ |
| M3 | WebView 登录 + cookie 持久化 + 会话失效横幅 | ⏳ |
| M4 | 回帖/发帖/图片上传/验证码 | ⏳ |
| M5 | 通知未读 + 聚合流完整版 | ⏳ |
| M6 | 设置/打磨/内测包 | ⏳ |

## 架构速览

- **协议主路径**：Discuz Web HTML（`forum.php?mod=...` + formhash + cookie），mobile API 仅作加速
- **差异三级收敛**：L1 `SiteConfig` 纯数据 / L2 `DiscuzSelectors` 选择器 / L3 `DiscuzDriver` 钩子
- **主题行锚点**（M0 实测五站通用）：`tbody[id^=normalthread_]`，tid 直接内嵌行 id
- 反编译同类客户端（Re:Source）仅作**协议与设计参考**，代码全部自研，不搬运其代码与资源

## 构建

```bash
./gradlew assembleDebug    # 需 Android SDK（compileSdk 36）
```

> 依赖走阿里云 Maven 镜像（`settings.gradle.kts`），因 dl.google.com / repo1.maven.org 不可达。

## 目录

```
probes/     M0 探测脚本与产物
fixtures/   解析器单测用 HTML 样本（M2 录制）
app/        主模块（core 协议层 / data 持久层 / driver 站点特异 / ui）
```
