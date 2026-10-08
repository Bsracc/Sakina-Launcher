package app.sakinalauncher.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Export/import of the launcher's configuration as a single JSON file.
 *
 * The export contains every key of the main Prefs file (see [Prefs]) except the
 * obviously transient ones (one-shot onboarding flags, timestamps, live pomodoro
 * state, migration flags), plus the Productive notes and todos stored by
 * [NotePanelStore]. Reading and writing the note-panel file directly keeps the
 * round-trip byte-faithful and independent of [NotePanelStore]'s in-memory caches,
 * which are per-fragment instances anyway.
 *
 * Serialization is a pure, JVM-testable codec: [ConfigBackupCodec]. This class only
 * wires the codec to SharedPreferences.
 */
class ConfigBackup(context: Context) {

    private val mainPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILENAME_MAIN, Context.MODE_PRIVATE)
    private val notePrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILENAME_NOTE_PANEL, Context.MODE_PRIVATE)

    /** Serialize the current configuration to a JSON string ready to write to a file. */
    fun exportJson(): String {
        val entries = LinkedHashMap<String, Any>()
        mainPrefs.all.forEach { (key, value) ->
            if (key !in TRANSIENT_KEYS && value != null) entries[key] = value
        }
        return ConfigBackupCodec.encode(
            prefsEntries = entries,
            notes = NotePanelCodec.decodeNotes(notePrefs.getString(NotePanelStore.KEY_NOTES, null)),
            todos = NotePanelCodec.decodeTodos(notePrefs.getString(NotePanelStore.KEY_TODOS, null)),
        )
    }

    /**
     * Apply a previously exported JSON configuration. Returns false when the payload
     * is unreadable. On success the main Prefs file is rewritten from the payload
     * (transient keys were already dropped at export time) and the note-panel file is
     * replaced with the backed-up notes and todos.
     */
    fun importJson(json: String): Boolean {
        val payload = ConfigBackupCodec.decode(json) ?: return false
        val editor = mainPrefs.edit()
        payload.prefsEntries.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> editor.putStringSet(key, value.map { it.toString() }.toSet())
                else -> Unit
            }
        }
        editor.apply()
        notePrefs.edit()
            .putString(NotePanelStore.KEY_NOTES, NotePanelCodec.encodeNotes(payload.notes))
            .putString(NotePanelStore.KEY_TODOS, NotePanelCodec.encodeTodos(payload.todos))
            .apply()
        return true
    }

    companion object {
        private const val PREFS_FILENAME_MAIN = "app.sakinalauncher"
        private const val PREFS_FILENAME_NOTE_PANEL = "app.sakinalauncher.note_panel"

        /**
         * Keys never exported: one-shot onboarding flags, absolute timestamps that are
         * meaningless on a fresh device, per-day hint counters, and the live pomodoro
         * timer state (end-of-timer and remaining millis are wall-clock sensitive).
         * Rename labels (arbitrary package keys) and real settings are kept.
         */
        val TRANSIENT_KEYS: Set<String> = setOf(
            "FIRST_OPEN",
            "FIRST_OPEN_TIME",
            "FIRST_SETTINGS_OPEN",
            "FIRST_HIDE",
            "KEYBOARD_MESSAGE",
            "SHOW_HINT_COUNTER",
            "ABOUT_CLICKED",
            "RATE_CLICKED",
            "WALLPAPER_MSG_SHOWN",
            "SHARE_SHOWN_TIME",
            "PRO_MESSAGE_SHOWN",
            "HIDE_SET_DEFAULT_LAUNCHER",
            "SCREEN_TIME_LAST_UPDATED",
            "LAUNCHER_RECREATE_TIMESTAMP",
            "SHOWN_ON_DAY_OF_YEAR",
            "POMODORO_TIMER_TOTAL_MILLIS",
            "POMODORO_TIMER_END_ELAPSED_REALTIME",
            "POMODORO_TIMER_REMAINING_MILLIS",
            "HIDDEN_APPS_UPDATED",
        )
    }
}

/** Decoded backup payload. [prefsEntries] preserves SharedPreferences value types. */
data class ConfigBackupPayload(
    val version: Int,
    val prefsEntries: Map<String, Any>,
    val notes: List<NoteMessage>,
    val todos: List<TodoItem>,
)

/**
 * Pure JSON codec for [ConfigBackup]. No Android dependencies so it runs under JUnit.
 *
 * Type round-trip contract (mirrors SharedPreferences' five types):
 * - String  -> JSON string  -> String
 * - Int     -> JSON number  -> Int
 * - Long    -> JSON number  -> Long
 * - Float   -> JSON number (stored as double, like org.json does) -> Double -> Float
 * - Boolean -> JSON boolean -> Boolean
 * - Set     -> JSON array of strings -> Set
 */
object ConfigBackupCodec {

    const val FORMAT_VERSION = 1

    fun encode(
        prefsEntries: Map<String, Any>,
        notes: List<NoteMessage>,
        todos: List<TodoItem>,
    ): String {
        val root = JSONObject()
        root.put("format", FORMAT_VERSION)
        root.put(
            "prefs",
            JSONObject().apply {
                prefsEntries.forEach { (key, value) ->
                    when (value) {
                        is String -> put(key, value)
                        is Int -> put(key, value)
                        is Long -> put(key, value)
                        is Float -> put(key, value.toDouble())
                        is Boolean -> put(key, value)
                        is Set<*> -> put(key, JSONArray(value))
                        else -> Unit
                    }
                }
            }
        )
        // Serialize the codec's arrays through their own JSONArray.toString() so a
        // backup file has a single canonical representation regardless of which
        // org.json implementation (Android or the JVM artifact) produced it.
        root.put("notes", NotePanelCodec.encodeNotes(notes))
        root.put("todos", NotePanelCodec.encodeTodos(todos))
        return root.toString()
    }

    fun decode(json: String): ConfigBackupPayload? {
        return runCatching {
            val root = JSONObject(json)
            val version = root.optInt("format", FORMAT_VERSION)
            // A backup must carry a "prefs" object; a random JSON file (or a stray
            // {"format":1}) is not a valid backup and must not be imported as one.
            val prefsJson = root.optJSONObject("prefs") ?: return null
            val entries = LinkedHashMap<String, Any>()
            for (key in prefsJson.keys()) {
                val value = prefsJson.get(key)
                when (value) {
                    is JSONArray -> entries[key] = (0 until value.length()).map { value.getString(it) }.toSet()
                    // org.json (JVM artifact) returns BigDecimal for decimals; Android
                    // returns Double. Normalize both to Double so import writes a float
                    // and the unit test can assert the round-trip type.
                    is java.math.BigDecimal -> entries[key] = value.toDouble()
                    is Int -> entries[key] = value
                    is Long -> entries[key] = value
                    is Double -> entries[key] = value
                    is Float -> entries[key] = value.toDouble()
                    is Number -> entries[key] = value.toLong() // Short/Byte fallback
                    is String, is Boolean -> entries[key] = value
                    else -> Unit
                }
            }
            ConfigBackupPayload(
                version = version,
                prefsEntries = entries,
                // Accept both the v1 encoding (notes/todos stored as a raw JSON array
                // string via put("notes", NotePanelCodec.encodeNotes(...))) and any
                // hand-edited file that stores them as a proper JSONArray.
                notes = NotePanelCodec.decodeNotes(
                    root.optJSONArray("notes")?.toString() ?: root.optString("notes")
                ),
                todos = NotePanelCodec.decodeTodos(
                    root.optJSONArray("todos")?.toString() ?: root.optString("todos")
                ),
            )
        }.getOrNull()
    }
}
