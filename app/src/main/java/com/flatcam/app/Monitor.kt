package com.flatcam.app

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Monitor-only image analysis, run on a worker thread:
 *  - focus assist
 *      PEAKING: thin coloured lines drawn ONLY on edges that are genuinely sharp. An edge counts as sharp when its
 *               slope at pixel scale is much steeper than its slope at a wider scale (a blurred edge is a gentle ramp,
 *               so both scales agree; a focused edge is a step, so the fine scale is steeper). Noise is rejected by a
 *               contrast floor, isolated specks are removed, and the amount drawn is capped so the picture never fills up.
 *      ISOLATE: textured areas that are out of focus are gently dimmed; the sharp zone simply stays bright.
 *               Flat, featureless areas (sky, walls) are left alone because focus cannot be judged there.
 *  - focus score for the small focus meter
 *  - LUT preview
 * Nothing here touches the recorded picture.
 */
class Monitor {
    @Volatile var mode = 0          // 0 off, 1 isolate, 2 peaking
    @Volatile var sens = 1          // 0 low, 1 medium, 2 high
    @Volatile var peakColor = 0xFFFF3B30.toInt()
    @Volatile var lut: Lut3D? = null

    // results (read on the UI thread after process() returns)
    @Volatile var lutBmp: Bitmap? = null
    @Volatile var focusBmp: Bitmap? = null
    @Volatile var score = 0f

    private val lutBufs = arrayOfNulls<Bitmap>(3)
    private val peakBufs = arrayOfNulls<Bitmap>(3)
    private val isoBufs = arrayOfNulls<Bitmap>(3)
    private var flip = 0
    private var lutPx = IntArray(0)
    private var luma = IntArray(0)
    private var ii = IntArray(0)
    private var pk = IntArray(0)
    private var sf = FloatArray(0)
    private var rt = FloatArray(0)
    private var prevMask = FloatArray(0)

