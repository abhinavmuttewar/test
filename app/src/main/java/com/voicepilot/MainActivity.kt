package com.voicepilot

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/** One-time setup screen: permissions, API key, wake phrase, language. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private val languages = listOf("auto" to "English + Hindi (auto)", "en-IN" to "English", "hi-IN" to "हिन्दी")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        status = TextView(this).apply { textSize = 15f }
        root.addView(status)

        root.addView(button("1. Allow microphone & notifications") { requestPermissions() })
        root.addView(button("2. Turn on accessibility service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("3. Allow running in background") { requestBatteryExemption() })

        root.addView(label("Claude API key (for free-form commands)"))
        val key = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "sk-ant-…"
            setText(prefs.apiKey)
        }
        root.addView(key)

        root.addView(label("Wake phrase (English words)"))
        val wake = EditText(this).apply { setText(prefs.wakePhrase) }
        root.addView(wake)

        root.addView(label("Language"))
        val lang = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, languages.map { it.second })
            setSelection(languages.indexOfFirst { it.first == prefs.language }.coerceAtLeast(0))
        }
        root.addView(lang)

        root.addView(button("Save & start listening") {
            prefs.apiKey = key.text.toString()
            prefs.wakePhrase = wake.text.toString()
            prefs.language = languages[lang.selectedItemPosition].first
            stopService(Intent(this, ListenerService::class.java)) // restart with new settings
            ListenerService.start(this)
            refresh()
        })
        root.addView(button("Test: listen now") { ListenerService.start(this, ListenerService.ACTION_LISTEN_NOW) })
        root.addView(button("Stop listening") {
            stopService(Intent(this, ListenerService::class.java))
            refresh()
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        status.postDelayed({
            fun mark(ok: Boolean) = if (ok) "✅" else "❌"
            status.text = """
                ${mark(hasMic())} Microphone permission
                ${mark(VoiceAccessService.instance != null)} Accessibility service
                ${mark(isBatteryExempt())} Background running allowed
                ${mark(prefs.apiKey.isNotBlank())} Claude API key
                ${mark(ListenerService.running)} Listening for "${prefs.wakePhrase}"
            """.trimIndent()
        }, 300)
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun isBatteryExempt() = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 1)
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        refresh()
    }

    private fun button(text: String, onClick: (View) -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener(onClick)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
    }
}
