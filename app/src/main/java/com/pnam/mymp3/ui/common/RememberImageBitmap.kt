package com.pnam.mymp3.ui.common

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes a cached artwork file off the main thread.
 *
 * Artwork is already downscaled to 480px when it is cached (see `AudioMetadataReader`), so a
 * decode is cheap enough to do per list row without pulling in an image-loading library.
 */
@Composable
fun rememberImageBitmap(path: String?): ImageBitmap? {
    val cached = produceState<ImageBitmap?>(initialValue = null, key1 = path) {
        value = path?.let { filePath ->
            withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(filePath)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    return cached.value
}
