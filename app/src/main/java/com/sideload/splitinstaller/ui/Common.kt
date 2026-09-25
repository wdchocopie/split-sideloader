package com.sideload.splitinstaller.ui

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.sideload.splitinstaller.core.bundle.BundleFormat
import com.sideload.splitinstaller.core.bundle.IconBytes
import com.sideload.splitinstaller.core.bundle.Severity
import com.sideload.splitinstaller.core.verify.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---- tones ----------------------------------------------------------------------------

enum class Tone { NEUTRAL, PRIMARY, SUCCESS, WARNING, DANGER, INFO }

@Composable
fun Tone.container(): Color = when (this) {
    Tone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHighest
    Tone.PRIMARY -> MaterialTheme.colorScheme.primaryContainer
    Tone.SUCCESS -> AppTheme.status.successContainer
    Tone.WARNING -> AppTheme.status.warningContainer
    Tone.DANGER -> MaterialTheme.colorScheme.errorContainer
    Tone.INFO -> MaterialTheme.colorScheme.tertiaryContainer
}

@Composable
fun Tone.onContainer(): Color = when (this) {
    Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    Tone.PRIMARY -> MaterialTheme.colorScheme.onPrimaryContainer
    Tone.SUCCESS -> AppTheme.status.onSuccessContainer
    Tone.WARNING -> AppTheme.status.onWarningContainer
    Tone.DANGER -> MaterialTheme.colorScheme.onErrorContainer
    Tone.INFO -> MaterialTheme.colorScheme.onTertiaryContainer
}

/** The strong form of a tone, for icons and text on a neutral surface. */
@Composable
fun Tone.accent(): Color = when (this) {
    Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    Tone.PRIMARY -> MaterialTheme.colorScheme.primary
    Tone.SUCCESS -> AppTheme.status.success
    Tone.WARNING -> AppTheme.status.warning
    Tone.DANGER -> MaterialTheme.colorScheme.error
    Tone.INFO -> MaterialTheme.colorScheme.tertiary
}

fun Severity.tone(): Tone = when (this) {
    Severity.INFO -> Tone.NEUTRAL
    Severity.WARN -> Tone.WARNING
    Severity.ERROR -> Tone.DANGER
}

fun Verdict.tone(): Tone = when (this) {
    Verdict.OK -> Tone.SUCCESS
    Verdict.WARN -> Tone.WARNING
    Verdict.BROKEN -> Tone.DANGER
    Verdict.UNKNOWN, Verdict.NOT_INSTALLED -> Tone.NEUTRAL
}

fun Verdict.icon(): ImageVector = when (this) {
    Verdict.OK -> Icons.Rounded.CheckCircle
    Verdict.WARN -> Icons.Rounded.Warning
    Verdict.BROKEN -> Icons.Rounded.Error
    Verdict.UNKNOWN, Verdict.NOT_INSTALLED -> Icons.AutoMirrored.Rounded.HelpOutline
}

// ---- surfaces ---------------------------------------------------------------------------

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.dp),
    contentPadding: PaddingValues = PaddingValues(18.dp),
    spacing: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val inner: @Composable () -> Unit = {
        Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, color = color, contentColor = contentColor) { inner() }
    } else {
        Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = color, contentColor = contentColor) { inner() }
    }
}

@Composable
fun CardTitle(text: String, icon: ImageVector? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 6.dp, top = 10.dp, bottom = 2.dp),
    )
}

// ---- small pieces -----------------------------------------------------------------------

