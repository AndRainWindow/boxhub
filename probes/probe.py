# -*- coding: utf-8 -*-
"""BoxHub M0 站点探测脚本（纯只读 GET，不登录不发帖）。

用法:
    python probe.py                 # 探测全部站点
    python probe.py enshan znds     # 探测指定站点
    python probe.py --cookies "..." # 附带手动 cookie（用于探测登录态才可见的项，如 swfupload hash）

产出: probes/<site>/probe.json + probes/<site>/notes.md

TLS: 使用系统默认证书校验；证书失败会作为 probe 结果记录（cert-error），不做绕过。
"""
import json
import re
import ssl
import sys
import time
import urllib.request
from pathlib import Path

UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36")

# M0 预配置（最终值以 probe.json 回填 SiteRegistry）
SITES = {
    "enshan": {
        "display_name": "恩山无线",
        "base_url": "https://www.right.com.cn/forum/",
        "home": "forum.php",
    },
    "hassbian": {
        "display_name": "瀚思彼岸",
        "base_url": "https://bbs.hassbian.com/",
        "home": "forum.php",
    },
    "znds": {
        "display_name": "ZNDS 智能电视网",
        "base_url": "https://www.znds.com/",
        "home": "forum.php",
    },
    "kaixin": {
        "display_name": "开心电视",
        "base_url": "https://www.kaixindianshi.com/",
        "home": "index.php",
    },
    "mydigit": {
        "display_name": "数码之家",
        "base_url": "https://www.mydigit.cn/",
        "home": "forum.php",
    },
}

# 默认证书校验（不关闭）
CTX = ssl.create_default_context()


def _fetch_curl(url, cookie=None, timeout=15):
    """curl 回退（默认证书校验，不使用 -k）。

    某些站点的 WAF 会按 TLS 指纹拦截 Python urllib（如 ZNDS 的腾讯 EdgeOne 返回 567），
    curl（schannel/系统 TLS）通常放行。回退时在结果里标注 fetch_method=curl。
    """
    import subprocess
    cmd = ["curl", "-sS", "--max-time", str(timeout),
           "-A", UA,
           "-H", "Accept: text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
           "-H", "Accept-Language: zh-CN,zh;q=0.9",
           "-w", "\n__META__%{http_code}|%{url_effective}|%{size_download}"]
    if cookie:
        cmd += ["-H", f"Cookie: {cookie}"]
    cmd.append(url)
    try:
        out = subprocess.run(cmd, capture_output=True, timeout=timeout + 5)
    except FileNotFoundError:
        return 0, {}, b"", url, "curl-not-found"
    except Exception as e:
        return 0, {}, b"", url, f"curl:{type(e).__name__}:{e}"
    raw = out.stdout
    idx = raw.rfind(b"\n__META__")
    if idx < 0:
        return 0, {}, b"", url, f"curl-no-meta:{out.stderr[:200].decode('utf-8', 'replace')}"
    body = raw[:idx]
    meta = raw[idx + len(b"\n__META__"):].decode("ascii", "ignore").split("|")
    try:
        status = int(meta[0])
    except (ValueError, IndexError):
        status = 0
    return status, {"X-Curl": "1"}, body, meta[1] if len(meta) > 1 else url, None


