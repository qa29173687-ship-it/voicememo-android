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
        assertEquals("20261021_101402_A-1023", Names.base(t, "A-1023")); assertEquals("20261021_101402", Names.base(t, " "))
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
        assertNull(f("B棟5F浴室漏水，需要換止水閥"))
        assertNull(f("5F"))
    }
    @Test fun textAndMatch() {
        val n = Note(1792722842000, "漏水", caseNo = "A-1023", building = "B棟", text = "止水閥", tags = "水電", type = Note.REMIND, remindAt = 1792722842000)
        assertTrue(Names.noteText(n).contains("案號：A-1023")); assertTrue(Names.match(n, "水電")); assertTrue(Names.match(n, "a-1023"))
    }
}
