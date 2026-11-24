package com.flatcam.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Big round shutter / record button. */
class RecordButton(c: Context) : View(c) {
    var recording = false
        set(v) { field = v; invalidate() }
    var photo = false
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 4f * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(cv: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - ring.strokeWidth
        cv.drawCircle(cx, cy, r, ring)
        if (recording) {
            fill.color = Color.parseColor("#FF3B30")
            val s = r * 0.48f
            cv.drawRoundRect(cx - s, cy - s, cx + s, cy + s, s * 0.3f, s * 0.3f, fill)
        } else {
            fill.color = if (photo) Color.WHITE else Color.parseColor("#FF3B30")
            cv.drawCircle(cx, cy, r * 0.78f, fill)
        }
    }
}

/** Faces, tracked-subject brackets and the tap ripple. Everything is in view pixels. */
class OverlayView(c: Context) : View(c) {
    val faceRects = ArrayList<RectF>()
    var primary = -1
    var target: RectF? = null
    var targetState = 0 // 0 none, 1 face lock, 2 focus lock, 3 lost, 4 focusing
    var meter: RectF? = null
    /** Focus marks (A/B/C) placed on subjects; x,y in view pixels. */
    class MarkPt(val label: String, val x: Float, val y: Float, val active: Boolean)
    val marks = ArrayList<MarkPt>()
    /** Gamepad cursor in view pixels, null = hidden. */
    var cursor: PointF? = null