def fetch(url, cookie=None, timeout=15, _via_curl=False):
    """GET 一次，返回 (status, headers, body_bytes, final_url, error)。

    urllib 被 WAF 拦（567/连接层失败）时自动回退 curl。
    """
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9",
        "Accept-Encoding": "identity",  # 避免压缩解码问题
    })
    if cookie:
        req.add_header("Cookie", cookie)
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=CTX) as r:
            return r.status, dict(r.headers), r.read(), r.geturl(), None
    except urllib.error.HTTPError as e:
        body = b""
        try:
            body = e.read()
        except Exception:
            pass
        err = f"HTTPError:{e.code}"
        if e.code in (567, 403, 429, 503) and not _via_curl:
            st, hdrs, cbody, curl_url, cerr = _fetch_curl(url, cookie, timeout)
            if st == 200 and cbody:
                hdrs = dict(hdrs)
                hdrs["X-Fetch-Fallback"] = "curl-after-waf-block"
                return st, hdrs, cbody, curl_url, None
        return e.code, dict(e.headers or {}), body, url, err
    except ssl.SSLError as e:
        if not _via_curl:
            st, hdrs, cbody, curl_url, cerr = _fetch_curl(url, cookie, timeout)
            if st and cbody:
                hdrs = dict(hdrs)
                hdrs["X-Fetch-Fallback"] = "curl-after-ssl-error"
                return st, hdrs, cbody, curl_url, None
        return 0, {}, b"", url, f"cert-error:{type(e).__name__}:{e}"
    except Exception as e:
        if not _via_curl:
            st, hdrs, cbody, curl_url, cerr = _fetch_curl(url, cookie, timeout)
            if st and cbody:
                hdrs = dict(hdrs)
                hdrs["X-Fetch-Fallback"] = "curl-after-exception"
                return st, hdrs, cbody, curl_url, None
        return 0, {}, b"", url, f"{type(e).__name__}:{e}"


def detect_charset(headers, body):
    """按优先级: HTTP header -> BOM -> meta charset -> 启发式。"""
    ctype = next((v for k, v in headers.items() if k.lower() == "content-type"), "") or ""
    m = re.search(r"charset=([\w-]+)", ctype, re.I)
    if m:
        return m.group(1).upper(), "http-header"
    if body.startswith(b"\xef\xbb\xbf"):
        return "UTF-8", "bom"
    if body.startswith((b"\xff\xfe", b"\xfe\xff")):
        return "UTF-16", "bom"
    head = body[:4096].decode("ascii", errors="ignore")
    m = re.search(r'<meta[^>]+charset=["\']?([\w-]+)', head, re.I)
    if m:
        return m.group(1).upper(), "meta"
    # 启发式: 能否按 utf-8 解码前 64KB
    sample = body[:65536]
    try:
        sample.decode("utf-8")
        return "UTF-8", "heuristic-utf8-ok"
    except UnicodeDecodeError:
        return "GBK", "heuristic-utf8-fail"


def decode(body, charset):
    try:
        return body.decode(charset, errors="replace")
    except LookupError:
        return body.decode("utf-8", errors="replace")


def first(pattern, text, flags=0):
    m = re.search(pattern, text, flags)
    return m.group(1) if m else None


