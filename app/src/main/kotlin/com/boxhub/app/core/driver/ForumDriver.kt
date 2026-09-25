package com.boxhub.app.core.driver

import com.boxhub.app.core.discuz.result.DiscuzResult
import com.boxhub.app.core.model.Attachment
import com.boxhub.app.core.model.Board
import com.boxhub.app.core.model.CaptchaInput
import com.boxhub.app.core.model.PostReceipt
import com.boxhub.app.core.model.ReplyContext
import com.boxhub.app.core.model.ThreadDetail
import com.boxhub.app.data.site.SiteConfig

/**
 * 论坛驱动统一接口（Phase C 泛化）。
 *
 * 读方法全部引擎必须实现；写方法默认返回 [com.boxhub.app.core.discuz.result.ErrorKind.NotSupported]，
 * 仅 Discuz 系（含新接入的吾爱/Chiphell）覆写——与 Round 3 裁剪一致：
 * V2EX / 看雪 / 海纳斯 本轮只读 + 登录。
 */
interface ForumDriver {
    val config: SiteConfig

    // ---------- 读 ----------

    /** 版块目录 */
    suspend fun boards(): DiscuzResult<List<Board>>

    /** 版块主题列表 */
    suspend fun threadList(fid: String, page: Int): DiscuzResult<com.boxhub.app.core.discuz.ThreadListPage>

    /** 帖子详情（按页） */
    suspend fun threadDetail(tid: String, page: Int): DiscuzResult<ThreadDetail>

    /** 当前登录用户名，null=游客 */
    suspend fun loginUsername(): String?

    // ---------- 写（默认不支持） ----------

    suspend fun prepareReply(
        fid: String,
        tid: String,
        pid: String? = null,
    ): DiscuzResult<ReplyContext> = notSupported()

    suspend fun submitReply(
        ctx: ReplyContext,
        message: String,
        images: List<Attachment> = emptyList(),
        captcha: CaptchaInput? = null,
    ): DiscuzResult<PostReceipt> = notSupported()

    suspend fun uploadImage(
        ctx: ReplyContext,
        bytes: ByteArray,
        filename: String,
        mime: String,
    ): DiscuzResult<Attachment> = notSupported()

    private fun notSupported(): DiscuzResult<Nothing> = DiscuzResult.Failed(
        com.boxhub.app.core.discuz.result.ErrorKind.NotSupported,
        "该站暂只支持浏览（回帖能力按站逐步开放）",
    )
}
