package tw.voicememo

import android.Manifest
import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import java.io.File
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.concurrent.thread

class NoteActivity : AppCompatActivity() {
    lateinit var note: Note; private var isNew = false; private var pendingFile: File? = null; private var deleted = false
    private lateinit var title: EditText; private lateinit var caseNo: EditText; private lateinit var building: EditText; private lateinit var floor: EditText
    private lateinit var text: EditText; private lateinit var tags: EditText; private lateinit var addr: EditText; private lateinit var locSw: SwitchCompat
    private lateinit var remindBox: View; private lateinit var remindBtn: TextView; private lateinit var memoBox: View; private lateinit var noteBox: View
    private lateinit var pin: CheckBox; private lateinit var done: CheckBox; private lateinit var textLabel: TextView
    private val typeViews = mutableListOf<TextView>(); private var typeTouched = false; private var addrTouched = false
    private var player: MediaPlayer? = null; private var speed = 1f; private lateinit var seek: SeekBar; private lateinit var pos: TextView; private lateinit var playBtn: TextView
    private val speedViews = mutableListOf<TextView>()
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { player?.let { if (!dragging) { seek.progress = it.currentPosition; showPos(it.currentPosition) } }; h.postDelayed(this, 200) } }
    private var dragging = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val id = intent.getLongExtra("id", 0)
        val p = if (intent.getBooleanExtra("pending", false)) RecService.pending(this) else null
        note = when {
            id != 0L -> Store.get(this, id) ?: run { finish(); return }
            p != null -> { isNew = true; pendingFile = p.first
                Note(p.third, Names.defaultTitle(Names.at(p.third)), durationMs = p.second, type = Note.MEMO) }
            else -> { isNew = true; val now = System.currentTimeMillis(); Note(now, Names.defaultTitle(Names.at(now)).replace("錄音", "記事"), type = Note.MEMO) }
        }
        build()
        if (isNew && locSw.isChecked) locate()
    }
    private fun audioFile(): File? = pendingFile ?: if (note.hasAudio) Store.audio(this, note) else null

    private fun build() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(40)) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(btn("←") { save(); finish() })
        head.addView(tv(if (isNew) "新增記事" else "編輯記事", 20f, C.TX, true).apply { setPadding(dp(12), 0, 0, 0) }, lp(0, WC, 1f))
        if (!isNew) head.addView(btn("刪除", C.CARD2, C.RED) { confirmDelete() }, lp(WC, WC).apply { rightMargin = dp(8) })
        head.addView(btn("☁ 上傳") { save(); Share.ask(this, listOf(note)) })
        col.addView(head)
        audioFile()?.let { col.addView(buildPlayer(it), lp(MP, WC).apply { topMargin = dp(14) }) }

        col.addView(label("類型"))
        val seg = LinearLayout(this).apply { background = round(C.CARD, dp(20).toFloat()); setPadding(dp(3), dp(3), dp(3), dp(3)) }
        Note.TYPES.forEachIndexed { i, t -> val v = tv(t, 15f, C.SUB, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(9), 0, dp(9))
            setOnClickListener { typeTouched = true; setType(i, true) } }; typeViews += v; seg.addView(v, lp(0, WC, 1f)) }
        col.addView(seg)
        col.addView(label("標題")); title = field("標題").apply { setText(note.title) }; col.addView(title)

        remindBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
            addView(label("提醒時間")); remindBtn = btn("選擇提醒時間", C.CARD, C.OR) { pickTime() }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }; addView(remindBtn) }
        col.addView(remindBox)
        memoBox = LinearLayout(this).apply { setPadding(0, dp(10), 0, 0)
            pin = CheckBox(context).apply { text = "📌 置頂"; setTextColor(C.TX); isChecked = note.pinned }; addView(pin, lp(0, WC, 1f))
            done = CheckBox(context).apply { text = "已完成"; setTextColor(C.TX); isChecked = note.done }; addView(done, lp(0, WC, 1f)) }
        col.addView(memoBox)
        noteBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
            addView(label("標籤（用空格或逗號分隔）")); tags = field("例：漏水 水電 待料").apply { setText(note.tags) }; addView(tags) }
        col.addView(noteBox)

        col.addView(label("案號")); caseNo = field("例：A-1023").apply { setText(note.caseNo) }; col.addView(caseNo)
        val bf = LinearLayout(this)
        building = field("棟別").apply { setText(note.building) }; floor = field("樓層").apply { setText(note.floor) }
        bf.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(label("棟別")); addView(building) }, lp(0, WC, 1f).apply { rightMargin = dp(10) })
        bf.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(label("樓層")); addView(floor) }, lp(0, WC, 1f))
        col.addView(bf)
        textLabel = label("內容"); col.addView(textLabel)
        text = field("可直接打字，或用鍵盤的🎤語音輸入轉成文字", true).apply { setText(note.text) }; col.addView(text)
        text.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { autoType() }
        })

        val locRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14), 0, 0) }
        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        locSw = SwitchCompat(this).apply { text = "附加定位地址"; setTextColor(C.TX); isChecked = if (isNew) ui.getBoolean("loc", true) else note.address.isNotBlank()
            setOnCheckedChangeListener { _, c -> ui.edit().putBoolean("loc", c).apply(); addr.visibility = if (c) View.VISIBLE else View.GONE; if (c && addr.text.isBlank()) locate() } }
        locRow.addView(locSw, lp(0, WC, 1f)); locRow.addView(btn("重新定位") { addrTouched = false; locate() })
        col.addView(locRow)
        addr = field("地址（抓不到時可手動填）").apply { setText(note.address); visibility = if (locSw.isChecked) View.VISIBLE else View.GONE
            setOnKeyListener { _, _, _ -> addrTouched = true; false } }
        col.addView(addr, lp(MP, WC).apply { topMargin = dp(8) })
        col.addView(btn("儲存", C.OR, 0xFF1A1000.toInt()) { if (save()) finish() }, lp(MP, WC).apply { topMargin = dp(24) })
        setContentView(ScrollView(this).apply { setBackgroundColor(C.BG); addView(col) })
        if (isNew) autoType() else setType(note.type, false)
    }

    private fun detect(): ZonedDateTime? = TimeParse.find(title.text.toString() + " " + text.text.toString(), ZonedDateTime.now(Names.zone))

    private fun autoType() {
        if (!isNew || typeTouched) return
        val t = detect()
        if (t != null) { note.remindAt = t.toInstant().toEpochMilli(); setType(Note.REMIND, false) } else setType(Note.MEMO, false)
    }

    fun setType(t: Int, user: Boolean) {
        val old = note.type; note.type = t
        typeViews.forEachIndexed { i, v -> val on = i == t; v.setTextColor(if (on) 0xFF1A1000.toInt() else C.SUB); v.background = if (on) round(C.OR, dp(17).toFloat()) else null }
        remindBox.visibility = if (t == Note.REMIND) View.VISIBLE else View.GONE
        memoBox.visibility = if (t == Note.MEMO) View.VISIBLE else View.GONE
        noteBox.visibility = if (t == Note.NOTE) View.VISIBLE else View.GONE
        textLabel.text = when (t) { Note.NOTE -> "內容／逐字稿"; Note.MEMO -> "備忘內容"; else -> "提醒內容" }
        text.minLines = if (t == Note.NOTE) 8 else 3
        updateRemindBtn()
        if (user && t == Note.REMIND && old != Note.REMIND) {
            if (note.remindAt <= System.currentTimeMillis()) note.remindAt = detect()?.toInstant()?.toEpochMilli() ?: (System.currentTimeMillis() + 3600_000)
            pickTime()
        }
        if (user && old == Note.REMIND && t != Note.REMIND && !isNew) { Reminders.cancel(this, note); Toast.makeText(this, "已取消提醒", Toast.LENGTH_SHORT).show() }
    }
    private fun updateRemindBtn() { remindBtn.text = if (note.remindAt > 0) "⏰  " + Names.date(Names.at(note.remindAt)) + "（點此修改）" else "⏰  選擇提醒時間" }

    private fun pickTime() {
        val base = Names.at(if (note.remindAt > 0) note.remindAt else System.currentTimeMillis() + 3600_000)
        DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, hh, mm ->
                note.remindAt = base.withYear(y).withMonth(m + 1).withDayOfMonth(d).withHour(hh).withMinute(mm).withSecond(0).toInstant().toEpochMilli()
                updateRemindBtn()
            }, base.hour, base.minute, true).show()
        }, base.year, base.monthValue - 1, base.dayOfMonth).show()
    }

    fun save(): Boolean {
        if (deleted) return true
        note.title = title.text.toString().trim().ifEmpty { Names.defaultTitle(Names.at(note.id)) }
        note.caseNo = caseNo.text.toString().trim(); note.building = building.text.toString().trim(); note.floor = floor.text.toString().trim()
        note.text = text.text.toString(); note.tags = tags.text.toString().trim(); note.pinned = pin.isChecked; note.done = done.isChecked
        note.address = if (locSw.isChecked) addr.text.toString().trim() else ""; if (!locSw.isChecked) note.coords = ""
        if (note.type == Note.REMIND && note.remindAt <= 0) { pickTime(); Toast.makeText(this, "請選擇提醒時間", Toast.LENGTH_SHORT).show(); return false }
        pendingFile?.let { f ->
            var dest = File(Store.dir(this), Names.base(Names.at(note.id), note.caseNo) + ".m4a"); var k = 2
            while (dest.exists()) dest = File(Store.dir(this), Names.base(Names.at(note.id), note.caseNo) + "_${k++}.m4a")
            if (!f.renameTo(dest)) f.copyTo(dest, true).also { f.delete() }
            note.file = dest.name; pendingFile = null; RecService.clearPending(this)
            player?.release(); player = null
            val ex = Share.export(this, dest); note.exported = ex
            if (ex.isEmpty()) Toast.makeText(this, "已儲存，但複製到「音樂/錄音記事本」失敗", Toast.LENGTH_LONG).show()
        }
        Store.save(this, note); isNew = false
        if (!Reminders.sync(this, note) && note.type == Note.REMIND) {
            Toast.makeText(this, "提醒可能會晚幾分鐘，可在設定允許「鬧鐘與提醒」", Toast.LENGTH_LONG).show()
            if (Build.VERSION.SDK_INT >= 31) runCatching { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) }
        }
        if (note.type == Note.REMIND && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
        return true
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this).setTitle("刪除這筆記事？").setMessage("「${note.title}」和錄音檔會從 App 裡刪除（已存到「音樂/錄音記事本」的副本不會刪）。")
            .setPositiveButton("刪除") { _, _ -> Reminders.cancel(this, note); player?.release(); player = null; Store.delete(this, note); deleted = true; finish() }
            .setNegativeButton("取消", null).show()
    }

    private fun buildPlayer(f: File): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = round(C.CARD, dp(16).toFloat()); setPadding(dp(14), dp(12), dp(14), dp(12))
        val r1 = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        playBtn = btn("▶", C.OR, 0xFF1A1000.toInt()) { toggle(f) }; r1.addView(playBtn, lp(dp(52), dp(52)))
        seek = SeekBar(context).apply { max = note.durationMs.toInt().coerceAtLeast(1)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { if (u) showPos(v) }
                override fun onStartTrackingTouch(s: SeekBar?) { dragging = true }
                override fun onStopTrackingTouch(s: SeekBar?) { dragging = false; ensure(f)?.seekTo(s!!.progress) }
            }) }
        r1.addView(seek, lp(0, WC, 1f)); addView(r1)
        val r2 = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        pos = tv("00:00 / " + Names.dur(note.durationMs), 13f, C.SUB); r2.addView(pos, lp(0, WC, 1f))
        listOf(1f, 1.5f, 2f).forEach { s -> val v = tv(if (s == 1.5f) "1.5x" else "${s.toInt()}x", 13f, C.SUB, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), dp(6), dp(12), dp(6)); tag = s; setOnClickListener { speed = s; applySpeed() } }
            speedViews += v; r2.addView(v) }
        addView(r2); applySpeed()
    }
    private fun showPos(ms: Int) { pos.text = Names.dur(ms.toLong()) + " / " + Names.dur(note.durationMs) }
    private fun ensure(f: File): MediaPlayer? {
        if (player == null) player = try { MediaPlayer().apply { setDataSource(f.absolutePath); prepare()
            if (duration > 0) seek.max = duration; setOnCompletionListener { playBtn.text = "▶"; seek.progress = 0; showPos(0) } } }
            catch (e: Exception) { Toast.makeText(this, "無法播放：${e.message}", Toast.LENGTH_LONG).show(); null }
        return player
    }
    private fun toggle(f: File) {
        val p = ensure(f) ?: return
        if (p.isPlaying) { p.pause(); playBtn.text = "▶" } else { p.start(); applySpeed(); playBtn.text = "❚❚"; h.removeCallbacks(tick); h.post(tick) }
    }
    private fun applySpeed() {
        speedViews.forEach { v -> val on = v.tag == speed; v.setTextColor(if (on) 0xFF1A1000.toInt() else C.SUB); v.background = if (on) round(C.OR, dp(14).toFloat()) else null }
        player?.let { if (it.isPlaying) runCatching { it.playbackParams = it.playbackParams.setSpeed(speed) } }
    }

    @SuppressLint("MissingPermission")
    private fun locate() {
        val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (perms.none { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) { requestPermissions(perms, 5); return }
        val lm = getSystemService(LocationManager::class.java)
        val prov = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (prov.isEmpty()) { Toast.makeText(this, "請開啟定位", Toast.LENGTH_SHORT).show(); return }
        prov.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }?.let { got(it) }
        if (Build.VERSION.SDK_INT >= 30) prov.forEach { pr -> runCatching { lm.getCurrentLocation(pr, null, mainExecutor) { it?.let(::got) } } }
        else prov.forEach { pr -> runCatching { @Suppress("DEPRECATION") lm.requestSingleUpdate(pr, { l: Location -> got(l) }, Looper.getMainLooper()) } }
    }
    private var best: Location? = null
    private fun got(l: Location) {
        if (best != null && best!!.accuracy < l.accuracy && best!!.time > l.time - 60_000) return
        best = l; note.coords = "%.5f, %.5f (±%dm)".format(Locale.US, l.latitude, l.longitude, l.accuracy.toInt())
        if (!Geocoder.isPresent()) return
        thread { val a = runCatching { @Suppress("DEPRECATION") Geocoder(this, Locale.TAIWAN).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()?.getAddressLine(0) }.getOrNull()
            ?.replace(Regex("^\\d{3,6}\\s*"), "")?.replace("台灣", "")?.trim()
            if (a != null) runOnUiThread { if (!addrTouched && !isFinishing && locSw.isChecked) addr.setText(a) } }
    }
    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r); if (c == 5 && r.any { it == PackageManager.PERMISSION_GRANTED }) locate()
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (save()) @Suppress("DEPRECATION") super.onBackPressed() }
    override fun onPause() { super.onPause(); player?.let { if (it.isPlaying) { it.pause(); playBtn.text = "▶" } } }
    override fun onDestroy() { h.removeCallbacks(tick); player?.release(); player = null; super.onDestroy() }
}