def probe_site(name, cfg, extra_cookie=None):
    base = cfg["base_url"]
    result = {
        "site": name,
        "display_name": cfg["display_name"],
        "base_url": base,
        "probed_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "checks": {},
    }
    cookie = extra_cookie

    # ---- 1) 首页: 可达性 / 防盾 / Discuz 版本 / charset ----
    st, hdrs, body, final, err = fetch(base + cfg["home"], cookie=cookie)
    checks = result["checks"]
    checks["home"] = {
        "url": base + cfg["home"], "status": st, "bytes": len(body),
        "final_url": final, "error": err,
        "server": next((v for k, v in hdrs.items() if k.lower() == "server"), None),
        "set_cookies": [v for k, v in hdrs.items() if k.lower() == "set-cookie"],
        "cf_server": any("cloudflare" in str(v).lower() for v in hdrs.values()),
    }
    if st != 200 or not body:
        result["conclusion"] = "cert-error" if err and "cert-error" in err else "unreachable"
        return result

    charset, csrc = detect_charset(hdrs, body)
    html = decode(body, charset)
    result["charset"] = {"value": charset, "source": csrc}
    result["discuz"] = {
        "version": first(r'content="(Discuz! X[\d.]+)', html),
        "powered_by": first(r'(Powered by Discuz!)', html),
        "generator": first(r'<meta name="generator" content="([^"]+)"', html),
    }
    # cookie 前缀（从 Set-Cookie 提取）
    prefixes = set()
    for c in checks["home"]["set_cookies"]:
        m = re.match(r"(\w+?)_(2132|3192|48dd|69df|2f85)_(?:auth|saltkey)=", c)
        if m:
            prefixes.add(f"{m.group(1)}_{m.group(2)}")
    result["cookie_prefix"] = sorted(prefixes)[0] if prefixes else None
    # 登录态标记（页面里是否有用户名区）
    result["anon_view"] = {
        "discuz_uid_present": "discuz_uid" in html,
        "uid_value": first(r'discuz_uid\s*=\s*[\'"]?(\d+)', html),
    }

    # ---- 2) mobile API ----
    st2, hdrs2, body2, _, err2 = fetch(base + "api/mobile/index.php?module=forumindex&version=4", cookie=cookie)
    api = {"status": st2, "error": err2, "alive": False, "modules": {}}
    if st2 == 200 and body2:
        try:
            j = json.loads(decode(body2, detect_charset(hdrs2, body2)[0]))
            v = j.get("Variables") or {}
            api["alive"] = True
            api["cookiepre"] = v.get("cookiepre")
            api["formhash"] = v.get("formhash")
            fl = v.get("forumlist") or []
            api["forumlist_count"] = len(fl)
            api["sample_boards"] = [
                {"fid": f.get("fid"), "name": f.get("name"), "threads": f.get("threads")}
                for f in fl[:8]
            ]
            api["modules"]["forumindex"] = True
        except (ValueError, AttributeError) as e:
            api["error"] = f"parse:{e}"
            api["raw_head"] = decode(body2[:500], "utf-8")
    # forumdisplay 模块（拿一个 fid 实测）
    fid = None
    if api.get("sample_boards"):
        fid = api["sample_boards"][0]["fid"]
    if fid:
        st3, _, body3, _, err3 = fetch(
            f"{base}api/mobile/index.php?module=forumdisplay&fid={fid}&version=4&page=1&tpp=30",
            cookie=cookie)
        if st3 == 200 and body3:
            try:
                j3 = json.loads(decode(body3, "utf-8"))
                v3 = j3.get("Variables") or {}
                tl = v3.get("forum_threadlist") or []
                api["modules"]["forumdisplay"] = True
                api["forumdisplay_sample"] = {"fid": fid, "threads": len(tl)}
                api["forumdisplay_keys"] = sorted(tl[0].keys())[:25] if tl else []
            except ValueError:
                api["modules"]["forumdisplay"] = False
    result["mobile_api"] = api

    # ---- 3) 主题列表 DOM 形态 (forumdisplay HTML) ----
    # fid 发现顺序: mobile API forumlist -> 首页 gid 分组页 -> 已知兜底
    fd_html = ""
    html_fid = fid
    if not html_fid:
        # 拼接候选文本: 首页 + gid 分组页（恩山的板块链接只出现在 gid 页）
        candidates = [html]
        gid = first(r'[?&]gid=(\d+)', html)
        if gid:
            stg, hdrsg, bodyg, _, _ = fetch(f"{base}forum.php?gid={gid}", cookie=cookie)
            if stg == 200 and bodyg:
                candidates.append(decode(bodyg, detect_charset(hdrsg, bodyg)[0]))
        for text in candidates:
            html_fid = first(r'mod=forumdisplay&(?:amp;)?fid=(\d+)', text)
            if html_fid:
                break
            # rewrite 版块 URL: forum-{fid}-{page}.html（恩山整站 rewrite）
            html_fid = first(r'forum-(\d+)-\d+\.html', text)
            if html_fid:
                break
    if html_fid:
        st4, hdrs4, body4, final4, err4 = fetch(
            f"{base}forum.php?mod=forumdisplay&fid={html_fid}", cookie=cookie)
        if st4 == 200 and body4:
            cs4, _ = detect_charset(hdrs4, body4)
            fd = decode(body4, cs4)
            fd_html = fd
            thread_urls = re.findall(r"thread-(\d+)-(\d+)-(\d+)\.html", fd)
            rows = len(set(t[0] for t in thread_urls))
            normalthread = len(re.findall(r'id="normalthread_', fd))
            result["forumdisplay"] = {
                "fid": html_fid, "status": st4, "bytes": len(body4),
                "unique_threads": rows,
                "normalthread_rows": normalthread,
                "li_pbw": bool(re.search(r'<li[^>]*class="[^"]*\bpbw\b', fd)),
                "datatable": "datatable" in fd,
                "thread_row_pattern": (
                    "tbody[id^=normalthread_]" if normalthread else
                    "li.pbw" if re.search(r'<li[^>]*class="[^"]*\bpbw\b', fd) else
                    "table.datatable" if "datatable" in fd else "unknown"
                ),
                "page_markers": sorted(set(re.findall(r'forumdisplay[^"\']*page=(\d+)', fd)))[:10],
            }

    # ---- 4) rewrite 帖子页（tid 优先取版块页，回退首页）----
    m = re.search(r"thread-(\d+)-1-1\.html", fd_html) or re.search(r"thread-(\d+)-1-1\.html", html)
    if m:
        tid = m.group(1)
        st5, hdrs5, body5, final5, err5 = fetch(f"{base}thread-{tid}-1-1.html", cookie=cookie)
        ok = st5 == 200 and b"postmessage_" in body5
        result["rewrite_thread"] = {
            "tid": tid, "status": st5, "ok": ok,
            "has_pid": bool(re.search(rb'id="pid\d+"', body5)),
            "final_url": final5, "error": err5,
        }

    # ---- 5) 风险标记 ----
    risks = []
    if checks["home"]["cf_server"]:
        risks.append("cloudflare-like-headers")
    if any(x in html.lower() for x in ["cloudflare", "cf-browser-verification", "just a moment"]):
        risks.append("cf-challenge-page")
    if any("acw_tc" in c for c in checks["home"]["set_cookies"]):
        risks.append("aliyun-waf")
    if any("security_session" in c for c in checks["home"]["set_cookies"]):
        risks.append("safedog-or-similar-session")
    if err:
        risks.append(f"fetch-error:{err}")
    result["risks"] = risks
    result["conclusion"] = (
        "ok" if st == 200 and result["discuz"]["version"] else
        "non-discuz-or-blocked" if st == 200 else "unreachable"
    )
    return result


