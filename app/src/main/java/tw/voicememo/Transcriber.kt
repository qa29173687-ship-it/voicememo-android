package tw.voicememo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import kotlin.math.floor

/** SenseVoice Small (int8) 模型：第一次使用時下載 */
object AsrModel {
    private const val BASE = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/"
    const val MODEL = "model.int8.onnx"; const val TOKENS = "tokens.txt"; const val SIZE_MB = 240
    fun dir(ctx: Context) = File(ctx.filesDir, "sensevoice").apply { mkdirs() }
    fun ready(ctx: Context) = File(dir(ctx), ".ok").exists()
    fun delete(ctx: Context) { dir(ctx).deleteRecursively() }
    // 說話者分辨：pyannote segmentation-3.0 + 3D-Speaker ERes2NetV2 (zh-cn)
    const val SEG = "seg.onnx"; const val EMB = "emb.onnx"; const val DIAR_MB = 77
    private const val SEG_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.onnx"
    private const val EMB_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common.onnx"
    fun ddir(ctx: Context) = File(ctx.filesDir, "diar").apply { mkdirs() }
    fun diarReady(ctx: Context) = File(ddir(ctx), ".ok").exists()
    fun deleteDiar(ctx: Context) { ddir(ctx).deleteRecursively() }
    fun downloadDiar(ctx: Context, progress: (Int) -> Unit, cancelled: () -> Boolean) {
        val d = ddir(ctx); fetch(SEG_URL, File(d, SEG), {}, cancelled); fetch(EMB_URL, File(d, EMB), progress, cancelled); File(d, ".ok").writeText("1")
    }
    /** 可續傳下載；progress 0..100；cancelled() 為 true 時中止 */
    fun download(ctx: Context, progress: (Int) -> Unit, cancelled: () -> Boolean) {
        val d = dir(ctx)
        fetch(BASE + TOKENS, File(d, TOKENS), {}, cancelled)
        fetch(BASE + MODEL, File(d, MODEL), progress, cancelled)
        File(d, ".ok").writeText("1")
    }
    private fun fetch(url: String, dest: File, progress: (Int) -> Unit, cancelled: () -> Boolean) {
        if (dest.exists()) return
        val part = File(dest.path + ".part"); val have = part.length()
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 20000; readTimeout = 30000; instanceFollowRedirects = true
            if (have > 0) setRequestProperty("Range", "bytes=$have-") }
        val code = c.responseCode; if (code !in listOf(200, 206)) throw Exception("下載失敗 HTTP $code")
        val start = if (code == 206) have else 0L; val total = start + c.contentLengthLong
        RandomAccessFile(part, "rw").use { raf -> raf.setLength(start); raf.seek(start)
            c.inputStream.use { inp -> val buf = ByteArray(1 shl 16); var got = start; var last = -1
                while (true) { if (cancelled()) throw InterruptedException(); val n = inp.read(buf); if (n < 0) break
                    raf.write(buf, 0, n); got += n; val p = if (total > 0) (got * 100 / total).toInt() else 0; if (p != last) { last = p; progress(p) } } } }
        if (total > 0 && part.length() != total) throw Exception("下載不完整，請再試一次")
        part.renameTo(dest)
    }
}

/** 串流解碼 m4a → 16kHz 單聲道 float，不把整個檔案讀進記憶體 */
object Decoder {
    class Resampler(private val inRate: Int) {
        private var t = 0.0; private var last = 0f; private val step = inRate / 16000.0
        fun process(x: FloatArray): FloatArray {
            if (x.isEmpty()) return x
            val out = FloatArray(((x.size + 1) / step).toInt() + 2); var k = 0
            while (t < x.size - 1) { val i = floor(t).toInt(); val fr = (t - i).toFloat(); val a = if (i < 0) last else x[i]; val b = x[i + 1]
                out[k++] = a + (b - a) * fr; t += step }
            t -= x.size; last = x[x.size - 1]; return out.copyOf(k)
        }
    }
    /** cb(樣本, 已處理到的微秒, 總長微秒) 回傳 false 中止 */
    fun stream(f: File, cb: (FloatArray, Long, Long) -> Boolean) {
        val ex = MediaExtractor(); ex.setDataSource(f.path)
        val ti = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
        val fmt = ex.getTrackFormat(ti); ex.selectTrack(ti)
        val dur = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT); var rs = Resampler(fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)); var isFloat = false
        val info = MediaCodec.BufferInfo(); var inEos = false
        try {
            while (true) {
                if (!inEos) { val ii = codec.dequeueInputBuffer(10000)
                    if (ii >= 0) { val n = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true }
                        else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() } } }
                val oi = codec.dequeueOutputBuffer(info, 10000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { val of = codec.outputFormat
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT); rs = Resampler(of.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT }
                else if (oi >= 0) {
                    val b = codec.getOutputBuffer(oi)!!.order(ByteOrder.nativeOrder()); b.position(info.offset); b.limit(info.offset + info.size)
                    val mono: FloatArray
                    if (isFloat) { val fb = b.asFloatBuffer(); val n = fb.remaining() / ch; mono = FloatArray(n) { i -> var s = 0f; for (c in 0 until ch) s += fb.get(i * ch + c); s / ch } }
                    else { val sb = b.asShortBuffer(); val n = sb.remaining() / ch; mono = FloatArray(n) { i -> var s = 0f; for (c in 0 until ch) s += sb.get(i * ch + c); s / ch / 32768f } }
                    codec.releaseOutputBuffer(oi, false)
                    if (!cb(rs.process(mono), info.presentationTimeUs, dur)) return
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        } finally { runCatching { codec.stop() }; codec.release(); ex.release() }
    }
}

