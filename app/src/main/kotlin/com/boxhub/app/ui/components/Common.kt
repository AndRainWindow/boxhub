package com.boxhub.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 彩色首字圆头像 —— 设计语言核心元素（列表行无网络头像也可用）。
 * 颜色由名字稳定散列到调色板，同名恒定色。
 */
@Composable
fun LetterAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Int = 40,
) {
    val palette = listOf(
        Color(0xFF8E7CC3), Color(0xFF5B9BD5), Color(0xFF70AD47),
        Color(0xFFE8A33D), Color(0xFFC7554E), Color(0xFF4A9B9B),
        Color(0xFF9B7BB8), Color(0xFFD17DA8), Color(0xFF6B8EAE),
    )
    val color = remember(name) {
        if (name.isEmpty()) palette[0]
        else palette[(abs(name.hashCode())) % palette.size]
    }
    val ch = remember(name) { name.trim().firstOrNull()?.uppercaseChar() ?: '?' }
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = ch.toString(),
            color = Color.White,
            fontSize = (size / 2.4f).sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 站点来源色点 */
@Composable
fun SiteDot(color: Color, size: Int = 8) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** 灰底 pill（未读数/楼层徽标） */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** 简易 UiState */
data class UiState<T>(
    val loading: Boolean = false,
    val data: T? = null,
    val error: String? = null,
    /** 附加信息：页码等 */
    val extra: Int = 0,
) {
    companion object {
        fun <T> idle() = UiState<T>()
    }
}

fun <T> MutableStateFlow<UiState<T>>.update(transform: (UiState<T>) -> UiState<T>) {
    value = transform(value)
}

/** 相对时间（列表行右侧；epoch 秒 → 刚刚/N分钟前/昨天 HH:mm/M月d日） */
fun relativeTime(epochSeconds: Long?, nowMillis: Long = System.currentTimeMillis()): String {
    if (epochSeconds == null || epochSeconds <= 0) return ""
    val now = nowMillis / 1000
    val diff = now - epochSeconds
    return when {
        diff < 60 -> "刚刚"
        diff < 3600 -> "${diff / 60}分钟前"
        diff < 86400 && isSameDay(now, epochSeconds) -> "${diff / 3600}小时前"
        diff < 172800 -> "昨天"
        diff < 7 * 86400 -> "${diff / 86400}天前"
        else -> {
            val d = java.time.LocalDateTime.ofEpochSecond(
                epochSeconds, 0, java.time.ZoneId.systemDefault().rules
                    .getOffset(java.time.Instant.ofEpochSecond(epochSeconds))
            )
            if (d.year == java.time.LocalDateTime.now().year) "${d.monthValue}月${d.dayOfMonth}日"
            else "${d.year}年${d.monthValue}月${d.dayOfMonth}日"
        }
    }
}

private fun isSameDay(a: Long, b: Long): Boolean {
    val za = java.time.Instant.ofEpochSecond(a).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val zb = java.time.Instant.ofEpochSecond(b).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    return za == zb
}

/** 底部页码胶囊（快捷胶囊 v1：上一页 / n/N / 下一页 / 刷新） */
@Composable
fun PageCapsule(
    page: Int,
    totalPages: Int?,
    enabled: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            CapsuleText(if (enabled) "◀" else " ", enabled, onPrev)
            Text(
                text = if (totalPages != null) "$page/$totalPages" else "$page",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            CapsuleText(if (enabled && (totalPages == null || page < totalPages)) "▶" else " ",
                enabled && (totalPages == null || page < totalPages), onNext)
            CapsuleText("↻", enabled, onRefresh)
        }
    }
}

@Composable
private fun CapsuleText(text: String, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick, enabled = enabled) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}
