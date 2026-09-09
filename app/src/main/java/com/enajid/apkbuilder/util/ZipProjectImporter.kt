package com.enajid.apkbuilder.util

import android.content.Context
import android.net.Uri
import com.enajid.apkbuilder.data.TemplateRenderer
import com.enajid.apkbuilder.domain.UploadFilter
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Result of importing (copying + extracting + scanning) a picked zip. */
sealed interface ImportOutcome {
    /** [scanFiles] are small text/icon files for detection; [rootDir] is the extracted project root. */
    data class Success(
        val scanFiles: Map<String, ByteArray>,
        val rootDir: File,
        val fileCount: Int,
        val totalBytes: Long,
    ) : ImportOutcome

    data class TooLarge(val sizeBytes: Long) : ImportOutcome
    data class Failed(val message: String) : ImportOutcome
}

/**
 * Android side of zip import: copies the picked file into the cache dir
 * (enforcing the size limit), extracts it safely (no path traversal, no
 * build outputs/caches), and hands the scanner the files worth reading.
 *
 * The zip's contents are never executed — they are only copied and later
 * pushed to GitHub, where any build happens inside GitHub's own sandbox.
 */
object ZipProjectImporter {

    const val MAX_ZIP_BYTES: Long = 50L * 1024 * 1024
    const val MAX_PUSH_FILES = 500
    const val MAX_PUSH_BYTES: Long = 60L * 1024 * 1024

    private const val MAX_EXTRACTED_BYTES: Long = 120L * 1024 * 1024
    private const val MAX_ENTRIES = 3000
    private const val MAX_FILE_BYTES: Long = 25L * 1024 * 1024
    private const val MAX_SCAN_FILE_BYTES = 2L * 1024 * 1024

