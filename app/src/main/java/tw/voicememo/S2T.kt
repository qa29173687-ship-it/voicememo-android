package tw.voicememo

import java.io.InputStream

/** 簡體→繁體（台灣用字），使用 OpenCC STPhrases/STCharacters + TWVariants，最長詞優先 */
class S2T(s2t: InputStream, tw: InputStream) {
    private val dict = HashMap<String, String>(60000); private val twMap = HashMap<Char, Char>(); private var maxLen = 1
    init {
        s2t.bufferedReader(Charsets.UTF_8).forEachLine { l -> val i = l.indexOf('\t'); if (i > 0) { val k = l.substring(0, i); dict[k] = l.substring(i + 1); if (k.length > maxLen) maxLen = k.length } }
        tw.bufferedReader(Charsets.UTF_8).forEachLine { l -> if (l.length == 3 && l[1] == '\t') twMap[l[0]] = l[2] }
        maxLen = maxLen.coerceAtMost(8)
    }
    fun convert(s: String): String {
        val sb = StringBuilder(s.length); var i = 0
        while (i < s.length) {
            var hit = false
            for (len in minOf(maxLen, s.length - i) downTo 1) {
                val v = dict[s.substring(i, i + len)] ?: continue
                sb.append(v); i += len; hit = true; break
            }
            if (!hit) { sb.append(s[i]); i++ }
        }
        for (k in sb.indices) twMap[sb[k]]?.let { sb.setCharAt(k, it) }
        return sb.toString()
    }
    companion object {
        @Volatile private var inst: S2T? = null
        fun get(ctx: android.content.Context): S2T = inst ?: synchronized(this) {
            inst ?: S2T(ctx.assets.open("s2t.txt"), ctx.assets.open("tw.txt")).also { inst = it }
        }
    }
}

/** 把辨識片段組成有標點、分段的逐字稿 */
object Transcript {
    /** seg: (文字, 開始秒, 結束秒)；停頓 >0.8 秒加「。」、> 2.5 秒換段，其它加「，」 */
    fun join(segs: List<Triple<String, Double, Double>>): String {
        val sb = StringBuilder(); var lastEnd = -1.0
        for ((t0, st, en) in segs) {
            val t = t0.trim().replace(Regex("(?<=[\\u3000-\\u9fff\\uff00-\\uffef])\\s+|\\s+(?=[\\u3000-\\u9fff\\uff00-\\uffef])"), ""); if (t.isEmpty()) continue
            if (sb.isNotEmpty()) {
                val gap = st - lastEnd; val endsP = sb.last() in "，。！？、；：,.!?"
                if (endsP && gap > 0.8 && sb.last() in "，,") sb.setCharAt(sb.length - 1, '。')
                if (!endsP) sb.append(if (gap > 0.8) "。" else "，")
                if (gap > 2.5) sb.append('\n')
            }
            sb.append(t); lastEnd = en
        }
        if (sb.isNotEmpty() && sb.last() !in "。！？.!?") { if (sb.last() in "，,") sb.setLength(sb.length - 1); sb.append('。') }
        return sb.toString()
    }
    /** 說話者逐段：一人時不加標籤；多人時「說話者 N：…」，N 依出現順序，同一人連續發言合併成一段 */
    fun speakers(turns: List<Diar.Turn>): String {
        if (turns.isEmpty()) return ""
        val order = LinkedHashMap<Int, Int>(); turns.forEach { if (it.spk !in order) order[it.spk] = order.size + 1 }
        if (order.size == 1) return join(turns.map { Triple(it.text, it.start, it.end) })
        val sb = StringBuilder(); var last = -1
        for (t in turns) {
            val txt = t.text.trim(); if (txt.isEmpty()) continue
            if (t.spk == last) { if (sb.last() !in "，。！？、,.!?") sb.append('，'); sb.append(txt) }
            else { if (sb.isNotEmpty()) { if (sb.last() !in "。！？.!?") sb.append('。'); sb.append('\n') }; sb.append("說話者 ${order[t.spk]}：").append(txt) }
            last = t.spk
        }
        if (sb.last() !in "。！？.!?") sb.append('。')
        return sb.toString()
    }
    private val label = Regex("^([^：\n]{1,12})：", RegexOption.MULTILINE)
    /** 逐字稿裡的說話者名稱（依出現順序） */
    fun labels(text: String): List<String> = label.findAll(text).map { it.groupValues[1] }.distinct().toList().takeIf { it.size > 1 } ?: emptyList()
    /** 改名：只改每行開頭的「舊名：」 */
    fun rename(text: String, old: String, new: String): String {
        val n = new.trim().replace("：", "").replace("\n", ""); if (n.isEmpty()) return text
        return Regex("^" + Regex.escape(old) + "：", RegexOption.MULTILINE).replace(text, Regex.escapeReplacement(n) + "：")
    }
    private val filler = Regex("^(嗯|啊|呃|那個|然後|就是|喂|好|對)+")
    /** 第一個有意義的句子當標題（≤20 字） */
    fun title(text: String): String? {
        for (raw in text.replace(label, "").split(Regex("[，。！？\\n]"))) {
            val s = raw.trim().replace(filler, ""); if (s.length >= 2) return s.take(20)
        }
        return null
    }
}