    // ---- monitor layers (all drawn inside the picture area `img`)
    val img = RectF()
    var lutBmp: Bitmap? = null
    var focusBmp: Bitmap? = null
    var frameRatio = 0f          // 0 = no frame mask, else wanted width / height
    var barAlpha = 0.85f
    var showLevel = true
    var roll = 0f
    var pitch = 0f
    var showScore = false
    var score = 0f
    var hold = 0f
    private val filt = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeCap = Paint.Cap.ROUND
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f * d; color = Color.WHITE }
    private var rx = 0f
    private var ry = 0f
    private var ripple = 0f
    private var anim: ValueAnimator? = null

    fun ripple(x: Float, y: Float) {
        rx = x; ry = y
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            addUpdateListener { ripple = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun brackets(cv: Canvas, r: RectF) {
        val l = min(r.width(), r.height()) * 0.28f
        cv.drawLine(r.left, r.top, r.left + l, r.top, p); cv.drawLine(r.left, r.top, r.left, r.top + l, p)
        cv.drawLine(r.right, r.top, r.right - l, r.top, p); cv.drawLine(r.right, r.top, r.right, r.top + l, p)
        cv.drawLine(r.left, r.bottom, r.left + l, r.bottom, p); cv.drawLine(r.left, r.bottom, r.left, r.bottom - l, p)
        cv.drawLine(r.right, r.bottom, r.right - l, r.bottom, p); cv.drawLine(r.right, r.bottom, r.right, r.bottom - l, p)
    }

    private fun frameRect(): RectF? {
        if (frameRatio <= 0f || img.width() <= 0f) return null
        val ar = img.width() / img.height()
        if (abs(frameRatio - ar) / ar < 0.012f) return null
        val fw: Float; val fh: Float
        if (frameRatio > ar) { fw = img.width(); fh = fw / frameRatio } else { fh = img.height(); fw = fh * frameRatio }
        return RectF(img.centerX() - fw / 2f, img.centerY() - fh / 2f, img.centerX() + fw / 2f, img.centerY() + fh / 2f)
    }

    private fun drawLevel(cv: Canvas) {
        val cx = img.centerX(); val cy = img.centerY()
        val lvl = abs(roll) < 0.8f
        val col = when { lvl -> Color.parseColor("#4CD964"); abs(roll) < 3f -> Color.parseColor("#FFC107"); else -> Color.parseColor("#FF3B30") }
        val a = if (lvl) 190 else 140
        p.color = Color.argb(a, 255, 255, 255); p.strokeWidth = 1.5f * d
        cv.drawLine(cx - 120f * d, cy, cx - 82f * d, cy, p); cv.drawLine(cx + 82f * d, cy, cx + 120f * d, cy, p)
        p.color = Color.argb(a, Color.red(col), Color.green(col), Color.blue(col)); p.strokeWidth = 2f * d
        cv.save(); cv.rotate(roll, cx, cy)
        cv.drawLine(cx - 76f * d, cy, cx - 14f * d, cy, p); cv.drawLine(cx + 14f * d, cy, cx + 76f * d, cy, p)
        cv.restore()
        val py = cy - (pitch.coerceIn(-12f, 12f)) * 3f * d
        val plevel = abs(pitch) < 1.0f
        p.color = Color.argb(a, if (plevel) 76 else 255, if (plevel) 217 else 255, if (plevel) 100 else 255)
        cv.drawCircle(cx, py, 5f * d, p)
        p.color = Color.argb(70, 255, 255, 255); p.strokeWidth = 1f * d
        cv.drawCircle(cx, cy, 5f * d, p)
        p.strokeWidth = 2f * d
        txt.color = Color.argb(150, 255, 255, 255)
        val t = "%+.1f  %+.1f".format(roll, pitch)
        cv.drawText(t, cx - txt.measureText(t) / 2f, cy + 44f * d, txt)
    }

    private fun drawScore(cv: Canvas) {
        val w = 110f * d; val x = img.centerX() - w / 2f; val y = img.centerY() + 62f * d; val hh = 4f * d
        fill.color = Color.argb(70, 0, 0, 0); cv.drawRoundRect(x, y, x + w, y + hh, hh, hh, fill)
        fill.color = if (score > 0.75f) Color.argb(200, 76, 217, 100) else Color.argb(170, 255, 255, 255)
        cv.drawRoundRect(x, y, x + w * score.coerceIn(0.02f, 1f), y + hh, hh, hh, fill)
        fill.color = Color.argb(220, 255, 193, 7)
        val hx = x + w * hold.coerceIn(0f, 1f)
        cv.drawRect(hx - 1f * d, y - 2f * d, hx + 1f * d, y + hh + 2f * d, fill)
    }

    override fun onDraw(cv: Canvas) {
        if (img.width() > 0f) {
            lutBmp?.let { cv.drawBitmap(it, null, img, filt) }
            focusBmp?.let { cv.drawBitmap(it, null, img, filt) }
            frameRect()?.let { f ->
                fill.color = Color.argb((barAlpha * 255).toInt().coerceIn(0, 255), 0, 0, 0)
                cv.drawRect(img.left, img.top, img.right, f.top, fill); cv.drawRect(img.left, f.bottom, img.right, img.bottom, fill)
                cv.drawRect(img.left, f.top, f.left, f.bottom, fill); cv.drawRect(f.right, f.top, img.right, f.bottom, fill)
                p.color = Color.argb(70, 255, 255, 255); p.strokeWidth = 1f * d
                cv.drawRect(f, p); p.strokeWidth = 2f * d
            }
        }
        for ((i, r) in faceRects.withIndex()) {
            val isPrimary = i == primary && targetState == 0
            p.color = if (isPrimary) Color.parseColor("#FFC107") else Color.argb(150, 255, 255, 255)
            brackets(cv, r)
        }
        target?.let { t ->
            p.color = when (targetState) {
                3 -> Color.parseColor("#FF3B30")
                4 -> Color.parseColor("#FFC107")
                else -> Color.parseColor("#4CD964")
            }
            brackets(cv, t)
            txt.color = p.color
            val label = when (targetState) { 1 -> "FACE LOCK"; 2 -> "FOCUS LOCK"; 4 -> "FOCUSING"; else -> "LOST" }
            cv.drawText(label, t.left, max(t.top - 5f * d, 12f * d), txt)
        }
        meter?.let { m ->
            p.color = Color.parseColor("#4DA3FF")
            cv.drawCircle(m.centerX(), m.centerY(), max(m.width(), m.height()) / 2f, p)
            cv.drawLine(m.centerX() - 6f * d, m.centerY(), m.centerX() + 6f * d, m.centerY(), p)
            cv.drawLine(m.centerX(), m.centerY() - 6f * d, m.centerX(), m.centerY() + 6f * d, p)
            txt.color = p.color
            cv.drawText("METER", m.left, max(m.top - 5f * d, 12f * d), txt)
        }
        for (m in marks) {
            p.color = if (m.active) Color.parseColor("#4CD964") else Color.parseColor("#FFB300")
            cv.drawCircle(m.x, m.y, 13f * d, p)
            txt.color = p.color
            val w = txt.measureText(m.label)
            cv.drawText(m.label, m.x - w / 2f, m.y + 4f * d, txt)
        }
        cursor?.let { c ->
            p.color = Color.WHITE
            cv.drawCircle(c.x, c.y, 16f * d, p)
            cv.drawLine(c.x - 24f * d, c.y, c.x - 8f * d, c.y, p); cv.drawLine(c.x + 8f * d, c.y, c.x + 24f * d, c.y, p)
            cv.drawLine(c.x, c.y - 24f * d, c.x, c.y - 8f * d, p); cv.drawLine(c.x, c.y + 8f * d, c.x, c.y + 24f * d, p)
        }
        if (ripple > 0f && ripple < 1f) {
            p.color = Color.argb(((1f - ripple) * 220).toInt(), 255, 255, 255)
            cv.drawCircle(rx, ry, (22f + 36f * ripple) * d, p)
        }
        if (img.width() > 0f) {
            if (showLevel) drawLevel(cv)
            if (showScore) drawScore(cv)
        }
    }
}

/** Live luma histogram with clipping / crushed-shadow warnings. */
class HistogramView(c: Context) : View(c) {
    private var bins = IntArray(48)
    private var clip = 0f
    private var crush = 0f
    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)

    fun update(b: IntArray, clipFrac: Float, crushFrac: Float) {
        bins = b; clip = clipFrac; crush = crushFrac; invalidate()
    }

    override fun onDraw(cv: Canvas) {
        cv.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 6f * d, 6f * d, bg)
        val mx = max(1, bins.maxOrNull() ?: 1)
        val bw = width.toFloat() / bins.size
        for (i in bins.indices) {
            val h = height * 0.9f * (bins[i].toFloat() / mx)
            bar.color = when {
                i >= bins.size - 2 && clip > 0.01f -> Color.parseColor("#FF3B30")
                i <= 1 && crush > 0.05f -> Color.parseColor("#4DA3FF")
                else -> Color.argb(190, 255, 255, 255)
            }
            cv.drawRect(i * bw, height - h, (i + 1) * bw - 1f, height.toFloat(), bar)
        }
    }
}

