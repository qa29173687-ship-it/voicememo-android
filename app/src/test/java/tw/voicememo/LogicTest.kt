package tw.voicememo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class LogicTest {
    private val tz = ZoneId.of("Asia/Taipei")
    private val now = ZonedDateTime.of(2026, 10, 21, 12, 30, 0, 0, tz) // 週三
    private fun f(s: String) = TimeParse.find(s, now)?.toLocalDateTime()?.toString()
    @Test fun names() {
        val t = ZonedDateTime.of(2026, 10, 21, 10, 14, 2, 0, tz)
        assertEquals("20261021_101402_明天下午去郵局寄包裹", Names.base(t, "明天下午去郵局寄包裹給媽媽")); assertEquals("20261021_101402", Names.base(t, " "))
        assertEquals("20261021_101402", Names.base(t, "2026/10/21 10:14 錄音"))
        assertEquals("A-1-2-3", Names.safe("A/1 2:3")); assertEquals("2026/10/21 10:14 錄音", Names.defaultTitle(t))
        assertEquals("01:05", Names.dur(65_000)); assertEquals("1:00:05", Names.dur(3_605_000))
    }
    @Test fun times() {
        assertEquals("2026-10-22T15:00", f("明天下午三點去B棟看漏水"))
        assertEquals("2026-10-22T09:30", f("明天9:30"))
        assertEquals("2026-10-21T20:30", f("晚上八點半回電"))
        assertEquals("2026-10-23T09:00", f("後天要叫料"))
        assertEquals("2026-10-28T10:15", f("下週三上午十點十五分會勘"))
        assertEquals("2026-10-24T14:00", f("10月24日 14:00 驗收"))
        assertEquals("2026-10-22T10:00", f("10點換燈管")) // 今天已過→明天
        assertNull(f("今天心情不錯，去公園散步"))
        assertNull(f("五個蘋果"))
    }
    @Test fun textAndMatch() {
        val n = Note(1792722842000, "買菜", text = "記得買牛奶", tags = "家裡", type = Note.REMIND, remindAt = 1792722842000)
        assertTrue(Names.noteText(n).contains("提醒時間")); assertTrue(Names.match(n, "家裡")); assertTrue(Names.match(n, "牛奶"))
    }
}

class S2TTest {
    private val c = S2T(java.io.File("src/main/assets/s2t.txt").inputStream(), java.io.File("src/main/assets/tw.txt").inputStream())
    private val now = ZonedDateTime.of(2026, 10, 21, 12, 30, 0, 0, ZoneId.of("Asia/Taipei"))
    @Test fun convert() {
        assertEquals("明天下午三點記得去郵局寄包裹。", c.convert("明天下午三点记得去邮局寄包裹。"))
        assertEquals("這個的頭髮乾淨，裡面", c.convert("这个的头发干净，里面"))
        assertEquals("我們後天開會討論計劃", c.convert("我们后天开会讨论计划"))
        assertEquals("下週三上午十點十五分", c.convert("下周三上午十点十五分"))
    }
    @Test fun transcriptPipeline() {
        val segs = listOf(Triple(c.convert("嗯 提醒我明天下午三点"), 0.0, 2.0), Triple(c.convert("去邮局寄包裹"), 2.3, 4.0),
            Triple(c.convert("还有十月二十四日下午两点要看牙医。"), 5.2, 8.0), Triple(c.convert("另外买牛奶"), 11.5, 13.0))
        val t = Transcript.join(segs)
        assertEquals("嗯提醒我明天下午三點，去郵局寄包裹。還有十月二十四日下午兩點要看牙醫。\n另外買牛奶。", t)
        assertEquals("提醒我明天下午三點", Transcript.title(t))
        val times = TimeScan.all(t, now).map { it.first.toLocalDateTime().toString() }
        assertEquals(listOf("2026-10-22T15:00", "2026-10-24T14:00"), times)
    }
    @Test fun resampler() {
        val r = Decoder.Resampler(48000); var n = 0; repeat(10) { n += r.process(FloatArray(4800) { 0.5f }).size }
        assertTrue(n in 15990..16010)
    }
}

class DiarTest {
    private fun t(s: Int, x: String, a: Double) = Diar.Turn(s, x, a, a + 2)
    @Test fun labelsAndMerge() {
        val out = Transcript.speakers(listOf(t(3, "明天下午三點開會", 0.0), t(3, "記得帶筆電", 2.5), t(0, "好，我會準備報告。", 5.0), t(3, "謝謝", 8.0)))
        assertEquals("說話者 1：明天下午三點開會，記得帶筆電。\n說話者 2：好，我會準備報告。\n說話者 1：謝謝。", out)
        assertEquals(listOf("說話者 1", "說話者 2"), Transcript.labels(out))
        val r = Transcript.rename(out, "說話者 1", "小明")
        assertEquals("小明：明天下午三點開會，記得帶筆電。\n說話者 2：好，我會準備報告。\n小明：謝謝。", r)
        assertEquals("明天下午三點開會", Transcript.title(out))
    }
    @Test fun singleSpeakerNoLabels() {
        val out = Transcript.speakers(listOf(t(1, "第一句", 0.0), t(1, "第二句", 5.0)))
        assertEquals("第一句。\n第二句。", out); assertTrue(Transcript.labels(out).isEmpty())
    }
    @Test fun mergeSegs() {
        val m = Diar.merge(listOf(Diar.Seg(0, 16000, 0), Diar.Seg(20000, 40000, 0), Diar.Seg(80000, 90000, 0), Diar.Seg(90000, 99000, 1)))
        assertEquals(listOf(Diar.Seg(0, 40000, 0), Diar.Seg(80000, 90000, 0), Diar.Seg(90000, 99000, 1)), m)
    }
    @Test fun linker() {
        val l = Diar.Linker(0)
        assertEquals(0, l.assign(floatArrayOf(1f, 0f, 0f))); assertEquals(1, l.assign(floatArrayOf(0f, 1f, 0f)))
        assertEquals(0, l.assign(floatArrayOf(.9f, .1f, 0f))); assertEquals(2, l.assign(floatArrayOf(0f, 0f, 1f)))
        val c = Diar.Linker(2); c.assign(floatArrayOf(1f, 0f)); c.assign(floatArrayOf(0f, 1f)); assertEquals(1, c.assign(floatArrayOf(-.2f, 1f)))
    }
}
