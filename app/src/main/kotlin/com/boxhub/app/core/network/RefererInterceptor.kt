package com.boxhub.app.core.network

import com.boxhub.app.data.site.SiteRegistry
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 图片防盗链 Referer 补全：按图片 host 匹配站点域名 → 附带该站 Referer。
 * Coil 与正文 ImageGetter 共用（App 启动时挂到全局 ImageLoader）。
 */
class RefererInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.header("Referer") != null) return chain.proceed(req)
        val host = req.url.host
        val site = SiteRegistry.all.firstOrNull { s ->
            s.domains.any { d -> host == d || host.endsWith(".$d") }
        } ?: return chain.proceed(req)
        return chain.proceed(
            req.newBuilder().header("Referer", site.baseUrl).build()
        )
    }
}
