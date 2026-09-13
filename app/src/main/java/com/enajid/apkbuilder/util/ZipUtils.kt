package com.enajid.apkbuilder.util

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

object ZipUtils {

    /**
     * GitHub artifacts download as a zip that contains the APK (plus possibly
     * its metadata). Finds the first .apk entry and returns (name, bytes).
     */
    fun extractFirstApk(zipBytes: ByteArray): Pair<String, ByteArray>? {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".apk")) {
                    val name = entry.name.substringAfterLast('/')
                    return name to zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }
        return null
    }
}
