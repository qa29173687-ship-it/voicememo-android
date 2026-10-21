package tw.voicememo

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Todo(val text: String, val owner: String?, val due: Long?, val priority: String, val quote: String = "")
data class TodoResult(val todos: List<Todo>, val summary: List<String>)

/** 不用 API：把逐字稿交給 Grok App（使用者自己的訂閱），再把 Grok 的回覆貼回來解析 */
object Grok {
    const val PKG = "ai.x.grok"
    const val LONG = 20000
    private val tz: ZoneId = ZoneId.of("Asia/Taipei")
    private val WEEK = arrayOf("一", "二", "三", "四", "五", "六", "日")

    fun nowText(now: ZonedDateTime) = now.withZoneSameInstant(tz).let {
        it.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)) + "（星期" + WEEK[it.dayOfWeek.value - 1] + "，台北時間 +08:00）" }

    fun prompt(n: Note, now: ZonedDateTime): String = """請幫我整理下面這段錄音逐字稿裡的待辦事項。
現在時間：${nowText(now)}
錄音標題：${n.title}（錄於 ${Names.date(Names.at(n.id))}）

說明：逐字稿是語音辨識結果，可能有錯字；行首的「名字：」是說話者。
規則：
1. 只列出逐字稿裡真的提到、需要有人去做的事（待辦、承諾、約定、提醒），不要自己編。
2. 內容：繁體中文，一句簡短可執行的話。
3. 負責人：逐字稿明確說由誰做才填（說話者名稱或人名），否則 null。
4. 時間：依現在時間把「明天下午三點」「下週一」等換算成 ISO 8601，含 +08:00（例如 2026-10-22T15:00:00+08:00）；只有日期用 09:00；沒提到時間就 null。
5. 優先度：只能是「高」「中」「低」。
6. 摘要：3 到 5 點重點。

請「只」回覆一個 ```json 程式碼區塊，不要其他文字，格式如下：
```json
{"todos":[{"內容":"…","負責人":null,"時間":null,"優先度":"中"}],"摘要":["…"]}
```

逐字稿：
${n.text.trim()}"""

    /** 送到 Grok App；沒裝就複製＋分享選單。回傳 true 表示有開啟 Grok */
    fun send(a: Activity, n: Note): Boolean {
        val p = prompt(n, ZonedDateTime.now(Names.zone))
        a.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putLong("grokSrc", n.id).apply()
        val long = p.length > LONG
        if (long) copy(a, p)
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, p)
        return try { a.startActivity(Intent(i).setPackage(PKG))
            Toast.makeText(a, if (long) "逐字稿很長（${p.length} 字），已同時複製；若 Grok 沒收到完整內容請直接貼上" else "已送到 Grok，整理好後複製 Grok 的回覆，回來按「貼上待辦」", Toast.LENGTH_LONG).show(); true
        } catch (e: ActivityNotFoundException) {
            copy(a, p); Toast.makeText(a, "已複製，請貼到 Grok", Toast.LENGTH_LONG).show()
            runCatching { a.startActivity(Intent.createChooser(i, "送到 Grok 整理")) }; false
        }
    }
    fun copy(ctx: Context, s: String) = ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("錄音記事本", s))
    fun clip(ctx: Context): String = ctx.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""

    // ---------- 解析 Grok 回覆 ----------
    /** 從回覆找出 JSON：```json 區塊 → ``` 區塊 → 第一個 { 到最後一個 } */
    fun extractJson(s: String): String? {
        Regex("```(?:json|JSON)?\\s*\\n?([\\s\\S]*?)```").findAll(s).map { it.groupValues[1].trim() }.firstOrNull { it.startsWith("{") || it.startsWith("[") }?.let { return it }
        val la = s.indexOf('['); val lb = s.lastIndexOf(']'); val fa = s.indexOf('{')
        if (la >= 0 && lb > la && (fa < 0 || la < fa)) return s.substring(la, lb + 1)
        val a = s.indexOf('{').let { if (it < 0) s.indexOf('｛') else it }; val b = maxOf(s.lastIndexOf('}'), s.lastIndexOf('｝'))
        return if (a >= 0 && b > a) s.substring(a, b + 1) else null
    }
    /** 容錯：全形符號、智慧引號、尾逗號、註解 */
    fun lenient(j: String): String = j
        .replace('｛', '{').replace('｝', '}').replace('［', '[').replace('］', ']')
        .replace('“', '"').replace('”', '"').replace('＂', '"')
        .replace(Regex("(?<=[\"\\]}0-9el])\\s*，\\s*(?=[\"\\[{\\]}a-z0-9-])"), ",")
        .replace(Regex("\"\\s*：\\s*"), "\":")
        .replace(Regex("(?m)^\\s*//.*$"), "")
        .replace(Regex(",\\s*([}\\]])"), "$1")

    private val K_TEXT = listOf("內容", "内容", "待辦", "事項", "content", "text", "task", "title")
    private val K_OWNER = listOf("負責人", "负责人", "owner", "assignee", "who")
    private val K_TIME = listOf("時間", "时间", "截止或提醒時間", "提醒時間", "截止時間", "due", "time", "datetime", "deadline")
    private val K_PRI = listOf("優先度", "优先度", "優先級", "priority")
    private val K_SUM = listOf("摘要", "重點摘要", "重点摘要", "summary")
    private val K_TODOS = listOf("todos", "待辦", "待辦事項", "待办", "tasks", "items")
    private fun JSONObject.pick(keys: List<String>): Any? { for (k in keys) if (has(k) && !isNull(k)) return get(k); return null }
    private fun str(v: Any?): String? = v?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" && it != "無" && it != "无" }

    fun pri(s: String?) = when { s == null -> "中"; s.contains("高") || s.contains("high", true) || s.contains("急") -> "高"
        s.contains("低") || s.contains("low", true) -> "低"; else -> "中" }

    /** ISO 8601 → epoch ms；沒時區當台北；只有日期用 09:00；中文說法交給 TimeParse */
    fun time(s: String?, now: ZonedDateTime): Long? {
        if (s.isNullOrBlank()) return null
        val t = s.trim().replace(' ', 'T').replace('／', '-').replace('/', '-')
        return runCatching { OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(t).atZone(tz).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(t + ":00").atZone(tz).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.LocalDate.parse(t.take(10)).atTime(9, 0).atZone(tz).toInstant().toEpochMilli() }.getOrNull()
            ?: TimeParse.find(s, now)?.toInstant()?.toEpochMilli()
    }

    fun parseJson(j: String, now: ZonedDateTime): TodoResult? {
        val root: Any = runCatching { JSONObject(j) }.getOrNull() ?: runCatching { JSONObject(lenient(j)) }.getOrNull()
            ?: runCatching { JSONArray(lenient(j)) }.getOrNull() ?: return null
        val arr: JSONArray? = when (root) { is JSONArray -> root; is JSONObject -> root.pick(K_TODOS) as? JSONArray; else -> null }
        val todos = mutableListOf<Todo>()
        if (arr != null) for (i in 0 until arr.length()) {
            when (val x = arr.get(i)) {
                is JSONObject -> { val text = str(x.pick(K_TEXT)) ?: continue
                    todos += Todo(text, str(x.pick(K_OWNER)), time(str(x.pick(K_TIME)), now), pri(str(x.pick(K_PRI)))) }
                is String -> if (x.isNotBlank()) todos += Todo(x.trim(), null, TimeParse.find(x, now)?.toInstant()?.toEpochMilli(), "中")
            }
        }
        val sum = mutableListOf<String>()
        (root as? JSONObject)?.pick(K_SUM)?.let { s -> if (s is JSONArray) for (i in 0 until s.length()) str(s.opt(i))?.let { sum += it.trimStart('•', '-', ' ') }
            else str(s)?.split('\n')?.mapNotNull { str(it.trimStart('•', '-', ' ')) }?.let { sum += it } }
        return TodoResult(todos, sum)
    }

    private val bullet = Regex("^\\s*(?:[-*•・·□☐☑✅▪◦]|\\d{1,2}\\s*[.、)）．]|[（(]\\d{1,2}[)）]|[一二三四五六七八九十]+、)\\s*(.+)$")
    /** 沒有 JSON 時：解析條列文字。「摘要／重點」段落後的條列當摘要 */
    fun parseLines(s: String, now: ZonedDateTime): TodoResult {
        val todos = mutableListOf<Todo>(); val sum = mutableListOf<String>(); var inSum = false
        for (raw in s.lines()) {
            val l = raw.trim().replace("**", ""); if (l.isEmpty() || l.startsWith("```")) continue
            val m = bullet.find(l)
            if (m == null) { if (Regex("摘要|重點|重点|總結|总结|summary", RegexOption.IGNORE_CASE).containsMatchIn(l) && l.length < 20) inSum = true
                else if (Regex("待辦|待办|待做|事項|todo", RegexOption.IGNORE_CASE).containsMatchIn(l) && l.length < 20) inSum = false
                continue }
            var t = m.groupValues[1].trim()
            if (inSum) { sum += t; continue }
            var owner: String? = null; var p = "中"
            Regex("[（(【\\[]?(?:優先度|优先度|優先|priority)[:：]?\\s*([高中低])[)）】\\]]?").find(t)?.let { p = it.groupValues[1]; t = t.replace(it.value, "") }
            Regex("[【\\[]([高中低])[】\\]]").find(t)?.let { p = it.groupValues[1]; t = t.replace(it.value, "") }
            Regex("[（(]?(?:負責人|负责人|負責|负责)[:：]\\s*([^，,；;）)]+)[)）]?").find(t)?.let { owner = it.groupValues[1].trim(); t = t.replace(it.value, "") }
            val due = Regex("\\d{4}-\\d{2}-\\d{2}(?:[T ]\\d{2}:\\d{2}(?::\\d{2})?(?:[+-]\\d{2}:\\d{2})?)?").find(t)?.let { time(it.value, now) }
                ?: TimeParse.find(t, now)?.toInstant()?.toEpochMilli()
            t = t.replace(Regex("[｜|]\\s*$"), "").trim().trimEnd('，', ',', '；', ';', '—', '-', ' ')
            if (t.isNotEmpty()) todos += Todo(t, owner, due, p)
        }
        return TodoResult(todos, sum)
    }

    /** 主入口：JSON 優先，失敗再用條列 */
    fun parseReply(s: String, now: ZonedDateTime): TodoResult {
        extractJson(s)?.let { j -> parseJson(j, now)?.let { if (it.todos.isNotEmpty() || it.summary.isNotEmpty()) return dedupe(it) } }
        return dedupe(parseLines(s, now))
    }
    private fun norm(s: String) = s.replace(Regex("[\\s，。、！？,.!?：:「」]"), "").lowercase()
    fun dedupe(r: TodoResult): TodoResult {
        val out = mutableListOf<Todo>()
        for (t in r.todos) { val k = norm(t.text); val i = out.indexOfFirst { norm(it.text) == k }
            if (i < 0) out += t else out[i] = out[i].copy(owner = out[i].owner ?: t.owner, due = out[i].due ?: t.due) }
        return TodoResult(out, r.summary.distinctBy { norm(it) })
    }

    fun toJson(r: TodoResult): String = JSONObject().put("摘要", JSONArray(r.summary)).put("todos", JSONArray().apply {
        r.todos.forEach { put(JSONObject().put("內容", it.text).put("負責人", it.owner ?: JSONObject.NULL).put("優先度", it.priority)
            .put("時間", it.due?.let { d -> OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(d), tz).toString() } ?: JSONObject.NULL)) } }).toString()
}
