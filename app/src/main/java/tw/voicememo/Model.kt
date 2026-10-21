package tw.voicememo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class Note(
    var id: Long, var title: String,
    var text: String = "", var file: String = "",
    var durationMs: Long = 0, var exported: String = "",
    var type: Int = MEMO, var remindAt: Long = 0, var pinned: Boolean = false, var done: Boolean = false, var tags: String = "",
    var autoType: Boolean = true, var asr: Int = 0, var sourceId: Long = 0, // asr 0 無 1 進行中/部分 2 完成
) {
    val hasAudio get() = file.isNotBlank()
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("text", text).put("file", file)
        .put("durationMs", durationMs).put("exported", exported)
        .put("type", type).put("remindAt", remindAt).put("pinned", pinned).put("done", done).put("tags", tags).put("autoType", autoType).put("asr", asr).put("sourceId", sourceId)
    companion object {
        const val REMIND = 0; const val MEMO = 1; const val NOTE = 2
        val TYPES = arrayOf("提醒", "備忘", "筆記")
        fun from(o: JSONObject) = Note(o.getLong("id"), o.optString("title"), o.optString("text"), o.optString("file"),
            o.optLong("durationMs"), o.optString("exported"),
            o.optInt("type", MEMO), o.optLong("remindAt"), o.optBoolean("pinned"), o.optBoolean("done"), o.optString("tags"),
            o.optBoolean("autoType", true), o.optInt("asr"), o.optLong("sourceId"))
    }
}

object Names {
    var zone: ZoneId = ZoneId.systemDefault()
    fun at(ms: Long): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(zone)
    fun safe(s: String) = s.trim().replace(Regex("[^\\p{L}\\p{N}_-]+"), "-").trim('-')
    fun isDefaultTitle(s: String) = Regex("^\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2} (錄音|記事)$").matches(s.trim())
    /** yyyyMMdd_HHmmss_標題前 10 字 */
    fun base(t: ZonedDateTime, title: String): String {
        val b = t.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val c = if (isDefaultTitle(title)) "" else safe(title).take(10).trim('-')
        return if (c.isEmpty()) b else "${b}_$c"
    }
    fun defaultTitle(t: ZonedDateTime) = t.format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")) + " 錄音"
    fun date(t: ZonedDateTime) = t.format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"))
    fun dur(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }
    fun noteText(n: Note): String = buildString {
        appendLine("類型：${Note.TYPES[n.type]}"); appendLine("標題：${n.title}")
        if (n.type == Note.REMIND && n.remindAt > 0) appendLine("提醒時間：${date(at(n.remindAt))}")
        if (n.tags.isNotBlank()) appendLine("標籤：${n.tags}")
        appendLine("時間：${date(at(n.id))}"); appendLine("長度：${dur(n.durationMs)}")
        if (n.file.isNotBlank()) appendLine("錄音檔：${n.file}")
        if (n.text.isNotBlank()) { appendLine(); appendLine("內容／逐字稿："); appendLine(n.text) }
    }
    fun match(n: Note, q: String): Boolean {
        val k = q.trim(); if (k.isEmpty()) return true
        return listOf(n.title, n.tags, n.text, date(at(n.id))).any { it.contains(k, true) }
    }
}

object Store {
    fun dir(ctx: Context) = File(ctx.filesDir, "audio").apply { mkdirs() }
    private fun f(ctx: Context) = File(ctx.filesDir, "notes.json")
    fun audio(ctx: Context, n: Note) = File(dir(ctx), n.file)
    @Synchronized fun all(ctx: Context): MutableList<Note> {
        val file = f(ctx); if (!file.exists()) return mutableListOf()
        return try { val a = JSONArray(file.readText()); MutableList(a.length()) { Note.from(a.getJSONObject(it)) }.sortedByDescending { it.id }.toMutableList() }
        catch (e: Exception) { mutableListOf() }
    }
    @Synchronized private fun write(ctx: Context, l: List<Note>) {
        val a = JSONArray(); l.forEach { a.put(it.toJson()) }
        val tmp = File(ctx.filesDir, "notes.json.tmp"); tmp.writeText(a.toString()); tmp.renameTo(f(ctx))
    }
    @Synchronized fun save(ctx: Context, n: Note) { val l = all(ctx); l.removeAll { it.id == n.id }; l.add(n); write(ctx, l) }
    @Synchronized fun get(ctx: Context, id: Long) = all(ctx).firstOrNull { it.id == id }
    @Synchronized fun delete(ctx: Context, n: Note) { val l = all(ctx); l.removeAll { it.id == n.id }; write(ctx, l); audio(ctx, n).delete() }
}

