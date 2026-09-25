package com.boxhub.app.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import androidx.core.text.util.LinkifyCompat
import coil.ImageLoader
import coil.request.ImageRequest
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist

/**
 * Discuz 楼层正文渲染。
 *
 * 修复项（用户反馈 2026-09-25）：
 *  - 图片：正文 <img> 经 Jsoup 还原（懒加载 data-original → src）后由异步 ImageGetter 渲染；
 *    相对路径补全为绝对地址（配合 [com.boxhub.app.network.RefererInterceptor] 过防盗链）
 *  - 链接：相对 href 转绝对 + Linkify 识别裸 URL 文本；不再 setTextIsSelectable
 *    （可选模式会抢走 LinkMovementMethod 的点击）
 *
 * @param baseUrl 站点基址，用于补全相对链接/图片
 */
@Composable
fun HtmlText(
    html: String,
    baseUrl: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val loader = remember(context) { coil.Coil.imageLoader(context) }
    val maxImageWidthPx = remember {
        context.resources.displayMetrics.widthPixels -
            (48 + 16).dp2px(context)
    }

val processed = remember(html, baseUrl) { preprocessDiscuzHtml(html, baseUrl) }
    val tvRef = remember { arrayOfNulls<TextView>(1) }
    val spannable = remember(processed, dark, maxImageWidthPx) {
        val drawableFactory = { src: String ->
            AsyncDrawable(src, loader, maxImageWidthPx, context.applicationContext) {
                val tv = tvRef[0]
                tv?.post { tv.requestLayout(); tv.invalidate() }
            }
        }
        val spanned = HtmlCompat.fromHtml(
            processed,
            HtmlCompat.FROM_HTML_MODE_COMPACT,
            drawableFactory,
            null,
        )
        val builder = SpannableStringBuilder(spanned)
        // 裸 URL / 邮箱文本 → 可点击链接（HTML 未包 <a> 的情况）
        LinkifyCompat.addLinks(builder, android.text.util.Linkify.WEB_URLS)
        builder
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                tvRef[0] = this
                movementMethod = LinkMovementMethod.getInstance()
                // setLinkTextColor 直接收颜色值，不是资源 ID
                setLinkTextColor(if (dark) 0xFF7FA7E8.toInt() else 0xFF3A6EA5.toInt())
            }
        },
        update = { tv ->
            tv.text = spannable
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSize.value)
            tv.setTextColor(
                if (dark) Color.WHITE else Color.rgb(28, 27, 33)
            )
        },
    )
}

private fun Int.dp2px(ctx: android.content.Context): Int =
    (this * ctx.resources.displayMetrics.density + 0.5f).toInt()

/**
 * Jsoup 预处理：
 *  1. 懒加载图 src 还原（data-original/data-src）
 *  2. 相对 href/src → 绝对
 *  3. 去 script/style/iframe，保 img/a
 */
internal fun preprocessDiscuzHtml(html: String, baseUrl: String): String {
    return try {
        val doc = Jsoup.parseBodyFragment(html, baseUrl)
        doc.select("script, style, iframe, form, object, embed").remove()
        doc.select("img").forEach { img ->
            val src = img.attr("src")
            val missing = src.isBlank() || src.contains("none.gif")
            if (missing) {
                // Discuz 游客图：src 为 none.gif 占位，真实缩略图在属性链里
                // data-original/data-src(懒加载) → file(游客缩略图直链) → makefile(服务端图,相对)
                val real = listOf("data-original", "data-src", "file", "makefile")
                    .map { img.attr(it) }
                    .firstOrNull { it.isNotBlank() && !it.contains("none.gif") }
                if (real != null) {
                    img.attr("src", real)
                } else {
                    img.remove() // 拿不到任何真实地址：去掉占位
                    return@forEach
                }
            }
            val abs = img.attr("abs:src")
            if (abs.isNotBlank()) img.attr("src", abs)
            listOf("data-original", "data-src", "file", "makefile", "onclick", "onerror", "onload")
                .forEach { img.removeAttr(it) }
        }
        doc.select("a[href]").forEach { a ->
            val abs = a.attr("abs:href")
            if (abs.isNotBlank()) a.attr("href", abs)
        }
        doc.body().html()
    } catch (e: Exception) {
        html
    }
}

/**
 * 异步图片 Drawable：ImageGetter 需同步返回，先给 1x1 占位，Coil 加载完成后
 * 按屏宽缩放并触发重绘。
 */
private class AsyncDrawable(
    src: String,
    private val loader: ImageLoader,
    private val maxWidthPx: Int,
    context: android.content.Context,
    private val onReady: () -> Unit,
) : Drawable() {

    private var bitmap: Bitmap? = null
    private val appContext = context.applicationContext

    init {
        setBounds(0, 0, 1, 1)
        android.util.Log.d("BoxHubImg", "enqueue: $src")
        val request = ImageRequest.Builder(appContext)
            .data(src)
            .crossfade(false)
            .listener(
                onSuccess = { _, result ->
                    val raw = result.drawable
                    android.util.Log.d("BoxHubImg", "success: $src -> ${raw::class.java.simpleName}")
                    val bmp = (raw as? BitmapDrawable)?.bitmap ?: return@listener
                    val scaled = scaleToWidth(bmp, maxWidthPx) ?: return@listener
                    bitmap = scaled
                    val w = scaled.width
                    val h = scaled.height
                    setBounds(0, 0, w, h)
                    invalidateSelf()
                    onReady()
                },
                onError = { _, r ->
                    android.util.Log.e("BoxHubImg", "error: $src : ${r.throwable}")
                },
            )
            .build()
        loader.enqueue(request)
    }

    private fun scaleToWidth(bmp: Bitmap, maxW: Int): Bitmap? {
        if (bmp.width <= 0 || bmp.height <= 0) return null
        if (bmp.width <= maxW) return bmp
        val ratio = maxW.toFloat() / bmp.width
        return Bitmap.createScaledBitmap(bmp, maxW, (bmp.height * ratio).toInt(), true)
    }

    override fun draw(canvas: Canvas) {
        bitmap?.let { canvas.drawBitmap(it, null, bounds, null) }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in android.graphics.drawable.Drawable")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
