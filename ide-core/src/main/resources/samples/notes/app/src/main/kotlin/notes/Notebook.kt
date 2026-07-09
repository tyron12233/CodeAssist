package notes

/** A single note. [done] marks a to-do note as complete. */
data class Note(val id: Int, val text: String, val done: Boolean = false)

/**
 * An in-memory notebook: add, list, search, complete, and remove notes. This is the app's **model** — the
 * pure data + logic, with no printing. A real app would persist these notes (to a file or a database)
 * instead of holding them in a list, but the rest of the app wouldn't have to change.
 *
 * Keeping the model free of I/O like this makes it easy to test and to reuse behind a different UI.
 */
class Notebook {
    private val notes = mutableListOf<Note>()
    private var nextId = 1

    /** Add a note with the given [text] and return the created [Note]. */
    fun add(text: String): Note {
        val note = Note(nextId++, text)
        notes.add(note)
        return note
    }

    /** Every note, in the order it was added. */
    fun all(): List<Note> = notes.toList()

    /** Notes whose text contains [query], ignoring case. */
    fun search(query: String): List<Note> = notes.filter { it.text.contains(query, ignoreCase = true) }

    /** Mark the note with [id] complete. Returns true if such a note existed. */
    fun complete(id: Int): Boolean {
        val index = notes.indexOfFirst { it.id == id }
        if (index < 0) return false
        notes[index] = notes[index].copy(done = true)
        return true
    }

    /** Remove the note with [id]. Returns true if such a note existed. */
    fun remove(id: Int): Boolean = notes.removeAll { it.id == id }
}
