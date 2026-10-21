package tw.voicememo

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object Diar {
    /** 3D-Speaker ERes2NetV2 + pyannote 3.0 的分群門檻（用 sherpa 的四人中文範例調過：0.8 → 正確 4 人） */
    const val THRESHOLD = 0.8f
    /** 跨視窗判定同一人的聲紋 cosine 門檻 */
    const val LINK_SIM = 0.5f
    data class Seg(val a: Int, val b: Int, val spk: Int)
    data class Turn(val spk: Int, val text: String, val start: Double, val end: Double)

    /** 同一人、間隔 < 1 秒的片段合併成一個 turn */
    fun merge(s: List<Seg>): List<Seg> {
        val out = mutableListOf<Seg>()
        for (x in s.sortedBy { it.a }) { val l = out.lastOrNull()
            if (l != null && l.spk == x.spk && x.a - l.b < 16000) out[out.size - 1] = l.copy(b = maxOf(l.b, x.b)) else out += x }
        return out
    }

    fun read(pcm: File, from: Int, n: Int): FloatArray = RandomAccessFile(pcm, "r").use { r ->
        val b = ByteArray(n * 2); r.seek(from * 2L); r.readFully(b)
        val sb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer(); FloatArray(n) { sb.get(it) / 32768f } }

    /** 視窗切點：每 win 取樣，往前 10 秒內找最安靜的 0.1 秒，避免切在句子中間 */
    fun cuts(pcm: File, total: Int, win: Int): List<Int> {
        val c = mutableListOf(0); var p = win
        while (p < total - win / 4) {
            val look = 10 * 16000; val x = read(pcm, p - look, look); var best = p; var bestE = Double.MAX_VALUE
            for (s in 0 until look - 1600 step 800) { var e = 0.0; for (i in s until s + 1600) e += x[i] * x[i]; if (e < bestE) { bestE = e; best = p - look + s + 800 } }
            c += best; p = best + win
        }
        c += total; return c
    }

    /** 全域說話者：聲紋平均，cosine 比對；maxN>0 時最多 maxN 人 */
    class Linker(private val maxN: Int) {
        val cents = mutableListOf<FloatArray>(); private val counts = mutableListOf<Int>()
        private fun norm(v: FloatArray): FloatArray { var s = 0.0; for (x in v) s += x * x; val d = sqrt(s).toFloat().coerceAtLeast(1e-9f); return FloatArray(v.size) { v[it] / d } }
        fun cos(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s }
        fun assign(e0: FloatArray?): Int {
            if (e0 == null) return if (cents.isEmpty()) { cents += FloatArray(0); counts += 1; 0 } else 0
            val e = norm(e0)
            var bi = -1; var bs = -2f
            cents.forEachIndexed { i, c -> if (c.size == e.size) { val s = cos(c, e); if (s > bs) { bs = s; bi = i } } }
            val full = maxN > 0 && cents.size >= maxN
            if (bi >= 0 && (bs >= LINK_SIM || full)) {
                val k = counts[bi]; val c = cents[bi]; cents[bi] = norm(FloatArray(e.size) { (c[it] * k + e[it]) / (k + 1) }); counts[bi] = k + 1; return bi
            }
            cents += e; counts += 1; return cents.size - 1
        }
    }
}
