package com.tars.app

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * TARS as a terminal: black CRT screen, phosphor-green monospace log. You type
 * or speak (MIC); TARS answers in the stream and aloud. Brain = [RemoteBrain]
 * (Gemini), mouth = [Voice] (on-device TTS + effects), ears = [Ears] (on-device STT).
 */
class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var stream: TextView
    private lateinit var streamScroll: ScrollView
    private lateinit var statusView: TextView

    private val prefs by lazy { getSharedPreferences("tars", MODE_PRIVATE) }
    private val store by lazy { KeyStore(prefs) }
    private val brain by lazy { RemoteBrain(store) }
    private val voice by lazy { Voice(this, prefs) { line -> log(line) } }
    private val ears by lazy { Ears(this) { line -> log(line) } }
    private val updater by lazy { Updater(this) { line -> log(line) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        stream = findViewById(R.id.log)
        streamScroll = findViewById(R.id.scroll)
        statusView = findViewById(R.id.status)
        stream.movementMethod = ScrollingMovementMethod()
        stream.setOnLongClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("TARS terminal", stream.text))
            Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
            true
        }

        findViewById<Button>(R.id.send).setOnClickListener { submit() }
        findViewById<Button>(R.id.mic).setOnClickListener { startListening() }
        findViewById<EditText>(R.id.input).setOnEditorActionListener { _, _, _ -> submit(); true }

        voice            // kick off TTS init
        ears.onResult = { text -> emit("> $text"); handleUserText(text) }

        boot()
    }

    private fun boot() {
        val hasKey = store.key() != null
        val lines = listOf(
            "TARS SYSTEM // COSMOS-1A",
            "initializing core ............. ok",
            "voice + ears ................. on-device",
            "uplink: Gemini ............... ${if (hasKey) "key present" else "no key"}",
        )
        var delay = 120L
        for (line in lines) { ui.postDelayed({ emit(line) }, delay); delay += 90 }
        ui.postDelayed({
            if (!hasKey) {
                emit("")
                emit("First run — connect TARS to Google Gemini (free, ~1500/day):")
                emit("  1) aistudio.google.com/apikey -> create a key (no card)")
                emit("  2) here:  /key AI...your_key...")
                emit("  3) talk or tap MIC.   ( /help for commands )")
            } else {
                emit("")
                emit("TARS online. Talk, or tap MIC to speak. /help for commands.")
            }
            refreshStatus()
            checkUpdates(silent = true)
        }, delay)
    }

    private fun checkUpdates(silent: Boolean) {
        work.execute {
            val u = updater.checkForUpdate(BuildConfig.VERSION_CODE)
            if (u != null) ui.post { promptUpdate(u.first, u.second) }
            else if (!silent) emit("update: you're on the latest build (b${BuildConfig.VERSION_CODE}).")
        }
    }

    private fun promptUpdate(version: Int, apkUrl: String) {
        AlertDialog.Builder(this)
            .setTitle("Обновление TARS")
            .setMessage("Доступна сборка b$version (у тебя b${BuildConfig.VERSION_CODE}). Скачать и установить?")
            .setPositiveButton("Обновить") { _, _ ->
                emit("update: downloading b$version…")
                work.execute { updater.downloadAndInstall(apkUrl) }
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun submit() {
        val input = findViewById<EditText>(R.id.input)
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        emit("> $text")
        handleUserText(text)
    }

    private fun handleUserText(text: String) {
        if (text.startsWith("/")) { handleCommand(text); return }
        setStatus("THINKING")
        work.execute {
            val reply = brain.reply(text)
            emit("TARS> $reply")
            voice.speak(reply)
            refreshStatus()
        }
    }

    // ---- voice input ----

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        ears.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) ears.start()
            else emit("mic: permission denied")
        }
    }

    // ---- slash commands ----

    private fun handleCommand(line: String) {
        val parts = line.trim().split(Regex("\\s+"))
        when (parts[0].lowercase(Locale.US)) {
            "/help" -> emit(HELP)
            "/key" -> if (parts.size >= 2) { store.setKey(parts[1]); emit("key stored locally."); refreshStatus() }
                      else emit("usage: /key <gemini_key>")
            "/model" -> if (parts.size >= 2) { store.setModel(parts[1]); emit("model = ${parts[1]}"); refreshStatus() }
                        else emit("usage: /model <id>   (current: ${store.model() ?: "none"})")
            "/models" -> { emit("fetching available models…"); work.execute { emit(brain.listModels()) } }
            "/voice" -> { voice.enabled = !voice.enabled; emit("voice: ${if (voice.enabled) "on" else "off"}") }
            "/rate" -> parts.getOrNull(1)?.toFloatOrNull()?.let { voice.rate = it; emit("rate = $it") }
                       ?: emit("usage: /rate <0.5-1.5>   (current ${voice.rate})")
            "/pitch" -> parts.getOrNull(1)?.toFloatOrNull()?.let { voice.pitch = it; emit("pitch = $it") }
                        ?: emit("usage: /pitch <0.5-2.0>   (current ${voice.pitch})")
            "/nasal" -> parts.getOrNull(1)?.toFloatOrNull()?.let { voice.nasal = it; emit("nasal = $it") }
                        ?: emit("usage: /nasal <0-1>   (current ${voice.nasal})")
            "/say" -> { val t = line.substringAfter("/say").trim(); if (t.isNotEmpty()) voice.speak(t) else emit("usage: /say <text>") }
            "/update" -> { emit("checking for updates…"); checkUpdates(silent = false) }
            "/clear" -> ui.post { stream.text = "" }
            else -> emit("unknown command '${parts[0]}'. /help")
        }
    }

    // ---- terminal output ----

    private fun emit(line: String) = ui.post {
        if (stream.text.isNotEmpty()) stream.append("\n")
        stream.append(line)
        streamScroll.post { streamScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /** alias for internal logging into the same stream */
    private fun log(line: String) = emit("· $line")

    private fun refreshStatus() {
        val mode = if (store.key() == null) "NO KEY" else "GEMINI:${store.model() ?: "no model"}"
        setStatus(mode)
    }

    private fun setStatus(mode: String) = ui.post {
        statusView.text = "TARS · $mode · ${if (voice.enabled) "VOICE" else "MUTE"} · ${clock.format(Date())}"
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        runCatching { voice.shutdown() }
        runCatching { ears.destroy() }
        work.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val REQ_MIC = 101
        private val HELP = listOf(
            "commands:",
            "  /key <gemini_key>   store your Gemini key (local only)",
            "  /model <id>         set model (required; see /models)",
            "  /models             list models your key can use",
            "  /voice              toggle speaking aloud on/off",
            "  /rate <0.5-1.5>     speech speed",
            "  /pitch <0.5-2.0>    voice pitch",
            "  /nasal <0-1>        nasal / VHS colour (Gavrilov-ish)",
            "  /say <text>         test the voice",
            "  /update             check for a new build & install",
            "  /clear              clear the screen",
            "  /help               this list",
            "MIC button = speak to TARS. free key: aistudio.google.com/apikey",
        ).joinToString("\n")
    }
}
