package io.github.teykaijun.netblocker.ui

import android.content.Context
import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shows an app's launcher icon, loading it off the main thread and caching it for fast scrolling. */
@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val icon by produceState(initialValue = IconCache.get(packageName, sizePx), packageName, sizePx) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { IconCache.load(context, packageName, sizePx) }
        }
    }

    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier.size(size))
    } else {
        Box(
            modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

private object IconCache {
    private val cache = LruCache<String, ImageBitmap>(300)

    fun get(packageName: String, sizePx: Int): ImageBitmap? = cache.get(key(packageName, sizePx))

    fun load(context: Context, packageName: String, sizePx: Int): ImageBitmap? = try {
        context.packageManager.getApplicationIcon(packageName)
            .toBitmap(sizePx, sizePx)
            .asImageBitmap()
            .also { cache.put(key(packageName, sizePx), it) }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun key(packageName: String, sizePx: Int) = "$packageName@$sizePx"
}
