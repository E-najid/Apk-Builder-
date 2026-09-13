package com.enajid.apkbuilder.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Saves built APKs into the shared Downloads folder (scoped-storage friendly). */
object ApkFileUtils {

    /**
     * Writes [bytes] to Downloads/APK Builder/ and returns the content Uri.
     * On Android 9 and below this needs the legacy WRITE_EXTERNAL_STORAGE
     * permission; if it isn't granted we quietly return null and the app falls
     * back to sharing from its private storage.
     */
    fun saveToPublicDownloads(context: Context, displayName: String, bytes: ByteArray): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/APK Builder"
                    )
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
                uri
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                val file = File(dir, displayName)
                file.writeBytes(bytes)
                Uri.fromFile(file)
            }
        } catch (_: Exception) {
            null
        }
    }
}
