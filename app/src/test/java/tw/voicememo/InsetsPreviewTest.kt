package tw.voicememo

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** 模擬 24dp 狀態列 + 48dp 三鍵導覽列，確認內容不被遮住 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class InsetsPreviewTest {
    private val out = File("../previews").apply { mkdirs() }
    private val W = 800; private val H = 1720; private val SB = 48; private val NB = 96
    private fun shot(a: Activity, name: String): Pair<View, Bitmap> {
        val v: View = a.window.decorView
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY)); v.layout(0, 0, W, H)
        ViewCompat.dispatchApplyWindowInsets(v, WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, SB, 0, NB)).build())
        ShadowLooper.idleMainLooper()
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY)); v.layout(0, 0, W, H)
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val c = Canvas(b); v.draw(c)
        val p = Paint().apply { color = 0x99FF00FF.toInt() }; c.drawRect(0f, 0f, W.toFloat(), SB.toFloat(), p); c.drawRect(0f, (H - NB).toFloat(), W.toFloat(), H.toFloat(), p)
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26f }
        c.drawText("模擬狀態列 24dp", 16f, 36f, t); c.drawText("◁   ○   □   模擬三鍵導覽列 48dp", 16f, H - NB / 2f + 10, t)
        File(out, "insets-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return v to b
    }
    @Test fun render() {
        val m = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shot(m, "1-主畫面")
        val content = m.findViewById<View>(android.R.id.content) as android.view.ViewGroup
        val root = content.getChildAt(0) as android.view.ViewGroup
        assertTrue("頂部需避開狀態列", root.paddingTop >= SB)
        val tabs = root.getChildAt(root.childCount - 1)
        assertTrue("分頁列需避開導覽列", tabs.paddingBottom >= NB)
        val c = RuntimeEnvironment.getApplication()
        val e = Robolectric.buildActivity(NoteActivity::class.java, Intent(c, NoteActivity::class.java).putExtra("textOnly", true)).setup().get()
        shot(e, "2-編輯")
    }
}
