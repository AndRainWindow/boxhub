package com.boxhub.app.data.repo

import com.boxhub.app.core.discuz.DiscuzDriver
import com.boxhub.app.core.discuz.ThreadListPage
import com.boxhub.app.core.model.Board
import com.boxhub.app.core.model.ThreadDetail
import com.boxhub.app.core.network.DriverFactory
import com.boxhub.app.data.site.SiteConfig
import com.boxhub.app.data.site.SiteRegistry
import javax.inject.Inject
import javax.inject.Singleton

/** 站点注册表访问 */
@Singleton
class SiteRepository @Inject constructor() {
    fun all(): List<SiteConfig> = SiteRegistry.all
    fun byId(id: String): SiteConfig? = SiteRegistry.byId(id)
}

/** 只读浏览仓储（M2）：驱动调用 + 站点解析，无业务协议逻辑 */
@Singleton
class BrowseRepository @Inject constructor(
    private val drivers: DriverFactory,
) {
    fun site(siteId: String): SiteConfig? = SiteRegistry.byId(siteId)

    private fun driver(siteId: String): DiscuzDriver? =
        SiteRegistry.byId(siteId)?.let { drivers.driver(siteId, it) }

    suspend fun boards(siteId: String) = driver(siteId)?.boards()
        ?: error("unknown site: $siteId")

    suspend fun threadList(siteId: String, fid: String, page: Int) =
        driver(siteId)?.threadList(fid, page) ?: error("unknown site: $siteId")

    suspend fun threadDetail(siteId: String, tid: String, page: Int) =
        driver(siteId)?.threadDetail(tid, page) ?: error("unknown site: $siteId")
}
