package com.boxhub.app.core.model

/**
 * M4 写操作模型（Discuz）。
 * ReplyContext = prepareReply 从回帖表单页解析出的全部提交要素。
 */
data class ReplyContext(
    val fid: String,
    val tid: String,
    val formhash: String,
    /** 表单 input[name=posttime]，必填 */
    val posttime: String,
    /** 表单预填正文（引用时 Discuz 会预填引文） */
    val prefillMessage: String = "",
    /** 引用三兄弟（repquote 打开时表单已预填，原样回传） */
    val noticeAuthor: String? = null,
    val noticeTrimStr: String? = null,
    val noticeAuthoMsg: String? = null,
    /** 上传令牌 input[name=hash] */
    val uploadHash: String? = null,
    /** 当前用户 uid（swfupload URL 参数；页面 profile 链接提取） */
    val uid: String? = null,
    /** 原生图片验证码 idhash（updateseccode JS 提取），null=无需验证码 */
    val seccodeIdhash: String? = null,
    /** 验证码提交字段名（表单动态解析；默认 seccodeverify） */
    val seccodeField: String = "seccodeverify",
    /** 腾讯验证码 appId（若表单为腾讯滑块；null=原生图片） */
    val tencentAppId: String? = null,
    /** 图片上传限制 */
    val maxImageSizeKb: Int? = null,
    val allowedImageExts: List<String> = emptyList(),
) {
    /** 验证码刷图 URL（XHR 头见 gateway；Coil 加载带 cookie） */
    fun seccodeImageUrl(baseUrl: String): String? = seccodeIdhash?.let {
        "${baseUrl}misc.php?mod=seccode&action=update&idhash=$it&modid=forum::post"
    }
}

/** 提交成功的回执 */
data class PostReceipt(
    /** 新楼层 pid（响应里可能没有，可空） */
    val pid: String? = null,
    /** 原始成功文案（调试/展示） */
    val message: String = "",
)

/**
 * 验证码答案（字段通用化）：
 *  - 原生图片 → mapOf(seccodeField to 用户输入)
 *  - 腾讯滑块 → mapOf("seccodehash" to .., "codeVerifyTicket" to .., "codeVerifyRandstr" to ..)
 */
data class CaptchaInput(val fields: Map<String, String>)
