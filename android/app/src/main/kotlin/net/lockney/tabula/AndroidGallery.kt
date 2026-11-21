package net.lockney.tabula

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.screensaver.gallery.PhotoDetails
import net.lockney.tabula.ha.MediaFetcher

/**
 * The Android side of the photo slideshow (packet P3.8), behind the seams
 * [GalleryEngine] consumes, so the engine's logic is unit-tested on a plain
 * JVM. No storage permission is requested anywhere: the folder source reads
 * paths the OS already lets the app read, and the picker source reads the
 * app's own storage (F-SSV-12).
 */
object AndroidGallery {
    /** The extensions BitmapFactory decodes (HEIF needs API 28; older builds skip those files). */
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

    /** The picker source's storage: the app's external files dir, falling back to internal when absent. */
    fun pickerDir(context: Context): File =
        context.getExternalFilesDir("photos") ?: File(context.filesDir, "photos")

    /**
     * List a source folder's photos, sorted by path (F-SSV-12): recursive when
     * include-subfolders is on, files only, and nothing that is not an image.
     */
    fun listImages(dir: File, recursive: Boolean): List<String> {
        if (!dir.isDirectory) return emptyList()
        val files =
            if (recursive) {
                dir.walkTopDown().filter { it.isFile }
            } else {
                dir.listFiles().orEmpty().filter { it.isFile }.asSequence()
            }
        return files
            .filter { it.extension.lowercase() in IMAGE_EXTENSIONS }
            .map { it.absolutePath }
            .sorted()
            .toList()
    }

    /**
     * Decode one photo at panel resolution (F-SSV-12, the low-RAM target):
     * bounds first, then the largest power-of-two sample that still covers
     * the panel, an exact scale-down when the sampled decode is still larger,
     * and the EXIF rotation applied so photos come out the way they were
     * shot. EXIF details come along for the overlay (F-SSV-12). Null when the
     * file cannot be read or decoded (the slideshow keeps the current photo).
     */
    suspend fun decode(path: String, targetW: Int, targetH: Int): GalleryEngine.DecodedPhoto? =
        withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.isFile) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            var sample = 1
            var w = bounds.outWidth
            var h = bounds.outHeight
            while (w / 2 >= targetW && h / 2 >= targetH) {
                sample *= 2
                w /= 2
                h /= 2
            }
            val sampled = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@withContext null
            val rotated = rotateByExif(sampled, path)
            val bitmap =
                if (rotated.width > targetW || rotated.height > targetH) {
                    val scale = minOf(targetW.toFloat() / rotated.width, targetH.toFloat() / rotated.height)
                    val scaled = Bitmap.createScaledBitmap(
                        rotated,
                        (rotated.width * scale).roundToInt().coerceAtLeast(1),
                        (rotated.height * scale).roundToInt().coerceAtLeast(1),
                        true,
                    )
                    if (scaled !== rotated) rotated.recycle()
                    scaled
                } else {
                    rotated
                }
            GalleryEngine.DecodedPhoto(bitmap, bitmap.width, bitmap.height, exifDetails(path))
        }

    /** Apply the photo's EXIF orientation so a portrait shot shows upright. */
    private fun rotateByExif(bitmap: Bitmap, path: String): Bitmap {
        val orientation = runCatching {
            ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /** The EXIF details overlay's inputs (F-SSV-12): date taken and camera; no location. */
    private fun exifDetails(path: String): PhotoDetails? {
        val exif = runCatching { ExifInterface(path) }.getOrNull() ?: return null
        val date = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
        val make = exif.getAttribute(ExifInterface.TAG_MAKE)
        val model = exif.getAttribute(ExifInterface.TAG_MODEL)
        return PhotoDetails(date, make, model)
    }
}

/**
 * The URL source's fetcher (F-SSV-12): every slide is a fresh fetch through
 * the P2.5 media fetch (no credential, redirects within the ADR 0002 bounds,
 * cancellable), landing in a ring of at most
 * [Constants.GALLERY_URL_HISTORY] files in the cache dir, so edge taps can
 * step back through what was fetched (the ring's oldest file leaves as each
 * new one lands). A failed fetch returns null: the current photo stays and
 * the next tick tries again (F-SSV-12).
 */
class UrlPhotoFetcher(
    private val dir: File,
    private val fetcher: MediaFetcher = MediaFetcher(),
) {
    private val counter = AtomicLong(0)

    init {
        dir.mkdirs()
    }

    /** Drop the previous run's ring: a new slideshow rebuilds its own history. */
    fun reset() {
        counter.set(0)
        dir.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
    }

    suspend fun fetch(url: String): String? {
        val n = counter.getAndIncrement()
        val file = File(dir, "slide-$n.jpg")
        try {
            fetcher.fetch(url, file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            file.delete() // no partial file survives a failed fetch (P2.5's rule)
            return null
        }
        File(dir, "slide-${n - Constants.GALLERY_URL_HISTORY}.jpg").delete()
        return file.absolutePath
    }
}
