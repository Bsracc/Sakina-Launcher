package app.sakinalauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBackupCodecTest {

    @Test
    fun roundTripPreservesAllPrefValueTypes() {
        val entries = linkedMapOf(
            "STRING_KEY" to "hello",
            "INT_KEY" to 42,
            "LONG_KEY" to 9_007_199_254_740_992L,
            "FLOAT_KEY" to 0.90f,
            "BOOL_KEY" to true,
            "SET_KEY" to setOf("a", "b", "c"),
        )
        val notes = listOf(NoteMessage(id = "n1", text = "note", createdAtMillis = 1000L))
        val todos = listOf(TodoItem(id = "t1", text = "todo", createdAtMillis = 2000L))

        val payload = ConfigBackupCodec.decode(ConfigBackupCodec.encode(entries, notes, todos))

        assertNotNull(payload)
        payload!!.let {
            assertEquals(ConfigBackupCodec.FORMAT_VERSION, it.version)
            assertEquals("hello", it.prefsEntries["STRING_KEY"])
            assertEquals(42, it.prefsEntries["INT_KEY"])
            assertEquals(9_007_199_254_740_992L, it.prefsEntries["LONG_KEY"])
            // org.json stores numbers as doubles; the Float comes back as a Double.
            assertEquals(0.90f, (it.prefsEntries["FLOAT_KEY"] as Double).toFloat())
            assertEquals(true, it.prefsEntries["BOOL_KEY"])
            assertEquals(setOf("a", "b", "c"), it.prefsEntries["SET_KEY"])
            assertEquals(listOf("n1"), it.notes.map { n -> n.id })
            assertEquals("note", it.notes.first().text)
            assertEquals(listOf("t1"), it.todos.map { t -> t.id })
        }
    }

    @Test
    fun decodeSkipsUnknownValueTypes() {
        val root = org.json.JSONObject()
        root.put("format", ConfigBackupCodec.FORMAT_VERSION)
        root.put(
            "prefs",
            org.json.JSONObject().put("A", 1).put("B", "x").put("C", org.json.JSONObject.NULL)
        )

        val payload = ConfigBackupCodec.decode(root.toString())

        assertNotNull(payload)
        assertEquals(setOf("A", "B"), payload!!.prefsEntries.keys)
    }

    @Test
    fun corruptPayloadReturnsNull() {
        assertNull(ConfigBackupCodec.decode("not-json"))
        assertNull(ConfigBackupCodec.decode("{\"format\":1}"))
    }

    @Test
    fun transientKeysListCoversOneShotAndTimerState() {
        // Filtering happens in ConfigBackup.exportJson; this pins the set's contents so
        // a rename of a key in Prefs doesn't silently start exporting onboarding flags.
        val transient = ConfigBackup.TRANSIENT_KEYS
        assertTrue("FIRST_OPEN" in transient)
        assertTrue("FIRST_OPEN_TIME" in transient)
        assertTrue("POMODORO_TIMER_REMAINING_MILLIS" in transient)
        assertTrue("HIDDEN_APPS_UPDATED" in transient)
        // Real settings must never be filtered out.
        assertTrue("APP_THEME" !in transient)
        assertTrue("HIDDEN_APPS" !in transient)
    }
}
