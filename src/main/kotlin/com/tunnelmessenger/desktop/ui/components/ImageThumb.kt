package com.tunnelmessenger.desktop.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Миниатюры изображений: декодирование с масштабированием до ~720px
 * и LRU-кэш в памяти. Путь берётся из уже скачанного файла (localPath).
 *
 * Порт Android ImageThumb.kt: вместо Bitmap —
 * java.awt.image.BufferedImage, вместо BitmapFactory — ImageIO,
 * вместо LruCache — собственный LRU по байтам.
 */
object ImageThumb {

    private const val TARGET = 720
    private const val MAX_CACHE_BYTES = 24L * 1024 * 1024

    /** Простой LRU-кэш (access-order) с ограничением по суммарным байтам ARGB. */
    private val lock = Any()
    private val cache = LinkedHashMap<String, ImageBitmap>(16, 0.75f, true)
    private var cacheBytes = 0L

    private val IMAGE_EXT = listOf(
        ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp",
        // heic/heif ImageIO не декодирует, но расширение оставляем как в Android —
        // файл просто не декодируется и UI показывает строковый вид.
        ".heic", ".heif",
    )

    fun isImage(name: String?): Boolean {
        val n = (name ?: "").lowercase()
        return IMAGE_EXT.any { n.endsWith(it) }
    }

    /**
     * BufferedImage → ImageBitmap. Хелпер из задачи: makeFromBufferedImage.
     * Skiko требует «плоских» типов (INT_RGB/INT_ARGB) — кастомные типы
     * (TYPE_CUSTOM у некоторых PNG/16-битных файлов) перерисовываем в ARGB.
     */
    fun makeFromBufferedImage(src: BufferedImage): ImageBitmap {
        val argb = if (src.type == BufferedImage.TYPE_INT_ARGB || src.type == BufferedImage.TYPE_INT_RGB) {
            src
        } else {
            val img = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
            val g: Graphics2D = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(src, 0, 0, null)
            g.dispose()
            img
        }
        return argb.toComposeImageBitmap()
    }

    /** Декодировать файл с уменьшением до TARGET; null — не картинка/битый файл. */
    fun get(path: String?): ImageBitmap? {
        if (path.isNullOrEmpty()) return null
        synchronized(lock) { cache[path]?.let { return it } }
        val f = File(path)
        if (!f.exists() || !f.isFile) return null
        val decoded = runCatching {
            val full = ImageIO.read(f) ?: return null
            if (full.width <= 0 || full.height <= 0) return null
            // Даунсэмпл: в Android был inSampleSize (степени двойки) — здесь
            // честное масштабирование до TARGET по большей стороне.
            val scale = minOf(
                1f,
                TARGET.toFloat() / maxOf(full.width, full.height).coerceAtLeast(1),
            )
            val w = (full.width * scale).toInt().coerceAtLeast(1)
            val h = (full.height * scale).toInt().coerceAtLeast(1)
            if (w == full.width && h == full.height) full else {
                val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                val g: Graphics2D = out.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.drawImage(full, 0, 0, w, h, null)
                g.dispose()
                out
            }
        }.getOrNull() ?: return null

        val bmp = makeFromBufferedImage(decoded)
        synchronized(lock) {
            cache[path] = bmp
            cacheBytes += bmp.width.toLong() * bmp.height * 4L
            val it = cache.entries.iterator()
            while (cacheBytes > MAX_CACHE_BYTES && cache.size > 2) {
                val eldest = it.next()
                cacheBytes -= eldest.value.width.toLong() * eldest.value.height * 4L
                it.remove()
            }
        }
        return bmp
    }

    /** Сбросить кэш миниатюр (вызывается при очистке локальной истории). */
    fun evictAll() {
        synchronized(lock) {
            cache.clear()
            cacheBytes = 0L
        }
    }

    /**
     * v8.x: асинхронная миниатюра для Compose.
     *
     * Декодирование идёт в Dispatchers.IO, а пока ImageBitmap не готов,
     * вызов возвращает null — вызывающий код показывает лоадер
     * (CircularProgressIndicator). Из кэша результат возвращается мгновенно.
     */
    @Composable
    fun getAsync(path: String?): ImageBitmap? {
        if (path.isNullOrEmpty()) return null
        synchronized(lock) { cache[path]?.let { return it } }
        var bmp: ImageBitmap? by remember(path) { mutableStateOf(null) }
        var done by remember(path) { mutableStateOf(false) }
        if (!done) {
            LaunchedEffect(path) {
                val decoded = withContext(Dispatchers.IO) { get(path) }
                bmp = decoded
                done = true
            }
        }
        return bmp
    }
}
