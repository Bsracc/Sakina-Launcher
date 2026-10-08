package app.sakinalauncher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class NotePanelStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILENAME, Context.MODE_PRIVATE)

    @Volatile
    private var notesCache: List<NoteMessage>? = null

    @Volatile
    private var todosCache: List<TodoItem>? = null

    fun getNotes(): List<NoteMessage> {
        notesCache?.let { return it }
        val decoded = NotePanelCodec.decodeNotes(prefs.getString(KEY_NOTES, null))
        notesCache = decoded
        return decoded
    }

    fun getTodos(): List<TodoItem> {
        todosCache?.let { return it }
        val decoded = NotePanelCodec.decodeTodos(prefs.getString(KEY_TODOS, null))
        todosCache = decoded
        return decoded
    }

    fun addNote(text: String, nowMillis: Long = System.currentTimeMillis()): NoteMessage {
        val note = NoteMessage(
            id = UUID.randomUUID().toString(),
            text = text.trim(),
            createdAtMillis = nowMillis,
        )
        saveNotes(getNotes() + note)
        return note
    }

    fun updateNote(id: String, text: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val notes = getNotes()
        var updated = false
        val nextNotes = notes.map { note ->
            if (note.id == id) {
                updated = true
                note.copy(text = text.trim(), updatedAtMillis = nowMillis)
            } else {
                note
            }
        }
        if (updated) saveNotes(nextNotes)
        return updated
    }

    fun deleteNote(id: String): Boolean {
        val notes = getNotes()
        val nextNotes = notes.filterNot { it.id == id }
        if (nextNotes.size == notes.size) return false
        saveNotes(nextNotes)
        return true
    }

    fun toggleNotePinned(id: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val notes = getNotes()
        var updated = false
        val nextNotes = notes.map { note ->
            if (note.id == id) {
                updated = true
                note.copy(isPinned = note.isPinned.not(), updatedAtMillis = nowMillis)
            } else {
                note
            }
        }
        if (updated) saveNotes(nextNotes)
        return updated
    }

    fun toggleNoteDone(id: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val notes = getNotes()
        var updated = false
        val nextNotes = notes.map { note ->
            if (note.id == id) {
                updated = true
                note.copy(isDone = note.isDone.not(), updatedAtMillis = nowMillis)
            } else {
                note
            }
        }
        if (updated) saveNotes(nextNotes)
        return updated
    }

    fun moveNoteToTodo(id: String, nowMillis: Long = System.currentTimeMillis()): MovedNote? {
        val notes = getNotes()
        val note = notes.firstOrNull { it.id == id } ?: return null
        val todo = TodoItem(
            id = UUID.randomUUID().toString(),
            text = note.text,
            createdAtMillis = nowMillis,
        )
        saveNotesAndTodos(
            notes = notes.filterNot { it.id == id },
            todos = getTodos() + todo,
        )
        return MovedNote(note, todo)
    }

    fun undoMoveToTodo(movedNote: MovedNote): Boolean {
        val todos = getTodos()
        if (todos.none { it.id == movedNote.todo.id }) return false
        saveNotesAndTodos(
            notes = (getNotes() + movedNote.note).distinctBy { it.id },
            todos = todos.filterNot { it.id == movedNote.todo.id },
        )
        return true
    }

    fun addTodo(text: String, nowMillis: Long = System.currentTimeMillis()): TodoItem {
        val todo = TodoItem(
            id = UUID.randomUUID().toString(),
            text = text.trim(),
            createdAtMillis = nowMillis,
        )
        saveTodos(getTodos() + todo)
        return todo
    }

    fun updateTodo(id: String, text: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val todos = getTodos()
        var updated = false
        val nextTodos = todos.map { todo ->
            if (todo.id == id) {
                updated = true
                todo.copy(text = text.trim(), updatedAtMillis = nowMillis)
            } else {
                todo
            }
        }
        if (updated) saveTodos(nextTodos)
        return updated
    }

    fun deleteTodo(id: String): Boolean {
        val todos = getTodos()
        val nextTodos = todos.filterNot { it.id == id }
        if (nextTodos.size == todos.size) return false
        saveTodos(nextTodos)
        return true
    }

    fun toggleTodo(id: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val todos = getTodos()
        var updated = false
        val nextTodos = todos.map { todo ->
            if (todo.id == id) {
                updated = true
                todo.copy(isDone = todo.isDone.not(), updatedAtMillis = nowMillis)
            } else {
                todo
            }
        }
        if (updated) saveTodos(nextTodos)
        return updated
    }

    fun deleteNotes(ids: Set<String>): Int {
        if (ids.isEmpty()) return 0
        val notes = getNotes()
        val nextNotes = notes.filterNot { it.id in ids }
        val removed = notes.size - nextNotes.size
        if (removed > 0) saveNotes(nextNotes)
        return removed
    }

    fun deleteTodos(ids: Set<String>): Int {
        if (ids.isEmpty()) return 0
        val todos = getTodos()
        val nextTodos = todos.filterNot { it.id in ids }
        val removed = todos.size - nextTodos.size
        if (removed > 0) saveTodos(nextTodos)
        return removed
    }

    fun toggleNotesDone(ids: Set<String>, nowMillis: Long = System.currentTimeMillis()): Int {
        if (ids.isEmpty()) return 0
        var updated = 0
        val nextNotes = getNotes().map { note ->
            if (note.id in ids) {
                updated += 1
                note.copy(isDone = note.isDone.not(), updatedAtMillis = nowMillis)
            } else {
                note
            }
        }
        if (updated > 0) saveNotes(nextNotes)
        return updated
    }

    fun toggleTodosDone(ids: Set<String>, nowMillis: Long = System.currentTimeMillis()): Int {
        if (ids.isEmpty()) return 0
        var updated = 0
        val nextTodos = getTodos().map { todo ->
            if (todo.id in ids) {
                updated += 1
                todo.copy(isDone = todo.isDone.not(), updatedAtMillis = nowMillis)
            } else {
                todo
            }
        }
        if (updated > 0) saveTodos(nextTodos)
        return updated
    }

    private fun saveNotes(notes: List<NoteMessage>) {
        notesCache = notes
        prefs.edit { putString(KEY_NOTES, NotePanelCodec.encodeNotes(notes)) }
    }

    private fun saveTodos(todos: List<TodoItem>) {
        todosCache = todos
        prefs.edit { putString(KEY_TODOS, NotePanelCodec.encodeTodos(todos)) }
    }

    private fun saveNotesAndTodos(notes: List<NoteMessage>, todos: List<TodoItem>) {
        notesCache = notes
        todosCache = todos
        prefs.edit {
            putString(KEY_NOTES, NotePanelCodec.encodeNotes(notes))
            putString(KEY_TODOS, NotePanelCodec.encodeTodos(todos))
        }
    }

    companion object {
        private const val PREFS_FILENAME = "app.sakinalauncher.note_panel"
        const val KEY_NOTES = "NOTE_PANEL_NOTES"
        const val KEY_TODOS = "NOTE_PANEL_TODOS"
    }
}

