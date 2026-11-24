package com.flatcam.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.min

/**
 * Night stacking: several identical short exposures are aligned and averaged.
 * Averaging N frames cuts random noise by about sqrt(N) without smearing detail the way
 * aggressive noise reduction does. Pixels that differ too much from the reference frame
 * (moving people, leaves) are ignored, so there are no ghosts.
 */
object NightStack {

    fun merge(frames: List<ByteArray>, rotDeg: Int, maxPixels: Long = 13_000_000L): ByteArray {
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(frames[0], 0, frames[0].size, bo)
        var sample = 1
        while ((bo.outWidth / sample).toLong() * (bo.outHeight / sample) > maxPixels) sample *= 2
        val w = bo.outWidth / sample
        val h = bo.outHeight / sample

        val sumR = ShortArray(w * h)
        val sumG = ShortArray(w * h)
        val sumB = ShortArray(w * h)
        val cnt = ByteArray(w * h)
        val refLuma = ByteArray(w * h)
        val curLuma = ByteArray(w * h)
        val strip = IntArray(w * 64)

        for ((fi, data) in frames.withIndex()) {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, opts) ?: continue
            if (bmp.width != w || bmp.height != h) { bmp.recycle(); continue }

            // pass 1: luma of this frame
            var y = 0
            while (y < h) {
                val rows = min(64, h - y)
                bmp.getPixels(strip, 0, w, 0, y, w, rows)
                for (r in 0 until rows) for (x in 0 until w) {
                    val p = strip[r * w + x]
                    val l = (((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) shr 8
                    curLuma[(y + r) * w + x] = l.toByte()
                }
                y += rows
            }

            var dx = 0
            var dy = 0
            if (fi == 0) System.arraycopy(curLuma, 0, refLuma, 0, w * h)
            else { val s = align(refLuma, curLuma, w, h); dx = s.first; dy = s.second }

            // pass 2: accumulate
            y = 0
            while (y < h) {
                val rows = min(64, h - y)
                bmp.getPixels(strip, 0, w, 0, y, w, rows)
                for (r in 0 until rows) {
                    val sy = y + r
                    val ty = sy - dy
                    if (ty < 0 || ty >= h) continue
                    for (x in 0 until w) {
                        val tx = x - dx
                        if (tx < 0 || tx >= w) continue
                        val ti = ty * w + tx
                        if (fi > 0) {
                            val l = curLuma[sy * w + x].toInt() and 255
                            val rl = refLuma[ti].toInt() and 255
                            if (abs(l - rl) > 40) continue
                        }
                        val p = strip[r * w + x]
                        sumR[ti] = (sumR[ti] + ((p shr 16) and 255)).toShort()
                        sumG[ti] = (sumG[ti] + ((p shr 8) and 255)).toShort()
                        sumB[ti] = (sumB[ti] + (p and 255)).toShort()
                        cnt[ti] = (cnt[ti] + 1).toByte()
                    }
                }
                y += rows
            }
            bmp.recycle()
        }

        var out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        var y = 0
        while (y < h) {
            val rows = min(64, h - y)
            for (r in 0 until rows) for (x in 0 until w) {
                val i = (y + r) * w + x
                val c = max1(cnt[i].toInt() and 255)
                val rr = (sumR[i] + c / 2) / c
                val gg = (sumG[i] + c / 2) / c
                val bb = (sumB[i] + c / 2) / c
                strip[r * w + x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
            out.setPixels(strip, 0, w, 0, y, w, rows)
            y += rows
        }

        if (rotDeg != 0) {
            val m = Matrix().apply { postRotate(rotDeg.toFloat()) }
            val rotated = Bitmap.createBitmap(out, 0, 0, w, h, m, true)
            out.recycle()
            out = rotated
        }
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 96, bos)
        out.recycle()
        return bos.toByteArray()
    }

    private fun max1(v: Int) = if (v < 1) 1 else v

    /** Finds the shift (dx, dy) so that cur(x, y) matches ref(x - dx, y - dy). */
    private fun align(ref: ByteArray, cur: ByteArray, w: Int, h: Int): Pair<Int, Int> {
        val margin = 64
        fun cost(dx: Int, dy: Int, stride: Int): Long {
            var s = 0L
            var y = margin
            while (y < h - margin) {
                var x = margin
                while (x < w - margin) {
                    s += abs((cur[y * w + x].toInt() and 255) - (ref[(y - dy) * w + (x - dx)].toInt() and 255))
                    x += stride
                }
                y += stride
            }
            return s
        }
        if (w < margin * 3 || h < margin * 3) return Pair(0, 0)
        var best = Long.MAX_VALUE
        var bdx = 0
        var bdy = 0
        var dy = -32
        while (dy <= 32) {
            var dx = -32
            while (dx <= 32) {
                val c = cost(dx, dy, 8)
                if (c < best) { best = c; bdx = dx; bdy = dy }
                dx += 4
            }
            dy += 4
        }
        val cx = bdx
        val cy = bdy
        best = Long.MAX_VALUE
        for (ddy in -3..3) for (ddx in -3..3) {
            val c = cost(cx + ddx, cy + ddy, 4)
            if (c < best) { best = c; bdx = cx + ddx; bdy = cy + ddy }
        }
        return Pair(bdx, bdy)
    }
}
