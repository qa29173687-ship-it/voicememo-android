package tw.voicememo

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object Reminders {
    private fun pi(ctx: Context, id: Long, flag: Int) = PendingIntent.getBroadcast(ctx, (id % Int.MAX_VALUE).toInt(),
        Intent(ctx, AlarmReceiver::class.java).putExtra("id", id), flag or PendingIntent.FLAG_IMMUTABLE)
    /** 依記事狀態排程或取消；回傳 true 表示精準鬧鐘 */
    fun sync(ctx: Context, n: Note): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        pi(ctx, n.id, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
        if (n.type != Note.REMIND || n.remindAt <= System.currentTimeMillis() || n.done) return true
        val p = pi(ctx, n.id, PendingIntent.FLAG_UPDATE_CURRENT)
        return if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, n.remindAt, p); true
        } else { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, n.remindAt, p); false }
    }
    fun cancel(ctx: Context, n: Note) = sync(ctx, n.copy(type = Note.MEMO))
    fun resyncAll(ctx: Context) = Store.all(ctx).forEach { sync(ctx, it) }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val n = Store.get(ctx, i.getLongExtra("id", 0)) ?: return
        if (n.type != Note.REMIND) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("remind", "提醒", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, (n.id % Int.MAX_VALUE).toInt(),
            Intent(ctx, NoteActivity::class.java).putExtra("id", n.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val body = n.text.take(80)
        try { nm.notify((n.id % Int.MAX_VALUE).toInt(), NotificationCompat.Builder(ctx, "remind").setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("⏰ ${n.title}").setContentText(body).setAutoCancel(true).setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER).build()) } catch (e: SecurityException) { }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) Reminders.resyncAll(ctx)
    }
}
