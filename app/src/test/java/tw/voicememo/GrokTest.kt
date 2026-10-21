package tw.voicememo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class) @Config(sdk = [33])
class GrokTest {
    private val tpe = ZoneId.of("Asia/Taipei")
    private val now = ZonedDateTime.of(2026, 10, 21, 13, 40, 0, 0, tpe) // 週三
    private fun ms(d: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, d, h, m, 0, 0, tpe).toInstant().toEpochMilli()
    private fun p(s: String) = Grok.parseReply(s, now)

    @Test fun fencedJson() {
        val r = p("""好的，以下是整理結果：
```json
{"todos":[{"內容":"寄包裹到郵局","負責人":"小明","時間":"2026-10-22T15:00:00+08:00","優先度":"高"},
{"內容":"準備讀書會報告","負責人":null,"時間":null,"優先度":"中"}],"摘要":["討論第三章","下次由說話者 2 分享"]}
```
希望有幫助！""")
        assertEquals(2, r.todos.size); assertEquals("小明", r.todos[0].owner); assertEquals(ms(22, 15), r.todos[0].due); assertEquals("高", r.todos[0].priority)
        assertNull(r.todos[1].due); assertNull(r.todos[1].owner); assertEquals(listOf("討論第三章", "下次由說話者 2 分享"), r.summary)
    }
    @Test fun bareJsonTrailingCommaFullWidth() {
        val r = p("""{“todos”：［{“內容”：“買牛奶”，“負責人”：null，“時間”：“2026-10-21T18:00”，“優先度”：“低”，}，］，“摘要”：［“家裡用品”］}""")
        assertEquals(1, r.todos.size); assertEquals("買牛奶", r.todos[0].text); assertEquals(ms(21, 18), r.todos[0].due); assertEquals("低", r.todos[0].priority)
        assertEquals(listOf("家裡用品"), r.summary)
    }
    @Test fun altKeysAndArrays() {
        val r = p("""```
{"待辦事項":[{"content":"回電給房東","owner":"我","due":"2026-10-23","priority":"high"},"明天上午十點繳電費"],"summary":"重點一\n重點二"}
```""")
        assertEquals(2, r.todos.size); assertEquals(ms(23, 9), r.todos[0].due); assertEquals("高", r.todos[0].priority); assertEquals("我", r.todos[0].owner)
        assertEquals(ms(22, 10), r.todos[1].due); assertEquals(listOf("重點一", "重點二"), r.summary)
        val arr = p("""[{"內容":"A 事","時間":"2026-10-24 14:00"},{"內容":"B 事","時間":"無","優先度":"低"}]""")
        assertEquals(ms(24, 14), arr.todos[0].due); assertNull(arr.todos[1].due)
    }
    @Test fun naturalLanguageTimeInJson() {
        val r = p("""```json
{"todos":[{"內容":"看牙醫","負責人":"無","時間":"明天下午三點","優先度":"中"}],"摘要":[]}
```""")
        assertEquals(ms(22, 15), r.todos[0].due); assertNull(r.todos[0].owner)
    }
    @Test fun bulletFallback() {
        val r = p("""**待辦事項**
1. 寄包裹到郵局（負責人：小明）明天下午三點 【高】
2、整理第三章重點，優先度：低
- 下週一上午 10:30 開會
• 買信封

**重點摘要**
- 討論第三章
- 環境設計最有用""")
        assertEquals(4, r.todos.size)
        assertEquals("小明", r.todos[0].owner); assertEquals("高", r.todos[0].priority); assertEquals(ms(22, 15), r.todos[0].due)
        assertEquals("低", r.todos[1].priority); assertNull(r.todos[1].due)
        assertEquals(ms(26, 10, 30), r.todos[2].due); assertEquals("買信封", r.todos[3].text)
        assertEquals(listOf("討論第三章", "環境設計最有用"), r.summary)
    }
    @Test fun brokenJsonFallsBackToLines() {
        val r = p("""```json
{"todos":[{"內容":"壞掉的
```
- 明天 9:00 打給客服""")
        assertEquals(1, r.todos.size); assertEquals(ms(22, 9), r.todos[0].due)
    }
    @Test fun emptyAndNoise() {
        assertTrue(p("抱歉，我無法判斷").todos.isEmpty()); assertTrue(p("").todos.isEmpty())
        val r = p("""```json
{"todos":[],"摘要":["沒有待辦，只是聊天"]}
```"""); assertTrue(r.todos.isEmpty()); assertEquals(1, r.summary.size)
    }
    @Test fun dedupe() {
        val r = p("""{"todos":[{"內容":"寄包裹。","時間":null},{"內容":"寄包裹","負責人":"小明","時間":"2026-10-22T15:00:00+08:00"}]}""")
        assertEquals(1, r.todos.size); assertEquals("小明", r.todos[0].owner); assertEquals(ms(22, 15), r.todos[0].due)
    }
    @Test fun utcOffset() { assertEquals(ms(23, 18, 30), Grok.time("2026-10-23T10:30:00Z", now)) }
    @Test fun promptAndRoundTrip() {
        val n = Note(ms(21, 9), "讀書會", text = "小美：週五晚上前我整理重點\n說話者 2：好")
        val pr = Grok.prompt(n, now)
        assertTrue(pr.contains("2026-10-21 13:40（星期三")); assertTrue(pr.contains("小美：週五晚上前")); assertTrue(pr.contains("```json"))
        val r = TodoResult(listOf(Todo("A", "小美", ms(23, 20), "高")), listOf("S"))
        assertEquals(r.copy(todos = r.todos), p(Grok.toJson(r)))
    }
}
