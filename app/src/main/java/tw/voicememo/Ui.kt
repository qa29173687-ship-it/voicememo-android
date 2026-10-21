package tw.voicememo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object C {
    const val BG = 0xFF0F1012.toInt(); const val CARD = 0xFF1C1D21.toInt(); const val CARD2 = 0xFF26272C.toInt()
    const val OR = 0xFFFF8A1F.toInt(); const val TX = 0xFFF2F2F2.toInt(); const val SUB = 0xFF9A9CA3.toInt(); const val RED = 0xFFE5484D.toInt()
}
fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun round(color: Int, r: Float, stroke: Int = 0, sc: Int = 0) = GradientDrawable().apply { setColor(color); cornerRadius = r; if (stroke > 0) setStroke(stroke, sc) }
fun Context.tv(text: String, size: Float = 15f, color: Int = C.TX, bold: Boolean = false) = TextView(this).apply {
    this.text = text; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
}
fun Context.btn(text: String, bg: Int = C.CARD2, fg: Int = C.TX, onClick: (View) -> Unit) = tv(text, 15f, fg, true).apply {
    gravity = Gravity.CENTER; background = round(bg, dp(22).toFloat()); setPadding(dp(16), dp(10), dp(16), dp(10)); minHeight = dp(44)
    isClickable = true; setOnClickListener(onClick)
}
fun Context.field(hint: String, multi: Boolean = false) = EditText(this).apply {
    this.hint = hint; setHintTextColor(0xFF6B6D74.toInt()); setTextColor(C.TX); textSize = 16f
    background = round(C.CARD, dp(12).toFloat()); setPadding(dp(14), dp(12), dp(14), dp(12))
    if (multi) { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 4; gravity = Gravity.TOP } else isSingleLine = true
}
fun Context.label(t: String) = tv(t, 13f, C.SUB).apply { setPadding(dp(4), dp(12), 0, dp(6)) }
fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
const val MP = LinearLayout.LayoutParams.MATCH_PARENT
const val WC = LinearLayout.LayoutParams.WRAP_CONTENT

class LevelView(ctx: Context) : View(ctx) {
    private val hist = FloatArray(48); private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.OR }; private val r = RectF()
    fun push(v: Float) { System.arraycopy(hist, 1, hist, 0, hist.size - 1); hist[hist.size - 1] = v.coerceIn(0f, 1f); invalidate() }
    fun clear() { hist.fill(0f); invalidate() }
    override fun onDraw(c: Canvas) {
        val bw = width / hist.size.toFloat(); val mid = height / 2f
        hist.forEachIndexed { i, v ->
            val h = (height * 0.92f * v).coerceAtLeast(bw * 0.5f)
            p.alpha = (110 + 145 * i / hist.size); r.set(i * bw + bw * .2f, mid - h / 2, (i + 1) * bw - bw * .2f, mid + h / 2)
            c.drawRoundRect(r, bw / 2, bw / 2, p)
        }
    }
}

class RecButton(ctx: Context) : View(ctx) {
    var recording = false; set(v) { field = v; invalidate() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = C.TX }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.RED }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FF8A1F }
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val rad = minOf(cx, cy)
        c.drawCircle(cx, cy, rad, glow); ring.strokeWidth = rad * .07f; c.drawCircle(cx, cy, rad * .8f, ring)
        if (recording) { val s = rad * .32f; c.drawRoundRect(cx - s, cy - s, cx + s, cy + s, s * .3f, s * .3f, fill) }
        else c.drawCircle(cx, cy, rad * .64f, fill)
    }
}
