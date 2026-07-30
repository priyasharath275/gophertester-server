package com.example.gophertester.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Utility object that provides file I/O operations within the xGTracker app.
 *
 * Supports:
 *  - Writing or appending textual content to files (via ContentResolver or local FS)
 *  - Reading file contents from the local filesystem
 *  - Deleting files by URI or by name/path
 */
object FileManagement {

    private const val TAG = "FileManagement"


    /**
     * Writes the specified [content] to a file.
     *
     * If [receivedUri] is non-null, appends to that existing file.
     * Otherwise, creates a new file in [directory] with name [fileName] and format [fileFormat].
     *
     * @param context      Application context for ContentResolver access.
     * @param receivedUri  Optional URI of an existing file to append to.
     * @param fileName     The desired filename (without extension) for new file creation.
     * @param fileFormat   The file extension/type ("csv", "json", or "txt").
     * @param directory    The relative path under external storage (e.g., "Documents/xGTracker/...").
     * @param content      The string content to write.
     * @param append       Whether to append to the existing file or start a new one.
     * @return The [Uri] of the file that was written to, or `null` if an error occurred.
     */
    fun writeToFile(
        context: Context,
        receivedUri: Uri?,
        fileName: String?,
        fileFormat: String?,
        directory: String?,
        content: String,
        append: Boolean = true
    ): Uri? {
        Log.d(
            TAG,
            "Writing to file: name=$fileName, format=$fileFormat, uri=$receivedUri, append=$append"
        )
        val uri: Uri = receivedUri
            ?: createFile(context, fileName, fileFormat, directory)
            ?: return null

        val mode = if (append) "wa" else "wt"   // append or write-truncate

        return try {
            context.contentResolver.openOutputStream(uri, mode)?.buffered()?.use { output ->
                output.write(content.toByteArray())
                output.flush()
                Log.d(TAG, "Wrote ${content.length} bytes to $uri (mode=$mode)")
            } ?: throw IOException("Unable to open output stream for $uri").also { throw it }
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to file: ${e.message}")
            null
        }
    }


    // ────────────────────────────────────────────────────────────────────────────────────────────
    // Internal helper methods
    // ────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Creates a new file in external storage via MediaStore and returns its URI.
     *
     * @param context     Application context.
     * @param fileName    Filename without extension.
     * @param fileFormat  File extension/type ("csv", "json", "txt").
     * @param directory   Relative external storage path (e.g., '"'Documents/xGTracker/...').
     * @return URI of the newly created file, or 'null' on failure.
     */
    private fun createFile(
        context: Context,
        fileName: String?,
        fileFormat: String?,
        directory: String?
    ): Uri? {
        if (fileName.isNullOrEmpty() || fileFormat.isNullOrEmpty() || directory.isNullOrEmpty()) {
            Log.e(TAG, "createFile: missing parameters")
            return null
        }
        val mimeType = when (fileFormat.lowercase()) {
            "csv" -> "text/csv"
            "json" -> "application/json"
            "txt" -> "text/plain"
            else -> {
                Log.e(TAG, "createFile: unsupported format '$fileFormat'")
                return null
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
        }
        return try {
            context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
        } catch (e: Exception) {
            Log.e(TAG, "createFile: error=${e.message}")
            null
        }
    }
}
