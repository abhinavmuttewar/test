package com.voicepilot.agent

/** Offline commands handled without the AI. English, romanized Hindi and Devanagari Hindi. */
sealed class Command {
    data class Global(val action: String) : Command()
    data class Scroll(val direction: String) : Command()
    data class OpenApp(val name: String) : Command()
    data class TapText(val text: String) : Command()
    data class Type(val text: String) : Command()
    object Stop : Command()
}

object CommandParser {
    private val opt = RegexOption.IGNORE_CASE

    private val globals = listOf(
        "back" to Regex("^(go back|back|वापस( जाओ)?|पीछे( जाओ)?|peeche( jao)?|wapas( jao)?)$", opt),
        "home" to Regex("^(go home|home( screen)?|होम( स्क्रीन)?( पर)?( जाओ)?|home (par )?jao)$", opt),
        "recents" to Regex("^(recent apps|recents|show recent apps|रीसेंट( ऐप्स)?)$", opt),
        "notifications" to Regex("^((show |open )?notifications|नोटिफिकेशन(्स)?( दिखाओ)?|notification dikhao)$", opt),
        "quick_settings" to Regex("^((open )?quick settings|क्विक सेटिंग(्स)?)$", opt),
        "screenshot" to Regex("^((take a )?screenshot|स्क्रीनशॉट( लो)?|screenshot lo)$", opt),
        "lock_screen" to Regex("^(lock( the)? (phone|screen)|फ़?फोन लॉक करो|लॉक करो|phone lock karo)$", opt),
    )
    private val scrolls = listOf(
        "down" to Regex("^(scroll down|down|नीचे( करो| स्क्रॉल करो)?|neeche( karo)?)$", opt),
        "up" to Regex("^(scroll up|up|ऊपर( करो| स्क्रॉल करो)?|upar( karo)?)$", opt),
        "left" to Regex("^(scroll left|swipe left|बाएं( स्वाइप करो)?)$", opt),
        "right" to Regex("^(scroll right|swipe right|दाएं( स्वाइप करो)?)$", opt),
    )
    private val stop = Regex("^(stop|cancel|never ?mind|रुको|बंद करो|ruko|cancel karo)$", opt)
    private val open = listOf(
        Regex("^(?:open|launch|start) (.+)$", opt),
        Regex("^(.+?) (?:खोलो|खोलें|ओपन करो|kholo|open karo)$", opt),
    )
    private val tap = listOf(
        Regex("^(?:tap|click|press|tap on|click on) (.+)$", opt),
        Regex("^(.+?) (?:पर )?(?:दबाओ|क्लिक करो|टैप करो|dabao|(?:par )?click karo|(?:par )?tap karo)$", opt),
    )
    private val type = listOf(
        Regex("^(?:type|write) (.+)$", opt),
        Regex("^(?:लिखो|टाइप करो|likho|type karo) (.+)$", opt),
    )

    fun parse(raw: String): Command? {
        val s = raw.trim().trimEnd('.', '!', '?', '।').replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return null
        if (stop.matches(s)) return Command.Stop
        globals.firstOrNull { it.second.matches(s) }?.let { return Command.Global(it.first) }
        scrolls.firstOrNull { it.second.matches(s) }?.let { return Command.Scroll(it.first) }
        open.firstNotNullOfOrNull { it.find(s) }?.let { return Command.OpenApp(it.groupValues[1].removeSuffix(" app").trim()) }
        tap.firstNotNullOfOrNull { it.find(s) }?.let { return Command.TapText(it.groupValues[1].trim()) }
        type.firstNotNullOfOrNull { it.find(s) }?.let { return Command.Type(it.groupValues[1].trim()) }
        return null
    }
}
