package com.flatcam.app

import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class Media(
    val uri: Uri,
    val isVideo: Boolean,
    val name: String,
    val dateSec: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val durMs: Long
)

object MediaRepo {
    /** all = false: only files in the FilTarPro folders; all = true: every photo and video on the phone (needs media permission). */
    fun query(ctx: Context, all: Boolean): List<Media> {
        val out = ArrayList<Media>()
        for (video in listOf(false, true)) {
            val coll = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val proj = ArrayList<String>().apply {
                add(MediaStore.MediaColumns._ID)
                add(MediaStore.MediaColumns.DISPLAY_NAME)
                add(MediaStore.MediaColumns.DATE_ADDED)
                add(MediaStore.MediaColumns.SIZE)
                add(MediaStore.MediaColumns.WIDTH)
                add(MediaStore.MediaColumns.HEIGHT)
                if (video) add(MediaStore.MediaColumns.DURATION)
            }.toTypedArray()
            val sel = if (all) null else "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            val args = if (all) null else arrayOf("%FilTarPro%", "%FlatCam%")
            try {
                ctx.contentResolver.query(coll, proj, sel, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                    val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val iW = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
                    val iH = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
                    val iDur = if (video) c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION) else -1
                    while (c.moveToNext()) {
                        out.add(
                            Media(
                                Uri.withAppendedPath(coll, c.getLong(iId).toString()), video,
                                c.getString(iName) ?: "", c.getLong(iDate), c.getLong(iSize),
                                c.getInt(iW), c.getInt(iH), if (iDur >= 0) c.getLong(iDur) else 0L
                            )
                        )
                    }
                }
            } catch (_: Exception) { /* permission missing or store unavailable */ }
        }
        return out.sortedByDescending { it.dateSec }
    }
}
