package com.flatcam.app

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/** A 3D colour lookup table (red varies fastest). Used for monitor-only looks; recorded files stay flat. */
class Lut3D(val n: Int, val d: FloatArray) {
    fun apply(src: IntArray, dst: IntArray, count: Int) {
        val m = (n - 1) / 255f
        val last = n - 2
        for (i in 0 until count) {
            val p = src[i]
            val r = ((p shr 16) and 255) * m
            val g = ((p shr 8) and 255) * m
            val b = (p and 255) * m
            val r0 = r.toInt().coerceAtMost(last); val g0 = g.toInt().coerceAtMost(last); val b0 = b.toInt().coerceAtMost(last)
            val fr = r - r0; val fg = g - g0; val fb = b - b0
            var oR = 0f; var og = 0f; var ob = 0f
            for (c in 0..7) {
                val dr = c and 1; val dg = (c shr 1) and 1; val db = (c shr 2) and 1
                val w = (if (dr == 1) fr else 1f - fr) * (if (dg == 1) fg else 1f - fg) * (if (db == 1) fb else 1f - fb)
                val k = (((b0 + db) * n + g0 + dg) * n + r0 + dr) * 3
                oR += w * d[k]; og += w * d[k + 1]; ob += w * d[k + 2]
            }
            val ri = (oR * 255f + 0.5f).toInt().coerceIn(0, 255)
            val gi = (og * 255f + 0.5f).toInt().coerceIn(0, 255)
            val bi = (ob * 255f + 0.5f).toInt().coerceIn(0, 255)
            dst[i] = (255 shl 24) or (ri shl 16) or (gi shl 8) or bi
        }
    }

    companion object {
        /** Parses an Adobe/Resolve .cube 3D LUT. Returns null if it is not a usable 3D cube. */
        fun parseCube(text: String): Lut3D? {
            var n = 0
            var out: FloatArray? = null
            var k = 0
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                if (line.startsWith("LUT_3D_SIZE")) { n = line.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() ?: return null; if (n < 2 || n > 129) return null; out = FloatArray(n * n * n * 3); continue }
                if (line.startsWith("LUT_1D_SIZE")) return null
                val c = line[0]
                if (!(c.isDigit() || c == '-' || c == '.')) continue
                val o = out ?: return null
                val p = line.split(Regex("\\s+"))
                if (p.size < 3 || k + 3 > o.size) continue
                o[k++] = (p[0].toFloatOrNull() ?: 0f).coerceIn(0f, 1f)
                o[k++] = (p[1].toFloatOrNull() ?: 0f).coerceIn(0f, 1f)
                o[k++] = (p[2].toFloatOrNull() ?: 0f).coerceIn(0f, 1f)
            }
            val o = out ?: return null
            return if (k == o.size) Lut3D(n, o) else null
        }
    }
}

class Look(
    val name: String, val sat: Float, val contrast: Float,
    val sh: FloatArray = floatArrayOf(0f, 0f, 0f), val hi: FloatArray = floatArrayOf(0f, 0f, 0f),
    val fade: Float = 0f, val expo: Float = 1f, val bw: Boolean = false
)

/** Built-in cinematic looks. They expect the flat/log picture FilTarPro records and turn it into a graded display image. */
object Looks {
    val list = listOf(
        Look("Natural Rec.709", 1.0f, 1.0f),
        Look("Teal and Orange", 1.15f, 1.08f, floatArrayOf(-0.06f, 0.02f, 0.07f), floatArrayOf(0.08f, 0.03f, -0.06f)),
        Look("Bleach Bypass", 0.55f, 1.3f),
        Look("Kodak Warm Film", 1.1f, 1.05f, floatArrayOf(0.02f, 0f, -0.02f), floatArrayOf(0.07f, 0.04f, -0.05f), 0.02f),
        Look("Moonlight Cool", 0.85f, 1.05f, floatArrayOf(-0.03f, 0f, 0.07f), floatArrayOf(-0.03f, 0f, 0.05f)),
        Look("Noir B&W", 0f, 1.25f, bw = true),
        Look("Faded Vintage", 0.8f, 0.95f, floatArrayOf(0.02f, 0.01f, 0.03f), floatArrayOf(0.05f, 0.03f, -0.02f), 0.06f),
        Look("Golden Hour", 1.2f, 1.05f, hi = floatArrayOf(0.09f, 0.05f, -0.07f), expo = 1.1f),
        Look("Neon Cyber", 1.4f, 1.15f, floatArrayOf(0.05f, -0.02f, 0.08f), floatArrayOf(-0.03f, 0.05f, 0.08f)),
        Look("Soft Pastel", 0.9f, 0.9f, hi = floatArrayOf(0.04f, 0.02f, 0.04f), fade = 0.08f),
        Look("Cinema Contrast", 1.05f, 1.25f, sh = floatArrayOf(0f, 0f, 0.02f))
    )

    /** k = strength of the log curve being recorded (0 = standard profile, picture is already display-ready). */
    fun build(look: Look, k: Float, gamma: Float = 0f): Lut3D {
        val n = 17
        val d = FloatArray(n * n * n * 3)
        val lk = if (k > 0f) ln(1f + k) else 0f
        val flat = k > 0f || gamma > 0f
        fun dec(y: Float): Float = if (k > 0f) (exp(y * lk) - 1f) / k else if (gamma > 0f) y.pow(gamma) else y
        fun disp(x: Float): Float {
            if (!flat) return x
            val e = x * look.expo
            val t = (e * (2.51f * e + 0.03f) / (e * (2.43f * e + 0.59f) + 0.14f)).coerceIn(0f, 1f)
            return t.pow(1f / 2.2f)
        }
        var i = 0
        for (bi in 0 until n) for (gi in 0 until n) for (ri in 0 until n) {
            var r = disp(dec(ri / (n - 1f))); var g = disp(dec(gi / (n - 1f))); var b = disp(dec(bi / (n - 1f)))
            r = (r - 0.5f) * look.contrast + 0.5f; g = (g - 0.5f) * look.contrast + 0.5f; b = (b - 0.5f) * look.contrast + 0.5f
            val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
            if (look.bw) { r = l; g = l; b = l } else {
                r = l + (r - l) * look.sat; g = l + (g - l) * look.sat; b = l + (b - l) * look.sat
            }
            val lc = l.coerceIn(0f, 1f)
            val ws = (1f - lc) * (1f - lc); val wh = lc * lc
            r += look.sh[0] * ws + look.hi[0] * wh; g += look.sh[1] * ws + look.hi[1] * wh; b += look.sh[2] * ws + look.hi[2] * wh
            if (look.fade > 0f) { r = look.fade + r * (1f - look.fade); g = look.fade + g * (1f - look.fade); b = look.fade + b * (1f - look.fade) }
            d[i++] = r.coerceIn(0f, 1f); d[i++] = g.coerceIn(0f, 1f); d[i++] = b.coerceIn(0f, 1f)
        }
        return Lut3D(n, d)
    }
}
