package tw.voicememo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File

class RecService : Service() {
    companion object {
        const val A_START = "start"; const val A_PAUSE = "pause"; const val A_RESUME = "resume"; const val A_STOP = "stop"
        const val CH = "rec"; const val PREF = "rec"
        @Volatile var state = 0 // 0 待機 1 錄音 2 暫停
        @Volatile var amp = 0
        var accMs = 0L; var segStart = 0L; var startWall = 0L; var error: String? = null
        fun elapsed() = accMs + if (state == 1) SystemClock.elapsedRealtime() - segStart else 0
        fun send(ctx: Context, a: String) {
            val i = Intent(ctx, RecService::class.java).setAction(a)
            if (a == A_START) ContextCompat.startForegroundService(ctx, i) else ctx.startService(i)
        }
        /** 停止後還沒寫成記事的錄音：(檔案, 長度, 開始時間) */
        fun pending(ctx: Context): Triple<File, Long, Long>? {
            val p = ctx.getSharedPreferences(PREF, MODE_PRIVATE); val path = p.getString("file", null) ?: return null
            val f = File(path); if (!f.exists()) { clearPending(ctx); return null }
            return Triple(f, p.getLong("dur", 0), p.getLong("start", 0))
        }
        fun clearPending(ctx: Context) = ctx.getSharedPreferences(PREF, MODE_PRIVATE).edit().clear().apply()
    }
    private var rec: MediaRecorder? = null; private var file: File? = null
    private val h = Handler(Looper.getMainLooper()); private var n = 0
    private val tick = object : Runnable { override fun run() {
        if (state == 1) amp = try { rec?.maxAmplitude ?: 0 } catch (e: Exception) { 0 }
        if (state != 0) { if (++n % 10 == 0) notifyNow(); h.postDelayed(this, 100) }
    } }

    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        when (i?.action) { A_START -> start(); A_PAUSE -> pause(); A_RESUME -> resume(); A_STOP -> stop() }
        return START_NOT_STICKY
    }
    private fun nm() = getSystemService(NotificationManager::class.java)
    private fun pi(a: String, code: Int) = PendingIntent.getService(this, code, Intent(this, RecService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
    private fun notif() = NotificationCompat.Builder(this, CH).setSmallIcon(R.drawable.ic_mic).setOngoing(true).setOnlyAlertOnce(true)
        .setContentTitle(if (state == 2) "已暫停 ${Names.dur(elapsed())}" else "錄音中 ${Names.dur(elapsed())}").setContentText("錄音記事本")
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, if (state == 2) "繼續" else "暫停", if (state == 2) pi(A_RESUME, 2) else pi(A_PAUSE, 1))
        .addAction(0, "停止", pi(A_STOP, 3)).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
    private fun notifyNow() { try { nm().notify(1, notif()) } catch (e: SecurityException) { } }

    private fun start() {
        if (state != 0) return
        nm().createNotificationChannel(NotificationChannel(CH, "錄音中", NotificationManager.IMPORTANCE_LOW))
        accMs = 0; segStart = SystemClock.elapsedRealtime(); startWall = System.currentTimeMillis(); error = null
        ServiceCompat.startForeground(this, 1, notif(), if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        val f = File(Store.dir(this), "rec_$startWall.m4a"); file = f
        try {
            @Suppress("DEPRECATION")
            rec = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC); setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC); setAudioSamplingRate(44100); setAudioEncodingBitRate(128000)
                setAudioChannels(1); setOutputFile(f.absolutePath); prepare(); start()
            }
            state = 1; segStart = SystemClock.elapsedRealtime(); h.post(tick)
        } catch (e: Exception) {
            error = "無法開始錄音：${e.message}"; rec?.release(); rec = null; f.delete(); state = 0
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }
    private fun pause() { if (state != 1) return; try { rec?.pause() } catch (e: Exception) { return }
        accMs += SystemClock.elapsedRealtime() - segStart; state = 2; amp = 0; notifyNow() }
    private fun resume() { if (state != 2) return; try { rec?.resume() } catch (e: Exception) { return }
        segStart = SystemClock.elapsedRealtime(); state = 1; notifyNow() }
    private fun stop() {
        if (state == 0) { stopSelf(); return }
        val dur = elapsed(); val f = file
        val ok = try { rec?.stop(); true } catch (e: Exception) { false }
        rec?.release(); rec = null; state = 0; amp = 0; h.removeCallbacks(tick)
        if (ok && f != null && f.length() > 0) getSharedPreferences(PREF, MODE_PRIVATE).edit()
            .putString("file", f.absolutePath).putLong("dur", dur).putLong("start", startWall).apply()
        else { f?.delete(); error = "錄音太短，未儲存" }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf()
        // 回到 App 時 MainActivity 會讀 pending 開啟編輯
    }
    override fun onDestroy() { if (state != 0) stop(); super.onDestroy() }
}
