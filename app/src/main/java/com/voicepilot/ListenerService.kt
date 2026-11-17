package com.voicepilot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.voicepilot.agent.ClaudeAgent
import com.voicepilot.agent.Command
import com.voicepilot.agent.CommandParser
import com.voicepilot.agent.normalizePhrase
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Always-on listener: offline wake word (Vosk) -> command speech-to-text (Android SpeechRecognizer)
 * -> offline command or Claude agent -> spoken reply (TextToSpeech).
 */
class ListenerService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs

    private var model: Model? = null
    private var wakeService: SpeechService? = null
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }

    private var agent: ClaudeAgent? = null
    private var agentKey = ""
    @Volatile private var busy = false
    private var afterSpeech: (() -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        try {
            val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            startForeground(NOTIF_ID, notification("Starting…"), type)
        } catch (e: Exception) {
            // Android blocks starting the microphone from the background; open the app to start.
            Log.w(TAG, "Cannot start in background", e)
            stopSelf()
            return
        }
        running = true
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { main.post { runAfterSpeech() } }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { main.post { runAfterSpeech() } }
            })
        }
        StorageService.unpack(this, "model-en-us", "model",
            { m -> model = m; startWakeListening() },
            { e -> Log.e(TAG, "Model load failed", e); status("Wake word model failed to load") })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_LISTEN_NOW) main.post { onWake() }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        stopWakeListening()
        recognizer?.destroy()
        tts?.shutdown()
        model?.close()
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---- Wake word ----

    private fun startWakeListening() {
        val m = model ?: return
        if (wakeService != null) return
        val phrase = prefs.wakePhrase
        try {
            val rec = Recognizer(m, 16000f, "[\"$phrase\", \"[unk]\"]")
            wakeService = SpeechService(rec, 16000f).also {
                it.startListening(object : org.vosk.android.RecognitionListener {
                    override fun onPartialResult(h: String?) = check(h, "partial")
                    override fun onResult(h: String?) = check(h, "text")
                    override fun onFinalResult(h: String?) {}
                    override fun onError(e: Exception?) { Log.e(TAG, "Wake error", e) }
                    override fun onTimeout() {}
                    private fun check(json: String?, key: String) {
                        val text = json?.let { j -> runCatching { JSONObject(j).optString(key) }.getOrNull() } ?: return
                        if (text.contains(phrase)) main.post { onWake() }
                    }
                })
            }
            status(if (busy) "Working…" else "Say \"$phrase\"")
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start wake listening", e)
            status("Microphone unavailable")
        }
    }

    private fun stopWakeListening() {
        wakeService?.let { it.stop(); it.shutdown() }
        wakeService = null
    }

    private fun onWake() {
        stopWakeListening()
        tts?.stop()
        wakeScreen()
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        main.postDelayed({ listenForCommand() }, 250)
    }

    // ---- Command speech-to-text ----

    private fun listenForCommand() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("Speech recognition is not available on this phone.") { startWakeListening() }
            return
        }
        status("Listening…")
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (text.isNullOrBlank()) startWakeListening() else handle(text)
                }
                override fun onError(error: Int) {
                    Log.d(TAG, "STT error $error")
                    startWakeListening()
                }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            startListening(recognizerIntent())
        }
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        val lang = prefs.language
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (lang == "hi-IN") "hi-IN" else "en-IN")
        if (lang == "auto" && Build.VERSION.SDK_INT >= 34) {
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, arrayListOf("en-IN", "hi-IN"))
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
        }
    }

    // ---- Handling ----

    private fun handle(text: String) {
        Log.i(TAG, "Heard: $text")
        val command = CommandParser.parse(text)
        if (command == Command.Stop) {
            agent?.cancel()
            speak(if (isHindi(text)) "ठीक है" else "OK") { startWakeListening() }
            return
        }
        if (busy) {
            speak(if (isHindi(text)) "मैं अभी काम कर रहा हूँ। रोकने के लिए 'रुको' बोलें।" else "I'm still working. Say stop to cancel.") { startWakeListening() }
            return
        }
        val phone = VoiceAccessService.instance
        if (phone == null) {
            speak("Please turn on the VoicePilot accessibility service in Settings.") { startWakeListening() }
            return
        }
        busy = true
        startWakeListening() // so "hey phone, stop" works while a task runs
        worker.execute {
            val reply = try {
                val memory = PrefsMemory.get(this)
                val shortcut = memory.shortcuts()[normalizePhrase(text)]
                when {
                    shortcut != null -> runAgent("$text (saved shortcut: $shortcut)", phone)
                    command != null && runOffline(command, phone) -> null
                    else -> {
                        if (command != null) memory.logError("Offline command failed, used AI: \"$text\" -> $command")
                        runAgent(text, phone)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Command failed", e)
                PrefsMemory.get(this).logError("\"$text\" failed: ${e.javaClass.simpleName}: ${e.message}")
                if (isHindi(text)) "माफ़ कीजिए, कुछ गड़बड़ हो गई।" else "Sorry, something went wrong: ${e.message?.take(80)}"
            }
            main.post {
                busy = false
                if (reply == null) {
                    tone.startTone(ToneGenerator.TONE_PROP_ACK, 120)
                    status("Say \"${prefs.wakePhrase}\"")
                } else {
                    stopWakeListening()
                    // A question from the assistant: listen for the answer without the wake word.
                    speak(reply) { if (reply.trimEnd().endsWith("?")) onWake() else startWakeListening() }
                }
            }
        }
    }

    /** Returns true if the offline command succeeded; false falls back to the AI. */
    private fun runOffline(c: Command, phone: VoiceAccessService): Boolean = when (c) {
        is Command.Global -> phone.globalAction(c.action)
        is Command.Scroll -> phone.scroll(c.direction, null)
        is Command.OpenApp -> phone.openApp(c.name)
        is Command.TapText -> phone.tapText(c.text)
        is Command.Type -> phone.typeText(null, c.text, false)
        Command.Stop -> true
    }

    private fun runAgent(text: String, phone: VoiceAccessService): String {
        val key = prefs.apiKey
        if (key.isBlank()) {
            return if (isHindi(text)) "यह कमांड समझने के लिए ऐप में API key डालें।"
            else "I only know basic commands. Add your Claude API key in the app for everything else."
        }
        if (agent == null || agentKey != key) { agent = ClaudeAgent(key, phone, PrefsMemory.get(this)); agentKey = key }
        status("Working…")
        return agent!!.run(text) { step -> main.post { status("Working: $step") } }
    }

    // ---- Output ----

    private fun speak(text: String, then: () -> Unit) {
        status(text)
        val t = tts
        if (t == null || !ttsReady) { then(); return }
        afterSpeech = then
        t.language = if (isHindi(text)) Locale("hi", "IN") else Locale("en", "IN")
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    private fun runAfterSpeech() {
        val next = afterSpeech ?: return
        afterSpeech = null
        next()
    }

    private fun isHindi(s: String) = s.any { it in 'ऀ'..'ॿ' }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "voicepilot:wake"
            ).acquire(15_000)
        }
    }

    private fun status(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Voice listener", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ListenerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("VoicePilot")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "VoicePilot"
        private const val CHANNEL = "listener"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "com.voicepilot.STOP"
        const val ACTION_LISTEN_NOW = "com.voicepilot.LISTEN_NOW"
        @Volatile var running = false

        fun start(context: Context, action: String? = null) {
            context.startForegroundService(Intent(context, ListenerService::class.java).setAction(action))
        }
    }
}