data class MovedNote(
    val note: NoteMessage,
    val todo: TodoItem,
)

object NotePanelCodec {
    fun encodeNotes(notes: List<NoteMessage>): String {
        val array = JSONArray()
        notes.sortedBy { it.createdAtMillis }.forEach { note ->
            array.put(
                JSONObject()
                    .put("id", note.id)
                    .put("text", note.text)
                    .put("createdAtMillis", note.createdAtMillis)
                    .put("updatedAtMillis", note.updatedAtMillis ?: JSONObject.NULL)
                    .put("isPinned", note.isPinned)
                    .put("isDone", note.isDone)
            )
        }
        return array.toString()
    }

    fun decodeNotes(payload: String?): List<NoteMessage> {
        return runCatching {
            val array = JSONArray(payload ?: "[]")
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val text = item.optString("text")
                    val createdAtMillis = item.optLong("createdAtMillis", 0L)
                    if (id.isBlank() || text.isBlank() || createdAtMillis <= 0L) continue
                    add(
                        NoteMessage(
                            id = id,
                            text = text,
                            createdAtMillis = createdAtMillis,
                            updatedAtMillis = item.optNullableLong("updatedAtMillis"),
                            isPinned = item.optBoolean("isPinned", false),
                            isDone = item.optBoolean("isDone", false),
                        )
                    )
                }
            }.sortedBy { it.createdAtMillis }
        }.getOrDefault(emptyList())
    }

    fun encodeTodos(todos: List<TodoItem>): String {
        val array = JSONArray()
        todos.sortedBy { it.createdAtMillis }.forEach { todo ->
            array.put(
                JSONObject()
                    .put("id", todo.id)
                    .put("text", todo.text)
                    .put("createdAtMillis", todo.createdAtMillis)
                    .put("updatedAtMillis", todo.updatedAtMillis ?: JSONObject.NULL)
                    .put("isDone", todo.isDone)
            )
        }
        return array.toString()
    }

    fun decodeTodos(payload: String?): List<TodoItem> {
        return runCatching {
            val array = JSONArray(payload ?: "[]")
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val text = item.optString("text")
                    val createdAtMillis = item.optLong("createdAtMillis", 0L)
                    if (id.isBlank() || text.isBlank() || createdAtMillis <= 0L) continue
                    add(
                        TodoItem(
                            id = id,
                            text = text,
                            createdAtMillis = createdAtMillis,
                            updatedAtMillis = item.optNullableLong("updatedAtMillis"),
                            isDone = item.optBoolean("isDone", false),
                        )
                    )
                }
            }.sortedBy { it.createdAtMillis }
        }.getOrDefault(emptyList())
    }

    private fun JSONObject.optNullableLong(name: String): Long? {
        return if (has(name) && !isNull(name)) optLong(name) else null
    }
}
