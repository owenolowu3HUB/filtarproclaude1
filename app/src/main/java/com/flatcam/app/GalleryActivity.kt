package com.flatcam.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Media archive: browse everything FilTarPro captured (or every photo/video on the phone),
 * preview it, and delete it from the phone's storage.
 */
class GalleryActivity : Activity() {

    private var items: List<Media> = emptyList()
    private var showAll = false
    private val selected = LinkedHashSet<Uri>()
    private var pendingDelete: List<Uri> = emptyList()
    private var viewerIndex = -1
    private var rec709 = false

    private lateinit var grid: GridView
    private lateinit var titleTv: TextView
    private lateinit var tabLocal: TextView
    private lateinit var tabAll: TextView
    private lateinit var delBtn: TextView
    private lateinit var selBtn: TextView
    private lateinit var viewer: FrameLayout
    private lateinit var vImage: ImageView
    private lateinit var vVideo: VideoView
    private lateinit var vInfo: TextView
    private var cell = 200
    private val pool = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, v: Bitmap) = v.byteCount
    }
    private val amber = Color.parseColor("#FFB300")
    private val red = Color.parseColor("#FF3B30")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun round(color: Int, r: Int = 12) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun chip(t: String, onClick: (TextView) -> Unit) = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(Color.WHITE); setPadding(dp(12), dp(7), dp(12), dp(7))
        gravity = Gravity.CENTER; background = round(0x44FFFFFF); setOnClickListener { onClick(this) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Color.parseColor("#0B0B0D"))
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val root = FrameLayout(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(0xFF15161A.toInt()) }
        bar.addView(chip("BACK") { finish() })
        titleTv = TextView(this).apply { textSize = 15f; setTextColor(Color.WHITE); setPadding(dp(14), 0, dp(10), 0) }
        bar.addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f))
        tabLocal = chip("FILTARPRO") { setAll(false) }
        tabAll = chip("ALL PHONE") { setAll(true) }
        bar.addView(tabLocal); bar.addView(tabAll, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        selBtn = chip("SELECT ALL") { if (selected.size == items.size) selected.clear() else { selected.clear(); items.forEach { selected.add(it.uri) } }; refreshUi() }
        bar.addView(selBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(14) })
        delBtn = chip("DELETE") { confirmDelete(selected.toList()) }
        bar.addView(delBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        col.addView(bar, LinearLayout.LayoutParams(-1, -2))

        val sw = resources.displayMetrics.widthPixels
        val cols = max(3, sw / dp(130))
        cell = sw / cols
        grid = GridView(this).apply {
            numColumns = cols; horizontalSpacing = 0; verticalSpacing = 0; setBackgroundColor(Color.TRANSPARENT)
            adapter = adapterImpl
            setOnItemClickListener { _, _, pos, _ ->
                val m = items[pos]
                if (selected.isNotEmpty()) toggle(m.uri) else openViewer(pos)
            }
            setOnItemLongClickListener { _, _, pos, _ -> toggle(items[pos].uri); true }
        }
        col.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // ---- full-screen viewer
        viewer = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE; isClickable = true }
        vImage = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        vVideo = VideoView(this)
        viewer.addView(vImage, FrameLayout.LayoutParams(-1, -1))
        viewer.addView(vVideo, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        val vbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(0x99000000.toInt()) }
        vbar.addView(chip("CLOSE") { closeViewer() })
        vInfo = TextView(this).apply { textSize = 11f; setTextColor(Color.WHITE); setPadding(dp(12), 0, dp(8), 0) }
        vbar.addView(vInfo, LinearLayout.LayoutParams(0, -2, 1f))
        vbar.addView(chip("PREV") { stepViewer(-1) })
        vbar.addView(chip("NEXT") { stepViewer(1) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        vbar.addView(chip("709 LOOK") { b ->
            rec709 = !rec709
            b.background = round(if (rec709) amber else 0x44FFFFFF)
            applyLook()
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        val del = chip("DELETE") { if (viewerIndex in items.indices) confirmDelete(listOf(items[viewerIndex].uri)) }
        del.background = round(red)
        vbar.addView(del, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        viewer.addView(vbar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        root.addView(viewer, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        reload()
    }

    override fun onBackPressed() {
        when {
            viewer.visibility == View.VISIBLE -> closeViewer()
            selected.isNotEmpty() -> { selected.clear(); refreshUi() }
            else -> super.onBackPressed()
        }
    }

    // ---------------------------------------------------------------- data
    private fun setAll(all: Boolean) {
        if (all && !hasMediaPermission()) {
            val perms = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            requestPermissions(perms, 7)
            return
        }
        showAll = all; selected.clear(); reload()
    }

    private fun hasMediaPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (code == 7 && res.isNotEmpty() && res.all { it == PackageManager.PERMISSION_GRANTED }) { showAll = true; selected.clear(); reload() }
        else if (code == 7) Toast.makeText(this, "Permission needed to list all media on the phone", Toast.LENGTH_LONG).show()
    }

    private fun reload() {
        pool.execute {
            val list = MediaRepo.query(this, showAll)
            runOnUiThread { items = list; refreshUi() }
        }
    }

    private fun refreshUi() {
        titleTv.text = (if (showAll) "All photos and videos" else "FilTarPro media") + "  (${items.size})" +
            if (selected.isNotEmpty()) "   ${selected.size} selected" else ""
        tabLocal.background = round(if (!showAll) amber else 0x44FFFFFF); tabLocal.setTextColor(if (!showAll) Color.BLACK else Color.WHITE)
        tabAll.background = round(if (showAll) amber else 0x44FFFFFF); tabAll.setTextColor(if (showAll) Color.BLACK else Color.WHITE)
        delBtn.visibility = if (selected.isNotEmpty()) View.VISIBLE else View.GONE
        delBtn.background = round(red)
        selBtn.visibility = if (items.isNotEmpty()) View.VISIBLE else View.GONE
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun toggle(u: Uri) { if (!selected.remove(u)) selected.add(u); refreshUi() }

    // ---------------------------------------------------------------- grid
    private val adapterImpl = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val m = items[p]
            val f = (convert as? FrameLayout) ?: FrameLayout(this@GalleryActivity).apply {
                layoutParams = AbsListView.LayoutParams(cell, cell)
                setPadding(dp(1), dp(1), dp(1), dp(1))
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF222222.toInt()) }, FrameLayout.LayoutParams(-1, -1))
                addView(TextView(context).apply { textSize = 10f; setTextColor(Color.WHITE); setPadding(dp(5), dp(2), dp(5), dp(2)); background = round(0x99000000.toInt(), 6) },
                    FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(4), dp(4)) })
                addView(View(context).apply { setBackgroundColor(0x66FFB300) }, FrameLayout.LayoutParams(-1, -1))
            }
            val iv = f.getChildAt(0) as ImageView
            val badge = f.getChildAt(1) as TextView
            val mark = f.getChildAt(2)
            iv.setImageBitmap(null)
            iv.tag = m.uri.toString()
            val key = m.uri.toString()
            val hit = cache.get(key)
            if (hit != null) iv.setImageBitmap(hit) else pool.execute {
                try {
                    val b = contentResolver.loadThumbnail(m.uri, Size(cell, cell), null)
                    cache.put(key, b)
                    runOnUiThread { if (iv.tag == key) iv.setImageBitmap(b) }
                } catch (_: Exception) {}
            }
            badge.visibility = if (m.isVideo) View.VISIBLE else View.GONE
            badge.text = "▶ " + fmtDur(m.durMs)
            mark.visibility = if (selected.contains(m.uri)) View.VISIBLE else View.GONE
            return f
        }
    }

    private fun fmtDur(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    // ---------------------------------------------------------------- viewer
    private fun openViewer(pos: Int) {
        viewerIndex = pos
        viewer.visibility = View.VISIBLE
        showCurrent()
    }

    private fun stepViewer(d: Int) {
        if (items.isEmpty()) return
        viewerIndex = (viewerIndex + d + items.size) % items.size
        showCurrent()
    }

    private fun showCurrent() {
        val m = items.getOrNull(viewerIndex) ?: return
        vVideo.stopPlayback()
        vInfo.text = m.name + "\n${m.width}x${m.height}  ${"%.1f".format(m.size / 1048576.0)} MB  " +
            SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault()).format(Date(m.dateSec * 1000)) + if (m.isVideo) "  ${fmtDur(m.durMs)}" else ""
        if (m.isVideo) {
            vImage.visibility = View.GONE; vVideo.visibility = View.VISIBLE
            vVideo.setMediaController(MediaController(this).also { it.setAnchorView(vVideo) })
            vVideo.setVideoURI(m.uri)
            vVideo.setOnPreparedListener { it.isLooping = false; vVideo.start() }
            vVideo.setOnErrorListener { _, _, _ -> Toast.makeText(this, "Cannot play this video here", Toast.LENGTH_SHORT).show(); true }
        } else {
            vVideo.visibility = View.GONE; vImage.visibility = View.VISIBLE
            vImage.setImageBitmap(null)
            pool.execute {
                val bmp: Bitmap? = try {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, m.uri)) { dec, info, _ ->
                        val s = max(1, max(info.size.width, info.size.height) / 2400)
                        dec.setTargetSampleSize(s)
                    }
                } catch (_: Throwable) {
                    try { contentResolver.loadThumbnail(m.uri, Size(2000, 2000), null) } catch (_: Throwable) { null }
                }
                runOnUiThread { if (viewerIndex in items.indices && items[viewerIndex].uri == m.uri) { vImage.setImageBitmap(bmp); applyLook() } }
            }
        }
    }

    /** Flat / log footage looks grey on purpose. This previews a normal Rec.709-style contrast so you can judge the shot. */
    private fun applyLook() {
        if (!rec709) { vImage.colorFilter = null; return }
        val c = 1.45f
        val t = (-0.5f * c + 0.5f) * 255f
        val contrast = ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f))
        val sat = ColorMatrix().apply { setSaturation(1.3f) }
        contrast.postConcat(sat)
        vImage.colorFilter = ColorMatrixColorFilter(contrast)
    }

    private fun closeViewer() {
        vVideo.stopPlayback()
        viewer.visibility = View.GONE
        viewerIndex = -1
    }

    // ---------------------------------------------------------------- delete
    private fun confirmDelete(list: List<Uri>) {
        if (list.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 30) { deleteNow(list); return }   // the system shows its own confirmation
        AlertDialog.Builder(this).setMessage("Delete ${list.size} item(s) permanently from this phone?")
            .setPositiveButton("Delete") { _, _ -> deleteNow(list) }.setNegativeButton("Cancel", null).show()
    }

    private fun deleteNow(list: List<Uri>) {
        pendingDelete = list
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val pi = MediaStore.createDeleteRequest(contentResolver, list)
                startIntentSenderForResult(pi.intentSender, 41, null, 0, 0, 0)
            } else {
                for (u in list) {
                    try { contentResolver.delete(u, null, null) }
                    catch (e: RecoverableSecurityException) {
                        startIntentSenderForResult(e.userAction.actionIntent.intentSender, 42, null, 0, 0, 0)
                        return
                    }
                }
                afterDelete()
            }
        } catch (e: Exception) { Toast.makeText(this, "Delete failed: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun afterDelete() {
        cache.evictAll()
        selected.clear()
        if (viewer.visibility == View.VISIBLE) closeViewer()
        reload()
        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 41 && resultCode == RESULT_OK) afterDelete()
        else if (requestCode == 42 && resultCode == RESULT_OK) deleteNow(pendingDelete)
        else if (requestCode == 41 || requestCode == 42) reload()
    }

    override fun onDestroy() {
        pool.shutdown()
        super.onDestroy()
    }
}
