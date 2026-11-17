package com.voicepilot.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandParserTest {
    @Test fun english() {
        assertEquals(Command.Global("back"), CommandParser.parse("Go back"))
        assertEquals(Command.Global("home"), CommandParser.parse("home"))
        assertEquals(Command.Scroll("down"), CommandParser.parse("scroll down."))
        assertEquals(Command.OpenApp("WhatsApp"), CommandParser.parse("open WhatsApp app"))
        assertEquals(Command.TapText("send"), CommandParser.parse("tap send"))
        assertEquals(Command.Type("hello there"), CommandParser.parse("type hello there"))
        assertEquals(Command.Stop, CommandParser.parse("cancel"))
    }

    @Test fun hindi() {
        assertEquals(Command.Global("back"), CommandParser.parse("वापस जाओ"))
        assertEquals(Command.Scroll("down"), CommandParser.parse("नीचे"))
        assertEquals(Command.OpenApp("व्हाट्सएप"), CommandParser.parse("व्हाट्सएप खोलो"))
        assertEquals(Command.OpenApp("youtube"), CommandParser.parse("youtube kholo"))
        assertEquals(Command.TapText("सेंड"), CommandParser.parse("सेंड पर क्लिक करो"))
        assertEquals(Command.Type("नमस्ते"), CommandParser.parse("लिखो नमस्ते"))
    }

    @Test fun freeFormGoesToAi() {
        assertNull(CommandParser.parse("send hi to Rahul on WhatsApp"))
        assertNull(CommandParser.parse("मौसम कैसा है"))
    }
}
