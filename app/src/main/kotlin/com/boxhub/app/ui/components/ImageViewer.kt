package com.boxhub.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.boxhub.app.core.model.Attachment

/**
 * 内置图片查看器（全屏）：
 *  - 捏合缩放（1x~5x）+ 拖动 + 双击切换
 *  - 左右滑动浏览同楼层多图，顶部页码指示
 *  - 「原图」：游客缩略图 → 点按后站内加载大图（登录后走 cookie，无需浏览器）
 */
@Composable
fun ImageViewer(
    images: List<Attachment>,
    startIndex: Int = 0,
    onClose: () -> Unit,
) {
    if (images.isEmpty()) return
    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, images.lastIndex),
        pageCount = { images.size },
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.97f)),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            var fullLoaded by remember(images[page].url) { mutableStateOf(false) }
            val att = images[page]
            ZoomableImage(
                url = if (fullLoaded) att.fullUrl ?: att.url else att.url,
                contentDescription = att.description,
                modifier = Modifier.fillMaxSize(),
            )

            // 单图时的「原图」快捷入口
            if (att.fullUrl != null && !fullLoaded) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp),
                ) {
                    androidx.compose.material3.Surface(
                        shape = RoundedCornerShape(50),
                        color = Color.Black.copy(alpha = 0.6f),
                    ) {
                        androidx.compose.material3.TextButton(onClick = { fullLoaded = true }) {
                            Text("查看原图", color = Color.White)
                        }
                    }
                }
            }
        }

        // 顶栏：关闭 + 页码
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
            }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Text(
                text = "${pagerState.currentPage + 1}/${images.size}",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(end = 16.dp),
            )
        }
    }
}

/**
 * 单图缩放容器：transformable 捏合/拖动 + 双击切换 1x/2.5x。
 */
@Composable
private fun ZoomableImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    val clamp: () -> Unit = {
        if (scale <= 1f) {
            offset = Offset.Zero
            if (scale < 1f) scale = 1f
        } else {
            val maxX = size.width * (scale - 1f) / 2f
            val maxY = size.height * (scale - 1f) / 2f
            offset = Offset(
                offset.x.coerceIn(-maxX, maxX),
                offset.y.coerceIn(-maxY, maxY),
            )
        }
    }

    val transformState = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset += pan
        clamp()
    }

    Box(
        modifier = modifier
            .onSizeChanged { size = it }
            .transformable(state = transformState)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                            clamp()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            loading = {
                androidx.compose.material3.CircularProgressIndicator(color = Color.White)
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
        )
    }
}
