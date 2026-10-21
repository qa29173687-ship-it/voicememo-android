package tw.voicememo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
    private lateinit var timer: TextView; private lateinit var status: TextView; private lateinit var level: LevelView
    private lateinit var recBtn: RecButton; private lateinit var controls: LinearLayout; private lateinit var pauseBtn: TextView
    private lateinit var recPage: View; private lateinit var listPage: View; private lateinit var tabRec: TextView; private lateinit var tabList: TextView
    private lateinit var selBar: LinearLayout; private lateinit var selText: TextView; private lateinit var empty: TextView
    private val filterViews = mutableListOf<TextView>()
    private var notes = listOf<Note>(); private var shown = listOf<Note>(); private var query = ""; var filter = -1
    private var selecting = false; private val selected = mutableSetOf<Long>()
    private val h = Handler(Looper.getMainLooper()); private var ticks = 0; private var hadAsr = false
    private val poll = object : Runnable { override fun run() { render(); h.postDelayed(this, 100) } }
    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = shown[p].id
        override fun getView(p: Int, v: View?, parent: ViewGroup?) = row(shown[p])
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(18), dp(16), dp(8)) }
        head.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
            addView(tv("錄音記事本", 24f, C.TX, true)); addView(tv("錄音 · 提醒 · 備忘 · 筆記", 13f, C.SUB)) }, lp(0, WC, 1f))
        head.addView(btn("⚙") { settings() }, lp(WC, WC).apply { rightMargin = dp(8) })
        head.addView(btn("＋ 文字", C.CARD2, C.OR) { startActivity(Intent(this, NoteActivity::class.java).putExtra("textOnly", true)) })
        root.addView(head)
        val pages = FrameLayout(this)
        recPage = buildRec(); listPage = buildList()
        pages.addView(recPage); pages.addView(listPage); root.addView(pages, lp(MP, 0, 1f))
        val tabs = LinearLayout(this).apply { setBackgroundColor(0xFF16171A.toInt()); setPadding(dp(12), dp(8), dp(12), dp(10)) }
        tabRec = tv("🎙  錄音", 15f, C.OR, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10)); setOnClickListener { show(false) } }
        tabList = tv("📋  記事", 15f, C.SUB, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10)); setOnClickListener { show(true) } }
        tabs.addView(tabRec, lp(0, WC, 1f)); tabs.addView(tabList, lp(0, WC, 1f)); root.addView(tabs)
        setContentView(root); show(false)
    }

    private fun buildRec(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(24), dp(24), dp(24))
        addView(View(context), lp(1, 0, 1f))
        timer = tv("00:00", 64f, C.TX, true).apply { typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL); gravity = Gravity.CENTER }
        addView(timer); status = tv("點一下開始錄音", 15f, C.SUB).apply { gravity = Gravity.CENTER }; addView(status)
        level = LevelView(context); addView(level, lp(MP, dp(96)).apply { topMargin = dp(28); bottomMargin = dp(28) })
        recBtn = RecButton(context).apply { setOnClickListener { onRecTap() }; contentDescription = "錄音" }
        addView(recBtn, lp(dp(132), dp(132)))
        controls = LinearLayout(context).apply { gravity = Gravity.CENTER; visibility = View.INVISIBLE }
        pauseBtn = btn("⏸  暫停") { RecService.send(this@MainActivity, if (RecService.state == 2) RecService.A_RESUME else RecService.A_PAUSE) }
        controls.addView(pauseBtn, lp(dp(130), WC).apply { rightMargin = dp(12) })
        controls.addView(btn("■  停止", C.OR, 0xFF1A1000.toInt()) { RecService.send(this@MainActivity, RecService.A_STOP) }, lp(dp(130), WC))
        addView(controls, lp(MP, WC).apply { topMargin = dp(24) })
        addView(View(context), lp(1, 0, 1f))
        addView(tv("關閉螢幕或切到其他 App 也會繼續錄音", 12f, C.SUB).apply { gravity = Gravity.CENTER })
    }

    private fun buildList(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), 0)
        val seg = LinearLayout(context).apply { background = round(C.CARD, dp(20).toFloat()); setPadding(dp(3), dp(3), dp(3), dp(3)) }
        listOf(-1 to "全部", Note.REMIND to "提醒", Note.MEMO to "備忘", Note.NOTE to "筆記").forEach { (k, t) ->
            val v = tv(t, 14f, C.SUB, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)); tag = k; setOnClickListener { filter = k; reload() } }
            filterViews += v; seg.addView(v, lp(0, WC, 1f))
        }
        addView(seg)
        addView(field("🔍  搜尋標題、內容、標籤…").apply {
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s.toString(); reload() }
            }) }, lp(MP, WC).apply { topMargin = dp(10) })
        selBar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
        selText = tv("點選要上傳的項目", 14f, C.TX); selBar.addView(selText, lp(0, WC, 1f))
        selBar.addView(btn("全選") { selected.clear(); shown.forEach { selected += it.id }; reload() }, lp(WC, WC).apply { rightMargin = dp(8) })
        selBar.addView(btn("取消") { selecting = false; selected.clear(); reload() }, lp(WC, WC).apply { rightMargin = dp(8) })
        selBar.addView(btn("☁ 上傳", C.OR, 0xFF1A1000.toInt()) { Share.ask(this@MainActivity, notes.filter { it.id in selected }) })
        addView(selBar)
        empty = tv("還沒有記事\n到「錄音」錄一段，或按右上「＋ 文字」", 14f, C.SUB).apply { gravity = Gravity.CENTER; setPadding(0, dp(60), 0, 0) }
        addView(empty)
        val lv = ListView(context).apply { divider = null; dividerHeight = 0; adapter = this@MainActivity.adapter; clipToPadding = false; setPadding(0, dp(6), 0, dp(80)) }
        addView(lv, lp(MP, 0, 1f))
    }

    private fun row(n: Note): View {
        val card = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = round(C.CARD, dp(14).toFloat()); setPadding(dp(14), dp(12), dp(14), dp(12)) }
        if (selecting) card.addView(tv(if (n.id in selected) "☑" else "☐", 22f, C.OR).apply { setPadding(0, 0, dp(12), 0) })
        else if (n.type == Note.MEMO) card.addView(tv(if (n.done) "☑" else "☐", 22f, if (n.done) C.SUB else C.OR).apply {
            setPadding(0, 0, dp(12), 0); setOnClickListener { n.done = !n.done; Store.save(this@MainActivity, n); reload() } })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val colr = when (n.type) { Note.REMIND -> 0xFFFF6B5E.toInt(); Note.MEMO -> C.OR; else -> 0xFF5EA8FF.toInt() }
        top.addView(tv(Note.TYPES[n.type], 11f, colr, true).apply { background = round(0x22FFFFFF, dp(8).toFloat()); setPadding(dp(7), dp(2), dp(7), dp(2)) })
        top.addView(tv((if (n.pinned) "📌 " else "") + n.title, 16f, if (n.done) C.SUB else C.TX, true).apply {
            setPadding(dp(8), 0, 0, 0); isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
            if (n.done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG }, lp(0, WC, 1f))
        col.addView(top)
        val meta = mutableListOf(Names.date(Names.at(n.id)))
        if (n.hasAudio) meta += "🎙 " + Names.dur(n.durationMs)
        Transcriber.status(n.id)?.let { meta += it }
        col.addView(tv(meta.joinToString("  ·  "), 13f, C.SUB).apply { setPadding(0, dp(4), 0, 0) })
        val extra = mutableListOf<String>()
        if (n.type == Note.REMIND && n.remindAt > 0) extra += "⏰ " + Names.date(Names.at(n.remindAt)) + if (n.remindAt < System.currentTimeMillis()) "（已過）" else ""
        if (n.tags.isNotBlank()) extra += n.tags.split(Regex("[,，\\s]+")).filter { it.isNotBlank() }.joinToString(" ") { "#$it" }
        if (extra.isNotEmpty()) col.addView(tv(extra.joinToString("  ·  "), 13f, C.OR).apply { setPadding(0, dp(3), 0, 0) })
        if (n.text.isNotBlank()) col.addView(tv(n.text.replace('\n', ' '), 13f, 0xFFBFC1C7.toInt()).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0) })
        card.addView(col, lp(0, WC, 1f))
        card.setOnClickListener {
            if (selecting) { if (!selected.remove(n.id)) selected += n.id; reload() }
            else startActivity(Intent(this, NoteActivity::class.java).putExtra("id", n.id))
        }
        card.setOnLongClickListener { selecting = true; selected += n.id; reload(); true }
        return FrameLayout(this).apply { setPadding(0, dp(5), 0, dp(5)); addView(card) }
    }

    fun show(list: Boolean) {
        recPage.visibility = if (list) View.GONE else View.VISIBLE; listPage.visibility = if (list) View.VISIBLE else View.GONE
        tabRec.setTextColor(if (list) C.SUB else C.OR); tabList.setTextColor(if (list) C.OR else C.SUB); reload()
    }
    fun reload() {
        notes = Store.all(this)
        shown = notes.filter { (filter < 0 || it.type == filter) && Names.match(it, query) }
            .sortedWith(compareBy<Note> { it.done }.thenByDescending { it.pinned }.thenByDescending { it.id })
        filterViews.forEach { v -> val on = v.tag == filter; v.setTextColor(if (on) 0xFF1A1000.toInt() else C.SUB); v.background = if (on) round(C.OR, dp(17).toFloat()) else null }
        selBar.visibility = if (selecting) View.VISIBLE else View.GONE
        selText.text = if (selected.isEmpty()) "點選要上傳的項目" else "已選 ${selected.size} 筆"
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        if (notes.isEmpty()) empty.text = "還沒有記事\n到「錄音」錄一段，或按右上「＋ 文字」" else if (shown.isEmpty()) empty.text = "找不到符合的項目"
        tabList.text = "📋  記事 (${notes.size})"
        adapter.notifyDataSetChanged()
    }

    fun render() {
        val st = RecService.state
        timer.text = Names.dur(RecService.elapsed())
        status.text = when (st) { 1 -> "● 錄音中"; 2 -> "已暫停"; else -> "點一下開始錄音" }
        status.setTextColor(if (st == 1) C.RED else C.SUB)
        if (st == 1) level.push(sqrt(RecService.amp / 32767f)) else if (st == 0) level.clear()
        recBtn.recording = st != 0; controls.visibility = if (st == 0) View.INVISIBLE else View.VISIBLE
        pauseBtn.text = if (st == 2) "▶  繼續" else "⏸  暫停"
        Transcriber.error?.let { Transcriber.error = null; Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        if (++ticks % 10 == 0 && listPage.visibility == View.VISIBLE && (Transcriber.progress.isNotEmpty() || hadAsr)) { hadAsr = Transcriber.progress.isNotEmpty(); reload() }
        RecService.error?.let { RecService.error = null; Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        if (st == 0) RecService.pending(this)?.let { h.removeCallbacks(poll); startActivity(Intent(this, NoteActivity::class.java).putExtra("pending", true)) }
    }

    private fun settings() {
        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), 0) }
        box.addView(tv("語音轉文字：SenseVoice Small（離線、免費）", 15f, C.TX, true))
        val st = tv("", 14f, C.SUB).apply { setPadding(0, dp(6), 0, dp(10)) }; box.addView(st)
        fun upd() { st.text = when { Transcriber.downloading >= 0 -> "下載中 ${Transcriber.downloading}%"; AsrModel.ready(this) -> "模型已下載（約 ${AsrModel.SIZE_MB}MB）"; else -> "模型未下載（約 ${AsrModel.SIZE_MB}MB，第一次轉文字時下載）" } }
        upd()
        val sw = androidx.appcompat.widget.SwitchCompat(this).apply { text = "錄完自動轉文字"; setTextColor(C.TX); isChecked = ui.getBoolean("autoAsr", true)
            setOnCheckedChangeListener { _, c -> ui.edit().putBoolean("autoAsr", c).apply() } }
        box.addView(sw)
        val d = androidx.appcompat.app.AlertDialog.Builder(this).setTitle("設定").setView(box).setPositiveButton("完成", null)
            .setNeutralButton("刪除模型") { _, _ ->
                if (Transcriber.downloading >= 0) Toast.makeText(this, "下載中，請先取消", Toast.LENGTH_SHORT).show()
                else { AsrModel.delete(this); Toast.makeText(this, "已刪除語音模型", Toast.LENGTH_SHORT).show() } }.show()
        d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).isEnabled = AsrModel.ready(this)
    }

    private fun onRecTap() {
        if (RecService.state != 0) { RecService.send(this, RecService.A_STOP); return }
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (Manifest.permission.RECORD_AUDIO in missing) requestPermissions(missing.toTypedArray(), 7)
        else { if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 8); RecService.send(this, RecService.A_START) }
    }
    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (c == 7) { if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) RecService.send(this, RecService.A_START)
                      else Toast.makeText(this, "需要麥克風權限才能錄音", Toast.LENGTH_LONG).show() }
    }
    override fun onResume() { super.onResume(); reload(); h.removeCallbacks(poll); h.post(poll) }
    override fun onPause() { super.onPause(); h.removeCallbacks(poll) }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (selecting) { selecting = false; selected.clear(); reload() } else @Suppress("DEPRECATION") super.onBackPressed() }
}
