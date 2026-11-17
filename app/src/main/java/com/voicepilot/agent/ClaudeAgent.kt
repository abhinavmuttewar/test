package com.voicepilot.agent

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams

/**
 * Drives the phone with Claude: sends the user's request plus a dump of the screen,
 * executes the tool calls Claude returns, and loops until Claude replies with text.
 * Call [run] off the main thread; it blocks on network calls.
 */
class ClaudeAgent(
    apiKey: String,
    private val phone: PhoneController,
    private val memory: Memory,
) {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    // Conversation kept for follow-ups ("yes, send it"). Append-only within a conversation.
    private val history = mutableListOf<Any>() // BetaMessage (assistant) or List<BetaContentBlockParam> (user)
    private var lastActivity = 0L

    @Volatile private var cancelled = false

    /** Stops the running task after the current step. */
    fun cancel() { cancelled = true }

    /** Runs one user request to completion and returns what should be spoken to the user. */
    fun run(request: String, onStatus: (String) -> Unit = {}): String {
        cancelled = false
        val now = System.currentTimeMillis()
        if (now - lastActivity > FOLLOW_UP_WINDOW_MS) history.clear()
        lastActivity = now
        return try {
            runLoop(request, onStatus)
        } catch (e: Exception) {
            history.clear() // history may end with an unanswered tool call
            memory.logError("Request \"$request\" crashed: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    private fun runLoop(request: String, onStatus: (String) -> Unit): String {
        val learned = if (history.isEmpty()) memoryBlock() else ""
        history.add(listOf(text("${learned}User said: \"$request\"\n\nCurrent screen:\n${phone.describeScreen()}")))

        repeat(MAX_STEPS) {
            if (cancelled) { history.clear(); return "Stopped." }
            val response = client.beta().messages().create(buildParams())
            history.add(response)
            lastActivity = System.currentTimeMillis()

            val stop = response.stopReason().orElse(null)
            if (stop == BetaStopReason.REFUSAL) {
                history.clear()
                return "Sorry, I can't help with that."
            }
            val toolUses = response.content().mapNotNull { it.toolUse().orElse(null) }
            if (stop != BetaStopReason.TOOL_USE || toolUses.isEmpty()) {
                return spokenText(response).ifBlank { "Done." }
            }

            val results = toolUses.map { use ->
                onStatus(use.name())
                val (ok, note) = try {
                    execute(use.name(), use._input().convert(Map::class.java) ?: emptyMap<String, Any?>())
                } catch (e: Exception) {
                    false to "Error: ${e.message}"
                }
                if (!ok) memory.logError("Request \"$request\": ${use.name()} ${use._input()} failed. $note")
                if (use.name() != "wait") Thread.sleep(SETTLE_MS)
                BetaContentBlockParam.ofToolResult(
                    BetaToolResultBlockParam.builder()
                        .toolUseId(use.id())
                        .content("${if (ok) "OK" else "FAILED"}. $note\n\nScreen now:\n${phone.describeScreen()}")
                        .isError(!ok)
                        .build()
                )
            }
            history.add(results)
        }
        memory.logError("Request \"$request\": gave up after $MAX_STEPS steps")
        return "I stopped after $MAX_STEPS steps. Please try a simpler request."
    }

    private fun buildParams(): MessageCreateParams {
        val b = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(16000L)
            .system(SYSTEM_PROMPT)
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            .cacheControl(BetaCacheControlEphemeral.builder().build())
            // Server-side refusal fallback: retries a refused request on another model.
            .addBeta("server-side-fallback-2026-07-01")
            .fallbacksDefault()
        TOOLS.forEach { b.addTool(it) }
        @Suppress("UNCHECKED_CAST")
        history.forEach { msg ->
            when (msg) {
                is BetaMessage -> b.addMessage(msg)
                is List<*> -> b.addUserMessageOfContentBlockParams(msg as List<BetaContentBlockParam>)
            }
        }
        return b.build()
    }

    private fun execute(name: String, input: Map<*, *>): Pair<Boolean, String> {
        fun int(key: String) = (input[key] as? Number)?.toInt()
        fun str(key: String) = input[key]?.toString() ?: ""
        return when (name) {
            "tap" -> phone.tap(int("element_id") ?: -1) to ""
            "long_press" -> phone.longPress(int("element_id") ?: -1) to ""
            "type_text" -> phone.typeText(int("element_id"), str("text"), input["submit"] == true) to ""
            "scroll" -> phone.scroll(str("direction"), int("element_id")) to ""
            "global_action" -> phone.globalAction(str("action")) to ""
            "open_app" -> phone.openApp(str("app_name")).let { it to if (it) "" else "App not found." }
            "remember" -> { memory.addNote(str("note")); true to "Saved." }
            "forget" -> memory.removeNote((int("note_number") ?: 0) - 1) to ""
            "save_shortcut" -> { memory.addShortcut(str("phrase"), str("instruction")); true to "Saved." }
            "delete_shortcut" -> memory.removeShortcut(str("phrase")) to ""
            "wait" -> { Thread.sleep(((input["seconds"] as? Number)?.toLong() ?: 1L).coerceIn(1, 10) * 1000); true to "" }
            else -> false to "Unknown tool $name"
        }
    }

    private fun memoryBlock(): String {
        val notes = memory.notes()
        val shortcuts = memory.shortcuts()
        if (notes.isEmpty() && shortcuts.isEmpty()) return ""
        return buildString {
            append("What you have learned so far (follow it):\n")
            notes.forEachIndexed { i, n -> append("${i + 1}. $n\n") }
            if (shortcuts.isNotEmpty()) {
                append("Shortcuts:\n")
                shortcuts.forEach { (p, ins) -> append("- \"$p\" -> $ins\n") }
            }
            append("\n")
        }
    }

    private fun spokenText(m: BetaMessage) =
        m.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString(" ").trim()

    private fun text(s: String) = BetaContentBlockParam.ofText(
        com.anthropic.models.beta.messages.BetaTextBlockParam.builder().text(s).build()
    )

    companion object {
        const val MODEL = "claude-opus-5-5"
        private const val MAX_STEPS = 25
        private const val SETTLE_MS = 700L
        private const val FOLLOW_UP_WINDOW_MS = 90_000L

        private val SYSTEM_PROMPT = """
            You are a hands-free voice assistant controlling the user's Samsung Galaxy S24 Ultra (Android).
            The user cannot touch the phone. You see the screen as a list of elements; actionable ones start with [id].
            Use the tools to complete the request, checking the updated screen after each action.

            Rules:
            - Prefer open_app over navigating the launcher. Use scroll when the element you need is not listed.
            - Your final text reply is read aloud: keep it to one or two short sentences, no markdown.
            - Reply in the language the user spoke (Hindi in Devanagari, or English). Hinglish requests are fine.
            - If the request is ambiguous (e.g. two contacts match), ask one short question instead of guessing.
            - Before paying money, buying something, deleting data, or changing security settings, ask the user to confirm,
              unless they explicitly asked for that exact action.
            - If the screen is a lock screen asking for a PIN or password, tell the user to unlock the phone.
            - If you get stuck after a few attempts, say what blocked you.

            Learning (you get better with practice):
            - When the user teaches a preference or corrects you ("no, Mom is saved as Mummy", "always use YouTube Music"),
              call remember with a short, general note, then continue.
            - When a task needed trial and error, remember a one-line recipe of what finally worked
              (e.g. "Flashlight: open quick settings, tap Flashlight"). Don't save notes for things that worked first time.
            - When the user says "when I say X, do Y", call save_shortcut. Use forget / delete_shortcut when asked to unlearn.
            - If a learned note turns out to be wrong, forget it and remember the corrected version.
        """.trimIndent()

        private fun tool(name: String, desc: String, props: Map<String, Map<String, Any>>, required: List<String>) =
            BetaTool.builder()
                .name(name)
                .description(desc)
                .inputSchema(
                    BetaTool.InputSchema.builder()
                        .properties(
                            BetaTool.InputSchema.Properties.builder().apply {
                                props.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
                            }.build()
                        )
                        .required(required)
                        .build()
                )
                .build()

        private val ID = mapOf("type" to "integer", "description" to "Element id shown in [brackets] on the screen dump")

        private val TOOLS = listOf(
            tool("tap", "Tap a screen element.", mapOf("element_id" to ID), listOf("element_id")),
            tool("long_press", "Long-press a screen element.", mapOf("element_id" to ID), listOf("element_id")),
            tool(
                "type_text", "Replace the text in an editable field. Omit element_id to use the focused field.",
                mapOf(
                    "element_id" to ID,
                    "text" to mapOf("type" to "string"),
                    "submit" to mapOf("type" to "boolean", "description" to "Press the keyboard action (search/send/enter) after typing"),
                ),
                listOf("text"),
            ),
            tool(
                "scroll", "Scroll the screen, or a specific scrollable element.",
                mapOf(
                    "direction" to mapOf("type" to "string", "enum" to listOf("up", "down", "left", "right")),
                    "element_id" to ID,
                ),
                listOf("direction"),
            ),
            tool(
                "global_action", "System navigation actions.",
                mapOf(
                    "action" to mapOf(
                        "type" to "string",
                        "enum" to listOf("back", "home", "recents", "notifications", "quick_settings", "lock_screen", "screenshot"),
                    )
                ),
                listOf("action"),
            ),
            tool(
                "open_app", "Launch an installed app by its name, e.g. \"WhatsApp\", \"Camera\".",
                mapOf("app_name" to mapOf("type" to "string")), listOf("app_name"),
            ),
            tool(
                "remember", "Save a lesson for future requests: a preference, correction, or recipe for a task.",
                mapOf("note" to mapOf("type" to "string")), listOf("note"),
            ),
            tool(
                "forget", "Delete a learned note by its number.",
                mapOf("note_number" to mapOf("type" to "integer")), listOf("note_number"),
            ),
            tool(
                "save_shortcut", "Make a spoken phrase run an instruction, e.g. phrase \"good night\" -> \"turn on Do Not Disturb and set an alarm for 7 am\".",
                mapOf("phrase" to mapOf("type" to "string"), "instruction" to mapOf("type" to "string")),
                listOf("phrase", "instruction"),
            ),
            tool(
                "delete_shortcut", "Delete a spoken shortcut.",
                mapOf("phrase" to mapOf("type" to "string")), listOf("phrase"),
            ),
            tool(
                "wait", "Wait for content to load.",
                mapOf("seconds" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 10)), listOf("seconds"),
            ),
        )
    }
}
