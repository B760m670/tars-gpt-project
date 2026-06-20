package com.tars.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * TARS as a terminal: black CRT screen, phosphor-green monospace log. You type
 * commands or talk; TARS answers in the same stream. The mind is [RemoteBrain]
 * (OpenAI for now). Slash-commands set the key and model.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        stream = findViewById(R.id.log)
        streamScroll = findViewById(R.id.scroll)
        statusView = findViewById(R.id.status)
        stream.movementMethod = ScrollingMovementMethod()

        findViewById<Button>(R.id.send).setOnClickListener { submit() }
        findViewById<EditText>(R.id.input).setOnEditorActionListener { _, _, _ -> submit(); true }

        boot()
    }

    /** Green boot log — TARS terminal tone, plus a first-run quick start. */
    private fun boot() {
        val hasKey = store.key() != null
        val lines = listOf(
            "TARS SYSTEM // COSMOS-1A",
            "initializing core ............. ok",
            "personality matrix ........... loaded",
            "uplink: OpenAI .............. ${if (hasKey) "key present" else "no key"}",
        )
        var delay = 120L
        for (line in lines) { ui.postDelayed({ emit(line) }, delay); delay += 90 }
        ui.postDelayed({
            if (!hasKey) {
                emit("")
                emit("First run — connect TARS to OpenAI (free via data sharing):")
                emit("  1) platform.openai.com -> enable data sharing, create a key")
                emit("  2) here:  /key sk-...your_key...")
                emit("  3) talk to TARS.   ( /help for commands )")
            } else {
                emit("")
                emit("TARS online. Talk to me — or /help.")
            }
            refreshStatus()
        }, delay)
    }

    private fun submit() {
        val input = findViewById<EditText>(R.id.input)
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        emit("> $text")
        if (text.startsWith("/")) { handleCommand(text); return }
        setStatus("THINKING")
        work.execute {
            val reply = brain.reply(text)
            emit("TARS> $reply")
            refreshStatus()
        }
    }

    // ---- slash commands ----

    private fun handleCommand(line: String) {
        val parts = line.trim().split(Regex("\\s+"))
        when (parts[0].lowercase(Locale.US)) {
            "/help" -> emit(HELP)
            "/key" -> if (parts.size >= 2) {
                store.setKey(parts[1]); emit("key stored locally."); refreshStatus()
            } else emit("usage: /key <openai_key>")
            "/model" -> if (parts.size >= 2) {
                store.setModel(parts[1]); emit("model = ${parts[1]}"); refreshStatus()
            } else emit("usage: /model <model_id>   (current: ${store.model() ?: brain.defaultModel})")
            "/models" -> { emit("fetching available models…"); work.execute { emit(brain.listModels()) } }
            "/clear" -> ui.post { stream.text = "" }
            else -> emit("unknown command '${parts[0]}'. /help")
        }
    }

    // ---- terminal output ----

    /** Append a line. Safe from any thread. */
    private fun emit(line: String) = ui.post {
        if (stream.text.isNotEmpty()) stream.append("\n")
        stream.append(line)
        streamScroll.post { streamScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun refreshStatus() {
        val mode = if (store.key() != null) "OPENAI:${store.model() ?: brain.defaultModel}" else "NO KEY"
        setStatus(mode)
    }

    private fun setStatus(mode: String) = ui.post {
        statusView.text = "TARS · $mode · ${clock.format(Date())}"
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        work.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private val HELP = listOf(
            "commands:",
            "  /key <openai_key>   store your OpenAI key (local only)",
            "  /model <id>         set model (default gpt-5)",
            "  /models             list models your key can use",
            "  /clear              clear the screen",
            "  /help               this list",
            "free tokens need data sharing enabled at platform.openai.com",
        ).joinToString("\n")
    }
}