/** 從中文文字找時間：今天/明天/後天/下週X、M月D日、M/D、上午/下午/晚上 H點(半|M分)、HH:mm */
object TimeParse {
    private val num = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '兩' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10)
    fun cn(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        if (s.isEmpty() || s.any { it !in num }) return null
        val i = s.indexOf('十')
        if (i < 0) return s.fold(0) { a, c -> a * 10 + num[c]!! }
        val tens = if (i == 0) 1 else num[s[0]]!!; val ones = if (i == s.length - 1) 0 else num[s[i + 1]]!!
        return tens * 10 + ones
    }
    private const val N = "[0-9零〇一二兩三四五六七八九十]{1,3}"
    fun find(text: String, now: ZonedDateTime): ZonedDateTime? {
        var day: java.time.LocalDate? = null
        Regex("(今天|今日|明天|明日|後天|大後天)").find(text)?.let {
            day = now.toLocalDate().plusDays(when (it.value) { "明天", "明日" -> 1L; "後天" -> 2L; "大後天" -> 3L; else -> 0L })
        }
        Regex("(下|這|本)?(週|周|星期|禮拜)([一二三四五六日天])").find(text)?.let { m ->
            val w = "一二三四五六日天".indexOf(m.groupValues[3]).let { if (it == 7) 7 else it + 1 }
            var d = now.toLocalDate(); val cur = d.dayOfWeek.value
            d = if (m.groupValues[1] == "下") d.plusDays((8 - cur).toLong() + w - 1) else d.plusDays(((w - cur + 7) % 7).toLong())
            day = d
        }
        Regex("($N)月($N)[日號号]|(\\d{1,2})/(\\d{1,2})(?!\\d)").find(text)?.let { m ->
            val mo = cn(m.groupValues[1].ifEmpty { m.groupValues[3] }); val dd = cn(m.groupValues[2].ifEmpty { m.groupValues[4] })
            if (mo != null && dd != null && mo in 1..12 && dd in 1..31) runCatching {
                var d = java.time.LocalDate.of(now.year, mo, dd); if (d.isBefore(now.toLocalDate())) d = d.plusYears(1); day = d
            }
        }
        var h: Int? = null; var mi = 0
        val tm = Regex("(早上|上午|中午|下午|傍晚|晚上|凌晨)?\\s*($N)[點点時](半|($N)分?)?").find(text)
        val hm = Regex("(早上|上午|中午|下午|傍晚|晚上|凌晨)?\\s*(\\d{1,2})[:：](\\d{2})").find(text)
        val m = hm ?: tm
        if (m != null) {
            h = cn(m.groupValues[2])
            mi = if (m === hm) m.groupValues[3].toInt() else if (m.groupValues[3] == "半") 30 else m.groupValues[4].let { if (it.isEmpty()) 0 else cn(it) ?: 0 }
            val p = m.groupValues[1]
            if (h != null && h < 12 && p in setOf("下午", "傍晚", "晚上")) h += 12
            if (h != null && h < 6 && p == "中午") h += 12
        }
        if (h == null && day == null) return null
        if (h == null && day == now.toLocalDate()) return null
        if (h != null && (h > 23 || mi > 59)) return null
        val d = day ?: now.toLocalDate()
        var t = d.atTime(h ?: 9, mi).atZone(now.zone)
        if (day == null && !t.isAfter(now)) t = t.plusDays(1)
        return t
    }
}

object TimeScan {
    /** 逐句找時間；回傳未來的時間點（由早到晚），附來源句子 */
    fun all(text: String, now: ZonedDateTime): List<Pair<ZonedDateTime, String>> {
        val out = mutableListOf<Pair<ZonedDateTime, String>>()
        val parts = text.split(Regex("[。！？\\n]")).flatMap { s -> listOf(s) }
        for (p in parts) {
            val clauses = p.split(Regex("[，,；;]")).map { it.trim() }.filter { it.isNotEmpty() }
            var i = 0
            while (i < clauses.size) {
                // 「明天，下午三點」這類跨逗號的情況：先試合併兩段
                val two = if (i + 1 < clauses.size) clauses[i] + clauses[i + 1] else null
                val t1 = TimeParse.find(clauses[i], now)
                val t2 = two?.let { TimeParse.find(it, now) }
                val use = if (t2 != null && t1 != null && t2 != t1 && TimeParse.find(clauses[i + 1], now) != null) { i++; t2 } else t1
                if (use != null && use.isAfter(now)) out += use to clauses[i]
                i++
            }
        }
        return out.distinctBy { it.first }.sortedBy { it.first }
    }
}