@Composable
fun StatusPill(
    text: String,
    tone: Tone,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    Surface(shape = CircleShape, color = tone.container(), contentColor = tone.onContainer(), modifier = modifier) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** A quiet pill for metadata: version, size, ABI. */
@Composable
fun MetaPill(text: String, mono: Boolean = false, tone: Tone? = null) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = tone?.container() ?: MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = tone?.onContainer() ?: MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = if (mono) FontFamily.Monospace else null,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun IconBadge(
    icon: ImageVector,
    tone: Tone,
    size: Dp = 40.dp,
    iconSize: Dp = size * 0.5f,
    shape: androidx.compose.ui.graphics.Shape = CircleShape,
) {
    Box(
        Modifier.size(size).clip(shape).background(tone.container()),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(iconSize), tint = tone.onContainer())
    }
}

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun KeyValue(
    label: String,
    value: String,
    mono: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(128.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            style = if (mono) MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 18.sp)
            else MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else null,
            color = valueColor,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun FindingRow(severity: Severity, message: String) {
    val tone = severity.tone()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(
            when (severity) {
                Severity.INFO -> Icons.Rounded.Info
                Severity.WARN -> Icons.Rounded.Warning
                Severity.ERROR -> Icons.Rounded.Error
            },
            null,
            Modifier.padding(top = 1.dp).size(18.dp),
            tint = tone.accent(),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (severity == Severity.INFO) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun SwitchRow(
    title: String,
    description: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Surface(
        onClick = { onChange(!checked) },
        enabled = enabled,
        color = Color.Transparent,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    icon, null, Modifier.size(22.dp),
                    tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                )
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
                if (description != null) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconBadge(icon, Tone.PRIMARY, size = 72.dp, iconSize = 34.dp)
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(6.dp))
            action()
        }
    }
}

/** Monospace block for logcat output, command snippets and raw installer messages. */
@Composable
fun ConsoleBlock(
    lines: List<String>,
    modifier: Modifier = Modifier,
    colorFor: @Composable (String) -> Color = { MaterialTheme.colorScheme.onSurfaceVariant },
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(14.dp)) {
            lines.forEach { line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = colorFor(line),
                    softWrap = false,
                )
            }
        }
    }
}

/** Format tile for bundle lists: the extension, tinted per format. */
@Composable
fun FormatTile(format: BundleFormat, size: Dp = 46.dp) {
    val (bg, fg) = when (format) {
        BundleFormat.XAPK -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        BundleFormat.APKM -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        BundleFormat.APKS -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        BundleFormat.ZIP, BundleFormat.APK ->
            MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(format.label, color = fg, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp)
    }
}

fun formatOf(fileName: String): BundleFormat = when (fileName.substringAfterLast('.', "").lowercase()) {
    "xapk" -> BundleFormat.XAPK
    "apkm" -> BundleFormat.APKM
    "apks" -> BundleFormat.APKS
    "apk" -> BundleFormat.APK
    else -> BundleFormat.ZIP
}

// ---- app icons ------------------------------------------------------------------------

private object IconCache {
    private val cache = android.util.LruCache<String, ImageBitmap>(120)
    fun get(key: String): ImageBitmap? = cache.get(key)
    fun put(key: String, bitmap: ImageBitmap) {
        cache.put(key, bitmap)
    }
}

/**
 * The installed app's icon when there is one, the bundle's own `icon.png` otherwise, and
 * a lettered tile as the last resort.
 */
@Composable
fun AppIcon(
    packageName: String?,
    label: String,
    size: Dp = 44.dp,
    bytes: IconBytes? = null,
    corner: Dp = size * 0.28f,
) {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    val fromBytes = remember(bytes) {
        bytes?.let { runCatching { BitmapFactory.decodeByteArray(it.bytes, 0, it.bytes.size)?.asImageBitmap() }.getOrNull() }
    }
    val installed by produceState<ImageBitmap?>(initialValue = packageName?.let(IconCache::get), packageName, inspection) {
        if (value != null || packageName == null || inspection || fromBytes != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap(144, 144).asImageBitmap()
            }.getOrNull()?.also { IconCache.put(packageName, it) }
        }
    }
    val bitmap = fromBytes ?: installed
    val shape = RoundedCornerShape(corner)
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(size).clip(shape))
    } else {
        Box(
            Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label.trim().firstOrNull()?.uppercase() ?: "?",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun TitleWithMono(title: String, mono: String?, titleStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium) {
    Column {
        Text(title, style = titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!mono.isNullOrBlank()) {
            Text(
                mono,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---- formatting ---------------------------------------------------------------------

fun humanSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format("%.2f GB", bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> String.format("%.1f MB", bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> String.format("%.0f KB", bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}

fun relativeTime(time: Long, now: Long = System.currentTimeMillis()): String =
    runCatching {
        DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    }.getOrDefault("")
