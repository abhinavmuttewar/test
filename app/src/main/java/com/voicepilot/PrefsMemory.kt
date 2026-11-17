package com.voicepilot

import android.content.Context
import com.voicepilot.agent.InMemoryMemory
import com.voicepilot.agent.Memory
import com.voicepilot.agent.normalizePhrase
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Learned notes, shortcuts and error log, saved on the phone. */
class PrefsMemory private constructor(context: Context) : Memory {
    private val sp = context.getSharedPreferences("memory", Context.MODE_PRIVATE)

    @Synchronized override fun notes(): List<String> = list("notes")
    @Synchronized override fun addNote(note: String) {
        val n = note.trim()
        val all = list("notes").filter { it != n }.toMutableList()
        if (n.isNotEmpty()) all += n
        save("notes", all.takeLast(InMemoryMemory.MAX_NOTES))
    }
    @Synchronized override fun removeNote(index: Int): Boolean {
        val all = list("notes").toMutableList()
        if (index !in all.indices) return false
        all.removeAt(index); save("notes", all); return true
    }

    @Synchronized override fun shortcuts(): Map<String, String> {
        val o = JSONObject(sp.getString("shortcuts", "{}") ?: "{}")
        return o.keys().asSequence().associateWith { o.getString(it) }
    }
    @Synchronized override fun addShortcut(phrase: String, instruction: String) {
        val o = JSONObject(sp.getString("shortcuts", "{}") ?: "{}")
        o.put(normalizePhrase(phrase), instruction.trim())
        sp.edit().putString("shortcuts", o.toString()).apply()
    }
    @Synchronized override fun removeShortcut(phrase: String): Boolean {
        val o = JSONObject(sp.getString("shortcuts", "{}") ?: "{}")
        val removed = o.remove(normalizePhrase(phrase)) != null
        sp.edit().putString("shortcuts", o.toString()).apply()
        return removed
    }

    @Synchronized override fun logError(entry: String) {
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())
        save("errors", (list("errors") + "$time $entry".take(600)).takeLast(InMemoryMemory.MAX_ERRORS))
    }
    @Synchronized fun errors(): List<String> = list("errors")

    @Synchronized fun clearAll() = sp.edit().clear().apply()

    private fun list(key: String): List<String> {
        val a = JSONArray(sp.getString(key, "[]") ?: "[]")
        return (0 until a.length()).map { a.getString(it) }
    }
    private fun save(key: String, items: List<String>) =
        sp.edit().putString(key, JSONArray(items).toString()).apply()

    companion object {
        @Volatile private var instance: PrefsMemory? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: PrefsMemory(context.applicationContext).also { instance = it }
        }
    }
}
