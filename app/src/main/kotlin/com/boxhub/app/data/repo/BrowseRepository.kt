package com.boxhub.app.data.repo

import com.boxhub.app.core.discuz.ThreadListPage
import com.boxhub.app.core.driver.ForumDriver
import com.boxhub.app.core.model.Board
import com.boxhub.app.core.model.ThreadDetail
import com.boxhub.app.core.network.DriverFactory
import com.boxhub.app.data.site.SiteConfig
import com.boxhub.app.data.site.SiteRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    @dagger.hilt.android.qualifiers.ApplicationContext
    private val context: android.content.Context,
) {
    fun site(siteId: String): SiteConfig? = SiteRegistry.byId(siteId)

    fun baseUrl(siteId: String): String = site(siteId)?.baseUrl ?: ""

    private fun driver(siteId: String): ForumDriver? =
        SiteRegistry.byId(siteId)?.let { drivers.driver(siteId, it) }

    suspend fun boards(siteId: String) = driver(siteId)?.boards()
        ?: error("unknown site: $siteId")

    suspend fun threadList(siteId: String, fid: String, page: Int) =
        driver(siteId)?.threadList(fid, page) ?: error("unknown site: $siteId")

    suspend fun threadDetail(siteId: String, tid: String, page: Int) =
        driver(siteId)?.threadDetail(tid, page) ?: error("unknown site: $siteId")

    // ---------- M4 写操作 ----------

    suspend fun prepareReply(siteId: String, tid: String, pid: String?, fid: String) =
        driver(siteId)?.prepareReply(fid, tid, pid)
            ?: error("unknown site: $siteId")

    suspend fun submitReply(
        siteId: String,
        ctx: com.boxhub.app.core.model.ReplyContext,
        message: String,
        images: List<com.boxhub.app.core.model.Attachment>,
        captcha: com.boxhub.app.core.model.CaptchaInput?,
    ) = driver(siteId)?.submitReply(ctx, message, images, captcha)
        ?: error("unknown site: $siteId")

    suspend fun uploadImage(
        siteId: String,
        ctx: com.boxhub.app.core.model.ReplyContext,
        bytes: ByteArray,
        filename: String,
        mime: String,
    ) = driver(siteId)?.uploadImage(ctx, bytes, filename, mime)
        ?: error("unknown site: $siteId")

    /** 读取相册图片字节（content:// Uri） */
    suspend fun readBytes(siteId: String, uri: android.net.Uri): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
        }
}
