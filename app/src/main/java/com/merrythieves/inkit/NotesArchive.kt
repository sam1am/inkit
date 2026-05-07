package com.merrythieves.inkit

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Zip archive of `filesDir/canvases/` — the entire notes corpus (PNG strokes
 * plus index.json). Used to migrate notes across signing-key changes that
 * would otherwise force an uninstall.
 *
 * Entries are written without a parent directory so the archive looks the
 * same regardless of which device produced it; on import we restore them
 * into `filesDir/canvases/`.
 */
object NotesArchive {

    private const val TAG = "NotesArchive"

    fun export(context: Context, dest: Uri): Result {
        val dir = File(context.filesDir, "canvases")
        if (!dir.isDirectory) return Result.Failure("No notes to export")
        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        if (files.isEmpty()) return Result.Failure("No notes to export")
        var entryCount = 0
        return try {
            context.contentResolver.openOutputStream(dest, "w")?.use { os ->
                ZipOutputStream(os.buffered()).use { zos ->
                    for (f in files) {
                        zos.putNextEntry(ZipEntry(f.name))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                        entryCount++
                    }
                }
            } ?: return Result.Failure("Could not open destination")
            Result.Success(entryCount)
        } catch (t: Throwable) {
            Log.w(TAG, "export failed: ${t.message}", t)
            Result.Failure(t.message ?: "Export failed")
        }
    }

    fun importArchive(context: Context, src: Uri): Result {
        val dir = File(context.filesDir, "canvases").apply { mkdirs() }
        val staging = File(context.filesDir, "canvases.import").apply {
            deleteRecursively()
            mkdirs()
        }
        var entryCount = 0
        try {
            context.contentResolver.openInputStream(src)?.use { ins ->
                ZipInputStream(ins.buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        // Defend against zip-slip and nested directories: only
                        // accept flat filenames whose canonical path stays in staging.
                        val name = entry.name
                        if (entry.isDirectory || name.contains('/') || name.contains('\\') || name == "..") {
                            zis.closeEntry()
                            entry = zis.nextEntry
                            continue
                        }
                        val out = File(staging, name)
                        if (!out.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                            zis.closeEntry()
                            entry = zis.nextEntry
                            continue
                        }
                        FileOutputStream(out).use { zis.copyTo(it) }
                        zis.closeEntry()
                        entry = zis.nextEntry
                        entryCount++
                    }
                }
            } ?: return Result.Failure("Could not open archive")

            if (entryCount == 0) {
                staging.deleteRecursively()
                return Result.Failure("Archive is empty")
            }
            if (!File(staging, "index.json").isFile) {
                staging.deleteRecursively()
                return Result.Failure("Archive is missing index.json")
            }

            // Atomic-ish swap: wipe the existing dir and rename staging into place.
            dir.deleteRecursively()
            if (!staging.renameTo(dir)) {
                // renameTo can fail across some FS edge cases; fall back to copy.
                dir.mkdirs()
                staging.listFiles()?.forEach { src2 ->
                    src2.copyTo(File(dir, src2.name), overwrite = true)
                }
                staging.deleteRecursively()
            }
            return Result.Success(entryCount)
        } catch (t: Throwable) {
            Log.w(TAG, "import failed: ${t.message}", t)
            staging.deleteRecursively()
            return Result.Failure(t.message ?: "Import failed")
        }
    }

    sealed class Result {
        data class Success(val entries: Int) : Result()
        data class Failure(val message: String) : Result()
    }
}
