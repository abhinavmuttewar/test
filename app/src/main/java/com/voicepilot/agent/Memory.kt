package com.voicepilot.agent

/**
 * What the assistant has learned from practice and from the user's instructions.
 * Notes: preferences, corrections, recipes for tasks that needed trial and error.
 * Shortcuts: spoken trigger phrase -> instruction to run.
 */
interface Memory {
    fun notes(): List<String>
    fun addNote(note: String)
    fun removeNote(index: Int): Boolean
    fun shortcuts(): Map<String, String>
    fun addShortcut(phrase: String, instruction: String)
    fun removeShortcut(phrase: String): Boolean
    /** Records a failure so the user can export it for fixing the app's code. */
    fun logError(entry: String)
}

/** Simple in-memory version, used by tests. */
open class InMemoryMemory : Memory {
    private val notes = mutableListOf<String>()
    private val shortcuts = linkedMapOf<String, String>()
    val errors = mutableListOf<String>()

    override fun notes() = notes.toList()
    override fun addNote(note: String) {
        val n = note.trim()
        if (n.isNotEmpty() && n !in notes) notes += n
        while (notes.size > MAX_NOTES) notes.removeAt(0)
    }
    override fun removeNote(index: Int) = if (index in notes.indices) { notes.removeAt(index); true } else false
    override fun shortcuts() = shortcuts.toMap()
    override fun addShortcut(phrase: String, instruction: String) { shortcuts[normalizePhrase(phrase)] = instruction.trim() }
    override fun removeShortcut(phrase: String) = shortcuts.remove(normalizePhrase(phrase)) != null
    override fun logError(entry: String) { errors += entry; while (errors.size > MAX_ERRORS) errors.removeAt(0) }

    companion object {
        const val MAX_NOTES = 60
        const val MAX_ERRORS = 100
    }
}

fun normalizePhrase(s: String) =
    s.lowercase().trim().trimEnd('.', '!', '?', '।').replace(Regex("\\s+"), " ")
