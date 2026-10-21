package tw.voicememo

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
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
import java.io.File
import java.time.ZonedDateTime

class NoteActivity : AppCompatActivity() {
    lateinit var note: Note; private var isNew = false; private var deleted = false
    private lateinit var title: EditText
    private lateinit var text: EditText; private lateinit var tags: EditText
    private lateinit var asrBox: LinearLayout; private lateinit var asrText: TextView; private lateinit var asrBtn: TextView
    private var lastAsr: String? = null; private var origTitle = ""
    private lateinit var remindBox: View; private lateinit var remindBtn: TextView; private lateinit var memoBox: View; private lateinit var noteBox: View
    private lateinit var pin: CheckBox; private lateinit var done: CheckBox; private lateinit var textLabel: TextView
    private val typeViews = mutableListOf<TextView>(); private var typeTouched = false
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
            p != null -> Note(p.third, Names.defaultTitle(Names.at(p.third)), durationMs = p.second, type = Note.MEMO, file = p.first.name).also {
                Store.save(this, it); RecService.clearPending(this); isNew = true; if (Transcriber.auto(this)) startAsr(it, false) }
            else -> { isNew = true; val now = System.currentTimeMillis(); Note(now, Names.defaultTitle(Names.at(now)).replace("錄音", "記事"), type = Note.MEMO) }
        }
        origTitle = note.title; build()
    }
    private fun audioFile(): File? = if (note.hasAudio) Store.audio(this, note) else null

    /** 開始轉文字；模型沒下載時先問 */
    fun startAsr(n: Note, askOverwrite: Boolean) {
        if (askOverwrite && n.text.isNotBlank()) { AlertDialog.Builder(this).setTitle("重新轉文字？").setMessage("目前的內容會被新的逐字稿取代。")
            .setPositiveButton("重新轉") { _, _ -> startAsr(n, false) }.setNegativeButton("取消", null).show(); return }
        if (!AsrModel.ready(this)) { AlertDialog.Builder(this).setTitle("下載語音轉文字模型").setMessage("第一次使用需要下載 SenseVoice 中文模型，約 ${AsrModel.SIZE_MB}MB，建議用 Wi-Fi。下載後完全離線辨識，不會上傳錄音。")
            .setPositiveButton("下載並轉文字") { _, _ -> go(n) }.setNegativeButton("先不要", null).show(); return }
        go(n)
    }
    private fun go(n: Note) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4)
        if (n.text.isBlank() || n.asr != 0) { n.text = ""; n.asr = 1; Store.save(this, n) }
        Transcriber.enqueue(this, n.id)
    }
    private val asrPoll = object : Runnable { override fun run() { refreshAsr(); h.postDelayed(this, 500) } }
    private fun refreshAsr() {
        if (!::asrBox.isInitialized) return
        val st = Transcriber.status(note.id)
        Transcriber.error?.let { Transcriber.error = null; Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        if (st != null || lastAsr != null) Store.get(this, note.id)?.let { fresh ->
            if (fresh.text != note.text) { note.text = fresh.text; text.setText(fresh.text) }
            if (title.text.toString() == origTitle && fresh.title != origTitle) { title.setText(fresh.title); origTitle = fresh.title; note.title = fresh.title }
            if (fresh.file != note.file) note.file = fresh.file
            note.exported = fresh.exported; note.asr = fresh.asr
            if (st == null && !typeTouched && fresh.type != note.type) { note.remindAt = fresh.remindAt; setType(fresh.type, false) }
        }
        lastAsr = st
        asrText.text = st ?: when { !note.hasAudio -> ""; note.asr == 2 -> "✓ 已轉成文字（SenseVoice 離線辨識）"; else -> "可把錄音轉成文字" }
        asrBtn.text = if (st != null) "取消" else if (note.asr == 0 && note.text.isBlank()) "轉文字" else "重新轉文字"
        text.isEnabled = st == null; text.hint = if (st != null) "轉文字中，完成後可編輯…" else "可直接打字，或按「轉文字」"
    }

    private fun build() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(40)) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(btn("←") { save(); finish() })
        head.addView(tv(if (isNew) "新增記事" else "編輯記事", 20f, C.TX, true).apply { setPadding(dp(12), 0, 0, 0) }, lp(0, WC, 1f))
        if (!isNew) head.addView(btn("刪除", C.CARD2, C.RED) { confirmDelete() }, lp(WC, WC).apply { rightMargin = dp(8) })
        head.addView(btn("☁ 上傳") { save(); Share.ask(this, listOf(note)) })
        col.addView(head)
        audioFile()?.let { col.addView(buildPlayer(), lp(MP, WC).apply { topMargin = dp(14) }) }
        asrBox = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(10), 0, 0); visibility = if (note.hasAudio) View.VISIBLE else View.GONE }
        asrText = tv("", 13f, C.OR); asrBox.addView(asrText, lp(0, WC, 1f))
        asrBtn = btn("轉文字") { if (Transcriber.status(note.id) != null) Transcriber.cancel(note.id) else { save(); startAsr(note, true) } }; asrBox.addView(asrBtn)
        col.addView(asrBox)

        col.addView(label("類型"))
        val seg = LinearLayout(this).apply { background = round(C.CARD, dp(20).toFloat()); setPadding(dp(3), dp(3), dp(3), dp(3)) }
        Note.TYPES.forEachIndexed { i, t -> val v = tv(t, 15f, C.SUB, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(9), 0, dp(9))
            setOnClickListener { typeTouched = true; note.autoType = false; setType(i, true) } }; typeViews += v; seg.addView(v, lp(0, WC, 1f)) }
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

        textLabel = label("內容"); col.addView(textLabel)
        text = field("可直接打字，或按「轉文字」", true).apply { setText(note.text) }; col.addView(text)
        text.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { autoType() }
        })

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
        if (Transcriber.status(note.id) == null) note.text = text.text.toString(); note.tags = tags.text.toString().trim(); note.pinned = pin.isChecked; note.done = done.isChecked
        if (note.type == Note.REMIND && note.remindAt <= 0) { pickTime(); Toast.makeText(this, "請選擇提醒時間", Toast.LENGTH_SHORT).show(); return false }
        Store.get(this, note.id)?.let { f -> note.file = f.file; note.exported = f.exported; note.asr = f.asr; if (Transcriber.status(note.id) != null) note.text = f.text }
        Store.save(this, note); isNew = false
        if (Transcriber.status(note.id) == null) Files.finalize(this, note)
        if (note.exported == "failed") Toast.makeText(this, "已儲存，但複製到「音樂/錄音記事本」失敗", Toast.LENGTH_SHORT).show()
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
            .setPositiveButton("刪除") { _, _ -> Transcriber.cancel(note.id); Reminders.cancel(this, note); player?.release(); player = null; Store.delete(this, note); deleted = true; finish() }
            .setNegativeButton("取消", null).show()
    }

    private fun buildPlayer(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = round(C.CARD, dp(16).toFloat()); setPadding(dp(14), dp(12), dp(14), dp(12))
        val r1 = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        playBtn = btn("▶", C.OR, 0xFF1A1000.toInt()) { toggle() }; r1.addView(playBtn, lp(dp(52), dp(52)))
        seek = SeekBar(context).apply { max = note.durationMs.toInt().coerceAtLeast(1)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { if (u) showPos(v) }
                override fun onStartTrackingTouch(s: SeekBar?) { dragging = true }
                override fun onStopTrackingTouch(s: SeekBar?) { dragging = false; ensure()?.seekTo(s!!.progress) }
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
    private fun ensure(): MediaPlayer? {
        if (player == null) player = try { MediaPlayer().apply { setDataSource(audioFile()!!.absolutePath); prepare()
            if (duration > 0) seek.max = duration; setOnCompletionListener { playBtn.text = "▶"; seek.progress = 0; showPos(0) } } }
            catch (e: Exception) { Toast.makeText(this, "無法播放：${e.message}", Toast.LENGTH_LONG).show(); null }
        return player
    }
    private fun toggle() {
        val p = ensure() ?: return
        if (p.isPlaying) { p.pause(); playBtn.text = "▶" } else { p.start(); applySpeed(); playBtn.text = "❚❚"; h.removeCallbacks(tick); h.post(tick) }
    }
    private fun applySpeed() {
        speedViews.forEach { v -> val on = v.tag == speed; v.setTextColor(if (on) 0xFF1A1000.toInt() else C.SUB); v.background = if (on) round(C.OR, dp(14).toFloat()) else null }
        player?.let { if (it.isPlaying) runCatching { it.playbackParams = it.playbackParams.setSpeed(speed) } }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (save()) @Suppress("DEPRECATION") super.onBackPressed() }
    override fun onPause() { super.onPause(); player?.let { if (it.isPlaying) { it.pause(); playBtn.text = "▶" } } }
    override fun onResume() { super.onResume(); h.removeCallbacks(asrPoll); h.post(asrPoll) }
    override fun onDestroy() { h.removeCallbacks(tick); h.removeCallbacks(asrPoll); player?.release(); player = null; super.onDestroy() }
}
