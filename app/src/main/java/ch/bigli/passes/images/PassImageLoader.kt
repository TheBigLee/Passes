package ch.bigli.passes.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipFile

enum class PassImage(val baseName: String) { LOGO("logo"), STRIP("strip") }

/** Best available resolution for [baseName] among zip [names], preferring @3x then @2x then base. */
internal fun bestImageEntry(names: Set<String>, baseName: String): String? =
    listOf("$baseName@3x.png", "$baseName@2x.png", "$baseName.png").firstOrNull { it in names }

/**
 * Loads pkpass [PassImage]s on-demand from the stored raw `.pkpass` zip at a given file path,
 * decoded off the main thread and cached in memory. Returns null if the image is absent or the
 * file/zip is unreadable; never throws to the caller.
 */
class PassImageLoader {
    private val cache = LruCache<String, Bitmap>(16)

    suspend fun load(rawFilePath: String, image: PassImage): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$rawFilePath#${image.baseName}"
        cache.get(key)?.let { return@withContext it }
        val bitmap = runCatching {
            ZipFile(rawFilePath).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toSet()
                val entryName = bestImageEntry(names, image.baseName) ?: return@use null
                zip.getInputStream(zip.getEntry(entryName)).use { BitmapFactory.decodeStream(it) }
            }
        }.getOrNull()
        if (bitmap != null) cache.put(key, bitmap)
        bitmap
    }
}
