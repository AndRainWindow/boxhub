package com.boxhub.app.core.model

/**
 * 聚合层数据模型（与站点无关；由 Discuz 解析器产出）。
 */

data class Board(
    val fid: String,
    val name: String,
    val description: String = "",
    val threads: Int = 0,
    val todayThreads: Int = 0,
    val url: String = "",
    val subBoards: List<Board> = emptyList(),
)

data class ThreadSummary(
    val siteId: String,
    val tid: String,
    val title: String,
    val author: String = "",
    val authorUid: String? = null,
    val replies: Int = 0,
    val views: Int? = null,
    /** 最后回复时间（epoch 秒），未知为 null */
    val lastPostAt: Long? = null,
    val lastPoster: String? = null,
    val fid: String? = null,
    val typeName: String? = null,
    val pinned: Boolean = false,
    val locked: Boolean = false,
    val hasImage: Boolean = false,
    val hasAttachment: Boolean = false,
    /** 标题高亮色 0xFFRRGGBB，无则 null */
    val highlightColor: Int? = null,
    /** 绝对地址（列表页点击直达） */
    val url: String = "",
)

data class ThreadDetail(
    val siteId: String,
    val tid: String,
    val title: String,
    val fid: String? = null,
    val author: String? = null,
    val totalFloors: Int? = null,
    /** 当前页在总页数中的位置（1 起） */
    val page: Int = 1,
    val totalPages: Int? = null,
    val posts: List<Post> = emptyList(),
)

data class Post(
    val pid: String,
    /** 楼层号（1L 起；首楼=1） */
    val floor: Int,
    val author: String = "",
    val authorUid: String? = null,
    /** 渲染前的正文 HTML（已清洗） */
    val bodyHtml: String = "",
    /** 发帖时间 epoch 秒，未知 null */
    val postedAt: Long? = null,
    val avatarUrl: String? = null,
    val attachments: List<Attachment> = emptyList(),
    /** 引用的楼层 pid（若有） */
    val quotePid: String? = null,
    val isOp: Boolean = false,
)

data class Attachment(
    /** 展示地址（游客缩略图 / 已登录原图） */
    val url: String,
    val aid: String? = null,
    val isImage: Boolean = true,
    val description: String = "",
    /** 大图地址（附件 nothumb 链接），点击跳转 */
    val fullUrl: String? = null,
)

data class UnreadCounts(
    val newPm: Int = 0,
    val newPrompt: Int = 0,
    val newMyPost: Int = 0,
) {
    val total: Int get() = newPm + newPrompt + newMyPost
}
