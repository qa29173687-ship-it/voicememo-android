package tw.voicememo

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w400dp-h860dp-xhdpi")
class PreviewTest {
    private val out = File(System.getProperty("previewDir") ?: "build/previews").apply { mkdirs() }
    private fun ms(d: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 10, d, h, m, 0, 0, ZoneId.of("Asia/Taipei")).toInstant().toEpochMilli()
    private fun shot(a: Activity, name: String) {
        ShadowLooper.idleMainLooper()
        val v: View = a.window.decorView; val w = 800; val h = 1720
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); v.layout(0, 0, w, h)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun seed() {
        Names.zone = ZoneId.of("Asia/Taipei"); val c = RuntimeEnvironment.getApplication()
        Store.save(c, Note(ms(21, 9, 12), "明天下午三點去郵局寄包裹", "提醒我明天下午三點，去郵局寄包裹。\n順便買信封。", asr = 2, file = "x.m4a", durationMs = 95_000, type = Note.REMIND, remindAt = ms(22, 15, 0)))
        Store.save(c, Note(ms(21, 10, 40), "買牛奶、雞蛋、衛生紙", type = Note.MEMO, pinned = true))
        Store.save(c, Note(ms(20, 16, 5), "繳電話費", "已用網銀繳", file = "y.m4a", durationMs = 42_000, type = Note.MEMO, done = true))
        Store.save(c, Note(ms(20, 14, 0), "讀書會：原子習慣", "小美：今天討論第三章，重點是讓好習慣變得顯而易見。\n說話者 2：我覺得環境設計那段最有用。\n小美：好，下次輪到你分享。", asr = 2, file = "z.m4a", durationMs = 612_000, type = Note.NOTE, tags = "讀書 習慣"))
    }
    @Test fun render() {
        seed()
        val m = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        RecService.state = 1; RecService.accMs = 83_000; RecService.segStart = android.os.SystemClock.elapsedRealtime()
        repeat(48) { RecService.amp = (8000 + 20000 * Math.abs(Math.sin(it / 3.0))).toInt(); m.render() }
        shot(m, "1-錄音中"); RecService.state = 0; RecService.accMs = 0
        m.show(true); shot(m, "2-列表-全部")
        m.filter = Note.REMIND; m.reload(); shot(m, "3-列表-提醒")
        val c = RuntimeEnvironment.getApplication()
        val e = Robolectric.buildActivity(NoteActivity::class.java, Intent(c, NoteActivity::class.java).putExtra("id", ms(21, 9, 12))).setup().get()
        shot(e, "4-編輯-提醒")
        val n = Robolectric.buildActivity(NoteActivity::class.java, Intent(c, NoteActivity::class.java).putExtra("id", ms(20, 14, 0))).setup().get()
        shot(n, "5-編輯-筆記-多人")
    }
}