/** Rule-of-thirds grid. */
class ThirdsGrid(c: Context) : View(c) {
    private val p = Paint().apply { color = Color.argb(90, 255, 255, 255); strokeWidth = 1f }
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        for (i in 1..2) {
            cv.drawLine(w * i / 3f, 0f, w * i / 3f, h, p)
            cv.drawLine(0f, h * i / 3f, w, h * i / 3f, p)
        }
    }
}


/** Audio level meter (peak) shown while recording. */
class VuView(c: Context) : View(c) {
    var level = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        cv.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 3f * d, 3f * d, bg)
        bar.color = when {
            level > 0.93f -> Color.parseColor("#FF3B30")
            level > 0.75f -> Color.parseColor("#FFC107")
            else -> Color.parseColor("#4CD964")
        }
        cv.drawRoundRect(0f, 0f, width * level, height.toFloat(), 3f * d, 3f * d, bar)
    }
}


/** Small picture of an aspect ratio for the ratio grid. */
class RatioThumb(c: android.content.Context, private val ratio: Float, private val sel: Boolean) : View(c) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val aw = width - 4f * d; val ah = height - 2f * d
        val w: Float; val h: Float
        if (ratio >= aw / ah) { w = aw; h = aw / ratio } else { h = ah; w = ah * ratio }
        val l = (width - w) / 2f; val t = (height - h) / 2f
        p.style = Paint.Style.FILL; p.color = if (sel) Color.argb(80, 255, 179, 0) else Color.argb(50, 255, 255, 255)
        cv.drawRoundRect(l, t, l + w, t + h, 3f * d, 3f * d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f * d; p.color = if (sel) Color.rgb(255, 179, 0) else Color.WHITE
        cv.drawRoundRect(l, t, l + w, t + h, 3f * d, 3f * d, p)
    }
}
