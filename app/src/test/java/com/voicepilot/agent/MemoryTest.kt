package com.voicepilot.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTest {
    @Test fun notesAndShortcuts() {
        val m = InMemoryMemory()
        m.addNote("Mom is saved as Mummy")
        m.addNote("Mom is saved as Mummy")
        assertEquals(1, m.notes().size)
        assertTrue(m.removeNote(0))
        m.addShortcut("Good Night!", "turn on Do Not Disturb")
        assertEquals("turn on Do Not Disturb", m.shortcuts()[normalizePhrase("good   night")])
        assertTrue(m.removeShortcut("good night"))
    }

    @Test fun notesAreCapped() {
        val m = InMemoryMemory()
        repeat(InMemoryMemory.MAX_NOTES + 5) { m.addNote("note $it") }
        assertEquals(InMemoryMemory.MAX_NOTES, m.notes().size)
        assertEquals("note 5", m.notes().first())
    }
}