    fun process(px: IntArray, w: Int, h: Int) {
        flip = (flip + 1) % 3
        if (mode != 0) focus(px, w, h) else { focusBmp = null; prevMask = FloatArray(0); score = 0f }
        val l = lut
        if (l != null) {
            if (lutPx.size < w * h) lutPx = IntArray(w * h)
            l.apply(px, lutPx, w * h)
            var b = lutBufs[flip]
            if (b == null || b.width != w || b.height != h) { b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); lutBufs[flip] = b }
            b.setPixels(lutPx, 0, w, 0, 0, w, h)
            lutBmp = b
        } else lutBmp = null
    }

    private fun focus(px: IntArray, w: Int, h: Int) {
        val n = w * h
        if (luma.size < n) luma = IntArray(n)
        for (i in 0 until n) { val p = px[i]; luma[i] = (((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) shr 8 }
        val iw = w + 1
        if (ii.size < iw * (h + 1)) ii = IntArray(iw * (h + 1))
        for (x in 0..w) ii[x] = 0
        for (y in 1..h) {
            var row = 0
            ii[y * iw] = 0
            for (x in 1..w) { row += luma[(y - 1) * w + x - 1]; ii[y * iw + x] = ii[(y - 1) * iw + x] + row }
        }
        fun box(x: Int, y: Int, r: Int): Float {
            val x0 = max(0, x - r); val x1 = min(w, x + r + 1); val y0 = max(0, y - r); val y1 = min(h, y + r + 1)
            val s = ii[y1 * iw + x1] - ii[y0 * iw + x1] - ii[y1 * iw + x0] + ii[y0 * iw + x0]
            return s.toFloat() / ((x1 - x0) * (y1 - y0))
        }
        val bs = 8
        val bw = w / bs; val bh = h / bs
        if (bw < 3 || bh < 3) return
        if (sf.size < n) { sf = FloatArray(n); rt = FloatArray(n) }
        java.util.Arrays.fill(sf, 0, n, 0f)
        java.util.Arrays.fill(rt, 0, n, 0f)

        // thresholds by sensitivity: contrast floor (levels per pixel), sharpness ratio, max share of the picture drawn
        val gT = when (sens) { 0 -> 8f; 1 -> 5.5f; else -> 3.8f }
        val rT = when (sens) { 0 -> 2.5f; 1 -> 2.1f; else -> 1.8f }
        val cap = when (sens) { 0 -> 0.025f; 1 -> 0.04f; else -> 0.06f }

        // pass 1: fine slope (raw pixels) versus coarse slope (7x7 average, +-3 px)
        for (y in 3 until h - 3) {
            val row = y * w
            for (x in 3 until w - 3) {
                val i = row + x
                val f = (abs(luma[i + 1] - luma[i - 1]) + abs(luma[i + w] - luma[i - w])) * 0.5f
                sf[i] = f
                if (f > gT) {
                    val c = (abs(box(x + 3, y, 3) - box(x - 3, y, 3)) + abs(box(x, y + 3, 3) - box(x, y - 3, 3))) / 6f
                    rt[i] = f / (c + 0.6f)
                }
            }
        }

        // pass 2: keep local maxima of the fine slope that are sharp; count per block
        val peak = mode == 2
        if (peak) { if (pk.size < n) pk = IntArray(n); java.util.Arrays.fill(pk, 0, n, 0) }
        val edgeCnt = IntArray(bw * bh); val sharpCnt = IntArray(bw * bh)
        val hist = IntArray(65)
        var ratioSum = 0f; var ratioN = 0
        for (y in 3 until h - 3) {
            val row = y * w
            for (x in 3 until w - 3) {
                val i = row + x
                val f = sf[i]
                if (f <= gT) continue
                if (f < sf[i - 1] || f < sf[i + 1] || f < sf[i - w] || f < sf[i + w]) continue
                val bi = min(y / bs, bh - 1) * bw + min(x / bs, bw - 1)
                edgeCnt[bi]++
                val r = rt[i]
                ratioSum += min(r, 3.5f); ratioN++
                if (r >= rT) {
                    sharpCnt[bi]++
                    if (peak) { pk[i] = 1; hist[min(f.toInt(), 64)]++ }
                }
            }
        }

        // focus score (0..1): how step-like the strongest edges are
        val m = if (ratioN >= 25) ratioSum / ratioN else 0f
        val sc = ((m - 1.2f) / 1.6f).coerceIn(0f, 1f)
        score = score * 0.6f + sc * 0.4f

        if (peak) {
            // remove specks: a block needs a few sharp edge pixels to be drawn
            var total = 0
            for (y in 0 until bh) for (x in 0 until bw) total += if (sharpCnt[y * bw + x] >= 3) sharpCnt[y * bw + x] else 0
            // cap the amount drawn: raise the slope threshold until at most `cap` of the picture is covered
            var thr = 0
            val allowed = (n * cap).toInt()
            if (total > allowed) {
                var acc = 0
                var t = 64
                while (t > 0) { acc += hist[t]; if (acc > allowed) break; t-- }
                thr = t + 1
            }
            var b = peakBufs[flip]
            if (b == null || b.width != w || b.height != h) { b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); peakBufs[flip] = b }
            val col = peakColor
            for (y in 3 until h - 3) {
                val by = min(y / bs, bh - 1)
                for (x in 3 until w - 3) {
                    val i = y * w + x
                    if (pk[i] == 0) { continue }
                    val bi = by * bw + min(x / bs, bw - 1)
                    pk[i] = if (sharpCnt[bi] >= 3 && sf[i] >= thr) col else 0
                }
            }
            b.setPixels(pk, 0, w, 0, 0, w, h)
            focusBmp = b
        } else {
            // ISOLATE: dim textured blocks whose edges are mostly blurred; leave flat blocks alone
            val dim = FloatArray(bw * bh)
            for (i in dim.indices) {
                if (edgeCnt[i] >= 3) {
                    val frac = sharpCnt[i].toFloat() / edgeCnt[i]
                    dim[i] = 1f - smooth(0.25f, 0.6f, frac)
                }
            }
            // let neighbouring flat blocks follow the textured ones softly, then settle over time
            val sm = FloatArray(bw * bh)
            for (y in 0 until bh) for (x in 0 until bw) {
                var s = 0f; var c = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= bw || yy >= bh) continue
                    s += dim[yy * bw + xx]; c++
                }
                sm[y * bw + x] = max(dim[y * bw + x] * 0.8f, s / c)
            }
            if (prevMask.size == sm.size) for (i in sm.indices) sm[i] = 0.6f * prevMask[i] + 0.4f * sm[i]
            prevMask = sm
            var mb = isoBufs[flip]
            if (mb == null || mb.width != bw || mb.height != bh) { mb = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); isoBufs[flip] = mb }
            val a = IntArray(bw * bh)
            for (i in a.indices) a[i] = (sm[i] * 0.55f * 255f).toInt().coerceIn(0, 255) shl 24
            mb.setPixels(a, 0, bw, 0, 0, bw, bh)
            focusBmp = mb
        }
    }

    private fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
