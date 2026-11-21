package net.lockney.tabula

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The picker source's photo picker (F-SSV-12, packet P3.8): the system SAF
 * document picker, so no broad storage permission is ever requested, with
 * the picked photos copied into the app's own storage — they survive reboots
 * and join the playlist the next time the screensaver starts (F-SSV-12).
 *
 * Launched from the service's ongoing notification's action: the kiosk has
 * no other device-side UI to hang it on (ADR 0014). Plain framework activity
 * on purpose (ADR 0013); the classic result API is used because
 * registerForActivityResult needs androidx, which this app does not carry.
 */
class PhotoPickActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            // restored after a process death: the picker result is gone
            finish()
            return
        }
        @Suppress("DEPRECATION")
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent.createChooser(pick, "Pick photos"), REQUEST)
    }

    @Deprecated("DeprecatedInJava", ReplaceWith("registerForActivityResult"))
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST) return finish()
        val uris = buildList {
            data?.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) add(clip.getItemAt(i).uri)
            }
            if (isEmpty()) data?.data?.let { add(it) }
        }
        if (resultCode != RESULT_OK || uris.isEmpty()) return finish()
        scope.launch { copy(uris) }
    }

    /** Copy each picked photo into the app's storage; the playlist picks them up next start. */
    private suspend fun copy(uris: List<Uri>) {
        val dir = AndroidGallery.pickerDir(applicationContext).apply { mkdirs() }
        var copied = 0
        for (uri in uris) {
            if (runCatching { copyInto(dir, uri) }.getOrDefault(false)) copied++
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(applicationContext, "Added $copied photo(s) to Tabula", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun copyInto(dir: File, uri: Uri): Boolean {
        val name = displayName(uri).ifBlank { "photo-${System.nanoTime()}.jpg" }
        val target = uniqueTarget(dir, name)
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return false
        return true
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull().orEmpty()

    /** Never overwrite: a re-picked photo lands beside the earlier copy. */
    private fun uniqueTarget(dir: File, name: String): File {
        var file = File(dir, name)
        if (!file.exists()) return file
        val base = file.nameWithoutExtension
        val ext = file.extension
        var n = 1
        while (file.exists()) {
            file = File(dir, "$base-$n.${ext.ifBlank { "jpg" }}")
            n++
        }
        return file
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val REQUEST = 1
    }
}
