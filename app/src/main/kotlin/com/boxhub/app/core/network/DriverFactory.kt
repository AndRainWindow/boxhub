package com.boxhub.app.core.network

import com.boxhub.app.core.discuz.DiscuzDriver
import com.boxhub.app.data.site.SiteConfig
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 每站一个 gateway + driver（缓存复用；cookie jar 随实例存活） */
@Singleton
class DriverFactory @Inject constructor(
    private val cookies: SharedCookieStore,
) {

    private val gateways = ConcurrentHashMap<String, SiteHttpGateway>()
    private val drivers = ConcurrentHashMap<String, DiscuzDriver>()

    fun gateway(siteId: String, config: SiteConfig): SiteHttpGateway =
        gateways.getOrPut(siteId) { SiteHttpGateway(config, cookies) }

    fun driver(siteId: String, config: SiteConfig): DiscuzDriver =
        drivers.getOrPut(siteId) { DiscuzDriver(config, gateway(siteId, config)) }
}