    fun import(context: Context, uri: Uri): ImportOutcome {
        val workDir = File(context.cacheDir, "zip-import")
        workDir.mkdirs()
        val zipCopy = File(workDir, "upload.zip")
        var extracted: File? = null
        try {
            // 1. Copy the picked uri into the cache, enforcing the zip size cap.
            val size = try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    zipCopy.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_ZIP_BYTES) return ImportOutcome.TooLarge(total)
                            output.write(buffer, 0, read)
                        }
                        total
                    }
                } ?: return ImportOutcome.Failed("Couldn’t read the selected file.")
            } catch (e: IOException) {
                return ImportOutcome.Failed("Couldn’t read the selected file: ${e.message ?: "I/O error"}")
            }

            // 2. Open the zip and extract it safely.
            val zipFile = try {
                ZipFile(zipCopy)
            } catch (e: ZipException) {
                return ImportOutcome.Failed("That file isn’t a valid zip archive.")
            } catch (e: IOException) {
                return ImportOutcome.Failed("Couldn’t open the zip: ${e.message ?: "I/O error"}")
            }

            zipFile.use { zip ->
                extracted = File(workDir, "extracted-${System.currentTimeMillis()}")
                extracted!!.mkdirs()
                var totalBytes = 0L
                var entries = 0

                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) {
                    val entry: ZipEntry = enumeration.nextElement()
                    if (entry.isDirectory) continue
                    val safeName = sanitize(entry.name)
                        ?: return ImportOutcome.Failed("The zip contains an unsafe path: “${entry.name}”")
                    if (UploadFilter.isExcluded(safeName)) continue

                    entries++
                    if (entries > MAX_ENTRIES) {
                        return ImportOutcome.Failed(
                            "This zip has too many files (over $MAX_ENTRIES even after excluding " +
                                "build outputs and caches)."
                        )
                    }
                    if (entry.size > MAX_FILE_BYTES) {
                        return ImportOutcome.Failed(
                            "“$safeName” is larger than 25 MB — too big to upload to GitHub from here."
                        )
                    }

                    val target = File(extracted, safeName)
                    target.parentFile?.mkdirs()
                    try {
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().use { output ->
                                totalBytes += input.copyTo(output)
                            }
                        }
                    } catch (e: IOException) {
                        return ImportOutcome.Failed("Couldn’t extract “$safeName”: ${e.message ?: "I/O error"}")
                    }
                    if (totalBytes > MAX_EXTRACTED_BYTES) {
                        return ImportOutcome.Failed(
                            "The extracted project is larger than 120 MB — too big to upload."
                        )
                    }
                }
            }

            zipCopy.delete()

            // 3. Resolve the real project root (file managers often add a
            //    single wrapper folder) and gather the files worth scanning.
            val projectRoot = resolveProjectRoot(extracted!!)
            val scanFiles = collectScanFiles(projectRoot)
            val (count, bytes) = countPushable(projectRoot)
            if (count == 0) {
                return ImportOutcome.Failed(
                    "After excluding build outputs and caches there were no files left to upload."
                )
            }
            return ImportOutcome.Success(
                scanFiles = scanFiles,
                rootDir = projectRoot,
                fileCount = count,
                totalBytes = bytes,
            )
        } catch (e: IOException) {
            extracted?.deleteRecursively()
            return ImportOutcome.Failed("Importing the zip failed: ${e.message ?: "I/O error"}")
        } catch (e: Exception) {
            extracted?.deleteRecursively()
            return ImportOutcome.Failed("Importing the zip failed: ${e.message ?: "unexpected error"}")
        }
    }

    /**
     * Builds the final push list from the extracted project. Throws
     * [IllegalStateException] with a human-readable message if limits are
     * exceeded — call this only after the user confirms creation.
     */
    fun buildPushFiles(rootDir: File): List<TemplateRenderer.RenderedFile> {
        val files = mutableListOf<TemplateRenderer.RenderedFile>()
        var totalBytes = 0L
        rootDir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val rel = file.relativeTo(rootDir).invariantSeparatorsPath
                if (UploadFilter.isExcluded(rel)) return@forEach
                if (files.size >= MAX_PUSH_FILES) {
                    throw IllegalStateException(
                        "This project has more than $MAX_PUSH_FILES files (after excluding build " +
                            "outputs) — too many to upload from the phone."
                    )
                }
                totalBytes += file.length()
                if (totalBytes > MAX_PUSH_BYTES) {
                    throw IllegalStateException(
                        "This project is larger than 60 MB (after excluding build outputs) — " +
                            "too big to upload from the phone."
                    )
                }
                files += TemplateRenderer.RenderedFile(
                    path = rel,
                    content = file.readBytes(),
                    executable = rel == "gradlew" || rel.endsWith("/gradlew"),
                )
            }
        return files
    }

    fun cleanup(rootDir: File?) {
        rootDir?.deleteRecursively()
        File(rootDir?.parentFile ?: return, "upload.zip").delete()
    }

    /** Rejects absolute paths and traversal ("../") — classic zip-slip guard. */
    private fun sanitize(name: String): String? {
        val normalized = name.replace('\\', '/')
        if (normalized.startsWith("/")) return null
        val parts = normalized.split('/')
        if (parts.any { it == ".." || it.isEmpty() }) return null
        if (normalized.isBlank()) return null
        return normalized
    }

    /** If every entry sits in one wrapper folder, that folder is the project root. */
    private fun resolveProjectRoot(extracted: File): File {
        val children = extracted.listFiles()?.filter { it.name != ".DS_Store" } ?: return extracted
        if (children.size == 1 && children[0].isDirectory) return children[0]
        return extracted
    }

    /** Reads the small text/icon files the scanner needs — never the whole project. */
    private fun collectScanFiles(root: File): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        root.walkTopDown()
            .filter { it.isFile && it.length() in 1..MAX_SCAN_FILE_BYTES }
            .forEach { file ->
                val rel = file.relativeTo(root).invariantSeparatorsPath
                val name = file.name
                val isInteresting = rel == "package.json" || rel == "pubspec.yaml" ||
                    rel.startsWith("android/") && (rel == "android/package.json" || rel == "android/pubspec.yaml") ||
                    name == "AndroidManifest.xml" ||
                    name == "strings.xml" || name == "build.gradle" || name == "build.gradle.kts" ||
                    name == "settings.gradle" || name == "settings.gradle.kts" ||
                    Regex("""(^|/)res/mipmap-[^/]+/ic_launcher\.(png|webp)$""").containsMatchIn(rel) ||
                    (name == "package.json" && rel.substringBeforeLast('/').isEmpty())
                if (isInteresting && !out.containsKey(rel)) {
                    runCatching { out[rel] = file.readBytes() }
                }
            }
        return out
    }

    private fun countPushable(root: File): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        root.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val rel = file.relativeTo(root).invariantSeparatorsPath
                if (UploadFilter.isExcluded(rel)) return@forEach
                count++
                bytes += file.length()
            }
        return count to bytes
    }
}