def write_outputs(name, r):
    out = Path(__file__).parent / name
    out.mkdir(parents=True, exist_ok=True)
    (out / "probe.json").write_text(
        json.dumps(r, ensure_ascii=False, indent=2), encoding="utf-8")

    lines = [
        f"# {r['display_name']} ({name})",
        "",
        f"- 探测时间: {r.get('probed_at')}",
        f"- base_url: {r.get('base_url')}",
        f"- 结论: **{r.get('conclusion')}**",
        f"- charset: {r.get('charset')}",
        f"- Discuz: {r.get('discuz')}",
        f"- cookie 前缀: {r.get('cookie_prefix')}",
        f"- mobile API: alive={r.get('mobile_api', {}).get('alive')}, "
        f"modules={r.get('mobile_api', {}).get('modules')}, "
        f"cookiepre={r.get('mobile_api', {}).get('cookiepre')}",
        f"- forumdisplay DOM: {r.get('forumdisplay')}",
        f"- rewrite 帖子页: {r.get('rewrite_thread')}",
        f"- 风险: {r.get('risks')}",
        "",
        "## 备注",
        "",
    ]
    (out / "notes.md").write_text("\n".join(lines), encoding="utf-8")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    cookie = None
    for i, a in enumerate(sys.argv[1:]):
        if a == "--cookies" and i + 1 < len(sys.argv):
            cookie = sys.argv[i + 2]
    names = args or list(SITES)
    for name in names:
        if name not in SITES:
            print(f"skip unknown site: {name}")
            continue
        print(f"--- probing {name} ---")
        try:
            r = probe_site(name, SITES[name], extra_cookie=cookie)
        except Exception as e:
            r = {"site": name, "conclusion": f"probe-crashed:{type(e).__name__}:{e}"}
        write_outputs(name, r)
        print(f"    conclusion={r.get('conclusion')} risks={r.get('risks')}")


if __name__ == "__main__":
    main()
