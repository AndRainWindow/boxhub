package com.boxhub.app.core.discuz.result

/**
 * 统一写/读结果。UI 只消费这一个类型族，
 * 站点差异（文案不同）在 driver 的 interpretWriteResponse 收敛为 ErrorKind。
 */
sealed interface DiscuzResult<out T> {
    data class Ok<T>(val value: T) : DiscuzResult<T>
    data class Failed(
        val kind: ErrorKind,
        val rawMessage: String? = null,
        val retryable: Boolean = false,
    ) : DiscuzResult<Nothing>

    /** 会话失效：全局横幅 + 一键重登 */
    data object SessionExpired : DiscuzResult<Nothing>
}

enum class ErrorKind {
    Network,
    Parse,
    LoginRequired,
    FormHashStale,
    Cooldown,             // 「两次发表间隔太近」
    SeccodeRequired,      // 需要验证码
    SeccodeWrong,
    MobileBindRequired,   // 恩山 PM 手机绑定
    NoPermission,
    ContentBlocked,       // 审核/敏感词/字数不足
    RateLimited,
    Unknown,
}

inline fun <T> DiscuzResult<T>.onOk(block: (T) -> Unit): DiscuzResult<T> {
    if (this is DiscuzResult.Ok) block(value)
    return this
}