/** 背景轉文字（前景服務，離開 App 也會繼續）：VAD 切段 → SenseVoice → 簡轉繁 → 邊轉邊存 */
class Transcriber : Service() {
    companion object {
        private val queue = LinkedBlockingDeque<Long>()
        val progress = ConcurrentHashMap<Long, Int>() // -1 排隊、0..100 進度
        @Volatile var downloading = -1; @Volatile var error: String? = null; @Volatile private var cancelId = 0L; @Volatile var cancelAll = false
        fun status(id: Long): String? = progress[id]?.let { p ->
            when { downloading >= 0 -> "下載模型 $downloading%"; p < 0 -> "等待轉文字…"; else -> "$phase… $p%" } }
        @Volatile var phase = "轉文字中"
        fun enqueue(ctx: Context, id: Long) {
            if (progress.putIfAbsent(id, -1) != null) return
            queue.add(id); cancelAll = false
            ContextCompat.startForegroundService(ctx, Intent(ctx, Transcriber::class.java))
        }
        fun cancel(id: Long) { if (queue.remove(id)) progress.remove(id); cancelId = id }
        fun auto(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("autoAsr", true)
        fun diarOn(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("diar", true)
        /** 0 = 自動，2..4 = 固定人數，5 = 5 人以上（自動、門檻放寬） */
        fun spkCount(ctx: Context) = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getInt("spk", 0)
    }
    private var worker: Thread? = null; private var rec: OfflineRecognizer? = null; private var cur = 0L
    private fun nm() = getSystemService(NotificationManager::class.java)
    private fun notif(text: String, p: Int) = NotificationCompat.Builder(this, "asr").setSmallIcon(R.drawable.ic_mic).setOngoing(true).setOnlyAlertOnce(true)
        .setContentTitle("錄音記事本").setContentText(text).setProgress(100, p.coerceAtLeast(0), p < 0)
        .setContentIntent(PendingIntent.getActivity(this, 5, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "取消", PendingIntent.getService(this, 6, Intent(this, Transcriber::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE)).build()
    private fun show(text: String, p: Int) { try { nm().notify(2, notif(text, p)) } catch (e: SecurityException) { } }

    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        nm().createNotificationChannel(NotificationChannel("asr", "轉文字", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, 2, notif("準備轉文字…", -1), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        if (i?.action == "cancel") { cancelAll = true; cancelId = cur; queue.clear(); progress.keys.filter { it != cur }.forEach { progress.remove(it) } }
        if (worker?.isAlive != true) worker = Thread { loop() }.apply { start() }
        return START_NOT_STICKY
    }
    private fun loop() {
        try {
            while (true) {
                val id = queue.poll() ?: break; cur = id; cancelId = 0
                try { if (!AsrModel.ready(this)) download(); if (diarOn(this) && !AsrModel.diarReady(this)) downloadDiar()
                      if (diarOn(this)) runDiar(id) else run(id) }
                catch (e: InterruptedException) { }
                catch (e: Throwable) { error = "轉文字失敗：${e.message}" }
                finally { progress.remove(id); cur = 0; downloading = -1 }
                if (cancelAll) break
            }
        } finally {
            rec?.release(); rec = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }
    private fun stop() = cancelAll || cancelId == cur
    private fun download() {
        downloading = 0; show("下載語音模型（約 ${AsrModel.SIZE_MB}MB）", 0)
        AsrModel.download(this, { downloading = it; show("下載語音模型 $it%", it) }, ::stop); downloading = -1
    }
    private fun downloadDiar() {
        downloading = 0; show("下載說話者模型（約 ${AsrModel.DIAR_MB}MB）", 0)
        AsrModel.downloadDiar(this, { downloading = it; show("下載說話者模型 $it%", it) }, ::stop); downloading = -1
    }
    private fun recognizer(): OfflineRecognizer = rec ?: run {
        val d = AsrModel.dir(this)
        OfflineRecognizer(null, OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig(model = File(d, AsrModel.MODEL).path, language = "zh", useInverseTextNormalization = true),
            tokens = File(d, AsrModel.TOKENS).path, numThreads = 4, debug = false, provider = "cpu"))).also { rec = it }
    }

    private fun run(id: Long) {
        val n0 = Store.get(this, id) ?: return; val f = Store.audio(this, n0); if (!f.exists()) return
        val r = recognizer(); val s2t = S2T.get(this)
        val vad = Vad(assets, VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(model = "silero_vad.onnx", threshold = 0.5f,
            minSilenceDuration = 0.5f, minSpeechDuration = 0.25f, windowSize = 512, maxSpeechDuration = 20f), sampleRate = 16000, numThreads = 1))
        val segs = mutableListOf<Triple<String, Double, Double>>(); val win = FloatArray(512); var wn = 0; var lastSave = 0L
        progress[id] = 0; phase = "轉文字中"; show("轉文字中… 0%", 0)
        fun drain() { while (!vad.empty()) { val sg = vad.front(); vad.pop()
            val st = r.createStream(); st.acceptWaveform(sg.samples, 16000); r.decode(st); val t = r.getResult(st).text; st.release()
            val tt = s2t.convert(t).trim(); if (tt.isNotEmpty()) segs += Triple(tt, sg.start / 16000.0, (sg.start + sg.samples.size) / 16000.0)
            val now = System.currentTimeMillis(); if (now - lastSave > 3000) { lastSave = now; write(id, segs, false) } } }
        try {
            Decoder.stream(f) { x, us, dur ->
                for (v in x) { win[wn++] = v; if (wn == 512) { vad.acceptWaveform(win.copyOf()); wn = 0; drain() } }
                if (dur > 0) { val p = (us * 100 / dur).toInt().coerceIn(0, 99); if (p != progress[id]) { progress[id] = p; show("轉文字中… $p%", p) } }
                !stop()
            }
            if (!stop()) { vad.flush(); drain() }
        } finally { vad.release() }
        write(id, segs, !stop())
    }

    private fun asr(x: FloatArray, from: Int, to: Int): String {
        val r = recognizer(); val sb = StringBuilder(); var a = from
        while (a < to) { // SenseVoice 一次最多約 30 秒，切成 ≤20 秒
            val b = minOf(to, a + 20 * 16000); if (b - a < 16000 * 3 / 10) break
            val st = r.createStream(); st.acceptWaveform(x.copyOfRange(a, b), 16000); r.decode(st); sb.append(r.getResult(st).text); st.release(); a = b
        }
        return S2T.get(this).convert(sb.toString()).trim()
    }

    /** 準確優先：先分辨說話者，再逐段（每個說話者的一段）辨識。每 10 分鐘一個視窗，跨視窗用聲紋比對同一人 */
    private fun runDiar(id: Long) {
        val n0 = Store.get(this, id) ?: return; val f = Store.audio(this, n0); if (!f.exists()) return
        val pcm = File(cacheDir, "asr_$id.pcm"); progress[id] = 0; show("準備音訊…", 0)
        try {
            // 1) 串流解碼成 16kHz 16-bit 暫存檔（每小時約 115MB），不佔記憶體
            pcm.outputStream().buffered(1 shl 16).use { o ->
                Decoder.stream(f) { x, us, dur ->
                    val bb = java.nio.ByteBuffer.allocate(x.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (v in x) bb.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort()); o.write(bb.array())
                    if (dur > 0) setP(id, (us * 10 / dur).toInt(), "整理音訊"); !stop() } }
            if (stop()) { write(id, emptyList<Diar.Turn>(), false); return }
            val total = (pcm.length() / 2).toInt(); val win = 600 * 16000
            val cuts = Diar.cuts(pcm, total, win)
            val spkN = spkCount(this)
            val cfg = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(File(AsrModel.ddir(this), AsrModel.SEG).path), numThreads = 4),
                embedding = SpeakerEmbeddingExtractorConfig(model = File(AsrModel.ddir(this), AsrModel.EMB).path, numThreads = 4),
                clustering = FastClusteringConfig(numClusters = if (spkN in 2..4) spkN else -1, threshold = if (spkN == 5) 0.7f else Diar.THRESHOLD),
                minDurationOn = 0.3f, minDurationOff = 0.5f)
            val sd = OfflineSpeakerDiarization(null, cfg)
            val ex = SpeakerEmbeddingExtractor(null, SpeakerEmbeddingExtractorConfig(model = File(AsrModel.ddir(this), AsrModel.EMB).path, numThreads = 4))
            val link = Diar.Linker(if (spkN in 2..4) spkN else 0); val turns = mutableListOf<Diar.Turn>()
            try {
                for (k in 0 until cuts.size - 1) {
                    if (stop()) break
                    val a = cuts[k]; val b = cuts[k + 1]; val x = Diar.read(pcm, a, b - a)
                    val base = 10 + 90 * k / (cuts.size - 1); val span = 90 / (cuts.size - 1)
                    setP(id, base, "分辨說話者")
                    val segs = sd.processWithCallback(x, { done, all, _ -> if (all > 0) setP(id, base + span / 2 * done / all, "分辨說話者"); 0 }, 0L)
                    val local = Diar.merge(segs.map { Diar.Seg((it.start * 16000).toInt(), (it.end * 16000).toInt(), it.speaker) })
                    // 每個本地說話者取最長幾段算聲紋，對到全域說話者
                    val map = HashMap<Int, Int>()
                    for (spk in local.map { it.spk }.distinct()) {
                        val parts = local.filter { it.spk == spk }.sortedByDescending { it.b - it.a }
                        val st = ex.createStream(); var len = 0
                        for (p in parts) { if (len > 20 * 16000) break; val e = minOf(p.b, p.a + 20 * 16000 - len); st.acceptWaveform(x.copyOfRange(p.a, e), 16000); len += e - p.a }
                        st.inputFinished(); map[spk] = if (ex.isReady(st)) link.assign(ex.compute(st)) else link.assign(null); st.release()
                    }
                    for ((i, t) in local.withIndex()) {
                        if (stop()) break
                        val txt = asr(x, t.a, t.b)
                        if (txt.isNotEmpty()) turns += Diar.Turn(map[t.spk] ?: 0, txt, (a + t.a) / 16000.0, (a + t.b) / 16000.0)
                        setP(id, base + span / 2 + span / 2 * (i + 1) / local.size, "辨識文字")
                        val now = System.currentTimeMillis(); if (now - lastWrite > 3000) { lastWrite = now; write(id, turns, false) }
                    }
                }
            } finally { sd.release(); ex.release() }
            write(id, turns, !stop())
        } finally { pcm.delete() }
    }
    private var lastWrite = 0L
    private fun setP(id: Long, p: Int, what: String) { val q = p.coerceIn(0, 99); if (q != progress[id]) { progress[id] = q; phase = what; show("$what… $q%", q) } }

    /** 寫回記事（只動逐字稿相關欄位）；final 時判斷標題、提醒時間、正式檔名 */
    private fun write(id: Long, segs: List<Triple<String, Double, Double>>, final: Boolean) = writeText(id, Transcript.join(segs), segs.isEmpty(), final)
    @JvmName("writeTurns") private fun write(id: Long, turns: List<Diar.Turn>, final: Boolean) = writeText(id, Transcript.speakers(turns), turns.isEmpty(), final)
    private fun writeText(id: Long, t0: String, empty: Boolean, final: Boolean) {
        val n = Store.get(this, id) ?: return
        var text = t0
        if (!final) text += if (stop()) "\n（轉文字已取消，以上為部分內容）" else "\n…"
        if (final && text.isNotEmpty()) {
            if (Names.isDefaultTitle(n.title)) Transcript.title(text)?.let { n.title = it }
            val times = TimeScan.all(text, ZonedDateTime.now(Names.zone))
            if (n.autoType && times.isNotEmpty()) {
                n.type = Note.REMIND; n.remindAt = times[0].first.toInstant().toEpochMilli()
                if (times.size > 1) text += "\n\n其他提到的時間：\n" + times.drop(1).joinToString("\n") { "• " + Names.date(it.first) + "　" + it.second }
            }
        }
        if (empty && final) text = "（沒有辨識到語音）"
        n.text = text; n.asr = if (final) 2 else 1
        if (final || stop()) { Store.save(this, n); Files.finalize(this, n); Reminders.sync(this, n) } else Store.save(this, n)
    }
}

object Files {
    /** 第一次確定內容後：改成正式檔名並複製到 音樂/錄音記事本 */
    fun finalize(ctx: Context, n: Note) {
        if (!n.hasAudio || n.exported.isNotEmpty()) return
        val f = Store.audio(ctx, n); if (!f.exists()) return
        val base = Names.base(Names.at(n.id), n.title); var dest = File(Store.dir(ctx), "$base.m4a"); var k = 2
        while (dest.exists() && dest != f) dest = File(Store.dir(ctx), "${base}_${k++}.m4a")
        if (dest != f && f.renameTo(dest)) n.file = dest.name
        n.exported = Share.export(ctx, Store.audio(ctx, n)).ifEmpty { "failed" }
        Store.save(ctx, n)
    }
}
