package com.metrolauncher.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import java.util.concurrent.ConcurrentHashMap

/**
 * Enumerates and loads images from a "document tree URI" chosen by the user 
 * (typically a folder granted via ACTION_OPEN_DOCUMENT_TREE).
 *
 * The listing is cached in memory to avoid re-scanning the folder at each tick 
 * of the slideshow.
 */
object GalleryProvider {

    /** Cache: documentTreeUri -> list of image URIs. */
    private val cache = ConcurrentHashMap<String, List<Uri>>()

    /** Forces re-listing on the next call. */
    fun invalidate(treeUri: String) { cache.remove(treeUri) }

    /**
     * Complete list of image URIs inside the document tree. Only one level — no 
     * recursion into subfolders (for now).
     */
    fun listImages(ctx: Context, treeUriString: String): List<Uri> {
        cache[treeUriString]?.let { return it }

        val treeUri = runCatching { Uri.parse(treeUriString) }.getOrNull() ?: return emptyList()
        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
        }.getOrNull() ?: return emptyList()

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val result = mutableListOf<Uri>()
        runCatching {
            ctx.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue
                    val mime = c.getString(1).orEmpty()
                    if (mime.startsWith("image/")) {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        result.add(docUri)
                    }
                }
            }
        }
        cache[treeUriString] = result
        return result
    }

    /**
     * Loads a reduced bitmap from a URI. targetSize is the maximum dimension of the 
     * longest side (we use decode inSampleSize to avoid loading full resolution).
     */
    fun loadBitmap(ctx: Context, uri: Uri, targetSize: Int): Bitmap? = runCatching {
        // 1. read dimensions without decoding pixels
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, boundsOpts)
        }
        val w = boundsOpts.outWidth
        val h = boundsOpts.outHeight
        if (w <= 0 || h <= 0) return@runCatching null

        // 2. Calculate inSampleSize
        var sample = 1
        while (maxOf(w, h) / sample > targetSize * 1.5) sample *= 2

        // 3. Decode
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }.getOrNull()
}
