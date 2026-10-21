package tw.voicememo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.time.ZonedDateTime

/** 整理待辦：呼叫 Grok → 勾選要建立的待辦 */
class TodoActivity : AppCompatActivity() {
    companion object {
        fun lastSrc(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getLong("grokSrc", 0)
    }
    private lateinit var src: Note; private lateinit var body: LinearLayout; private lateinit var status: TextView; private lateinit var retry: TextView
    private lateinit var createBtn: TextView; private var result: TodoResult? = null
    private val checks = mutableListOf<CheckBox>(); private var sumCheck: CheckBox? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        src = Store.get(this, intent.getLongExtra("id", 0).takeIf { it != 0L } ?: lastSrc(this))
            ?: run { Toast.makeText(this, "找不到來源錄音，請先在記事裡按「送到 Grok 整理」", Toast.LENGTH_LONG).show(); finish(); return }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(32)) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(btn("←") { finish() })
        head.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
            addView(tv("貼上待辦", 20f, C.TX, true)); addView(tv("來源：${src.title}", 13f, C.SUB).apply { maxLines = 1 }) }, lp(0, WC, 1f))
        col.addView(head)
        status = tv("", 14f, C.SUB).apply { setPadding(dp(4), dp(16), dp(4), dp(8)) }; col.addView(status)
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        retry = btn("📋 從剪貼簿貼上", C.CARD2, C.OR) { fromText(Grok.clip(this)) }; row.addView(retry, lp(WC, WC).apply { rightMargin = dp(8) })
        row.addView(btn("再送一次到 Grok") { Grok.send(this, src) })
        col.addView(row)
        pasteBox = field("或把 Grok 的回覆貼在這裡", true); col.addView(pasteBox, lp(MP, WC).apply { topMargin = dp(10) })
        parseBtn = btn("解析貼上的文字", C.CARD2, C.TX) { fromText(pasteBox.text.toString()) }; col.addView(parseBtn, lp(WC, WC).apply { topMargin = dp(8) })
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; col.addView(body)
        createBtn = btn("建立", C.OR, 0xFF1A1000.toInt()) { create() }.apply { visibility = View.GONE }
        col.addView(createBtn, lp(MP, WC).apply { topMargin = dp(20) })
        setContentView(ScrollView(this).apply { setBackgroundColor(C.BG); addView(col) })
        if (!intent.getBooleanExtra("preview", false)) { val c = intent.getStringExtra("text") ?: Grok.clip(this).takeIf { !it.contains(Grok.MARK) } ?: ""; if (c.isNotBlank()) fromText(c, quiet = true) else status.text = "在 Grok 複製整理好的回覆後，按「從剪貼簿貼上」" }
    }

    private lateinit var pasteBox: android.widget.EditText; private lateinit var parseBtn: TextView
    private fun fromText(t: String, quiet: Boolean = false) {
        if (t.isBlank()) { status.setTextColor(C.RED); status.text = "剪貼簿是空的，請先在 Grok 複製回覆，或貼在下方文字框"; return }
        val r = Grok.parseReply(t, ZonedDateTime.now(Names.zone))
        if (r.todos.isEmpty() && r.summary.isEmpty()) { if (!quiet) { status.setTextColor(C.RED); status.text = "看不出待辦事項。請確認貼的是 Grok 的回覆（```json 區塊或條列）" }
            else status.text = "在 Grok 複製整理好的回覆後，按「從剪貼簿貼上」"; return }
        show(r)
    }

    fun show(r: TodoResult) {
        result = r; body.removeAllViews(); checks.clear(); pasteBox.visibility = View.GONE; parseBtn.visibility = View.GONE
        status.setTextColor(C.SUB); status.text = if (r.todos.isEmpty()) "沒有找到待辦事項" else "找到 ${r.todos.size} 個待辦，勾選要建立的項目："
        for (t in r.todos) {
            val card = LinearLayout(this).apply { background = round(C.CARD, dp(14).toFloat()); setPadding(dp(10), dp(10), dp(14), dp(10)) }
            val cb = CheckBox(this).apply { isChecked = true; setOnCheckedChangeListener { _, _ -> updBtn() } }; checks += cb
            card.addView(cb, lp(WC, WC))
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c.addView(tv(t.text, 16f, C.TX, true))
            val meta = mutableListOf<String>()
            val pc = when (t.priority) { "高" -> "🔴 高"; "低" -> "⚪ 低"; else -> "🟠 中" }; meta += pc
            t.owner?.let { meta += "👤 $it" }
            meta += t.due?.let { "⏰ " + Names.date(Names.at(it)) + if (it < System.currentTimeMillis()) "（已過）" else "" } ?: "無時間 → 備忘"
            c.addView(tv(meta.joinToString("　"), 13f, C.OR).apply { setPadding(0, dp(4), 0, 0) })
            if (t.quote.isNotBlank()) c.addView(tv("「${t.quote}」", 13f, C.SUB).apply { setPadding(0, dp(4), 0, 0) })
            card.addView(c, lp(0, WC, 1f)); card.setOnClickListener { cb.toggle() }
            body.addView(card, lp(MP, WC).apply { topMargin = dp(10) })
        }
        if (r.summary.isNotEmpty()) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(C.CARD, dp(14).toFloat()); setPadding(dp(14), dp(12), dp(14), dp(12)) }
            box.addView(tv("重點摘要", 15f, C.TX, true))
            r.summary.forEach { box.addView(tv("• $it", 14f, 0xFFD5D7DD.toInt()).apply { setPadding(0, dp(4), 0, 0) }) }
            sumCheck = CheckBox(this).apply { text = "把摘要加到原記事"; setTextColor(C.TX); isChecked = true; setOnCheckedChangeListener { _, _ -> updBtn() } }
            box.addView(sumCheck)
            body.addView(box, lp(MP, WC).apply { topMargin = dp(16) })
        }
        updBtn()
    }
    private fun updBtn() {
        val n = checks.count { it.isChecked }; val s = sumCheck?.isChecked == true
        createBtn.visibility = if (n > 0 || s) View.VISIBLE else View.GONE
        createBtn.text = if (n > 0) "建立 $n 個項目" + (if (s) "並加入摘要" else "") else "加入摘要"
    }

    private fun create() {
        val r = result ?: return; var made = 0; val now = System.currentTimeMillis(); var exactOk = true
        r.todos.forEachIndexed { i, t ->
            if (!checks[i].isChecked) return@forEachIndexed
            val future = t.due != null && t.due > now
            val body = listOfNotNull(t.owner?.let { "負責人：$it" }, t.quote.takeIf { it.isNotBlank() }?.let { "原文：「$it」" }, "優先度：${t.priority}", "（由 Grok 整理）").joinToString("\n")
            val n = Note(now + i, t.text.take(40), text = body, type = if (future) Note.REMIND else Note.MEMO, remindAt = if (future) t.due!! else 0,
                pinned = t.priority == "高" && !future, autoType = false, sourceId = src.id)
            Store.save(this, n); if (!Reminders.sync(this, n)) exactOk = false; made++
        }
        if (sumCheck?.isChecked == true && r.summary.isNotEmpty()) Store.get(this, src.id)?.let { s ->
            s.text = s.text.trimEnd() + "\n\n重點摘要：\n" + r.summary.joinToString("\n") { "• $it" }; Store.save(this, s) }
        Toast.makeText(this, "已建立 $made 個項目" + if (!exactOk) "（提醒可能會晚幾分鐘）" else "", Toast.LENGTH_LONG).show()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("list", true)); finish()
    }
}
