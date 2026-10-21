package tw.voicememo

import org.robolectric.RuntimeEnvironment
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [33])
class StoreTest {
    @Test fun roundTrip() {
        val c = RuntimeEnvironment.getApplication() as Context
        Store.save(c, Note(1, "a", type = Note.MEMO, pinned = true)); Store.save(c, Note(2, "b", type = Note.REMIND, remindAt = 99, tags = "x"))
        val l = Store.all(c); assertEquals(listOf(2L, 1L), l.map { it.id }); assertEquals(99L, l[0].remindAt); assertEquals(true, l[1].pinned)
        Store.delete(c, l[1]); assertEquals(1, Store.all(c).size)
    }
}
