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
 * commands or talk; TARS answers in the same stream. The mind is [RemoteBrain] —
 * a pool of free cloud providers with auto-failover. Slash-commands configure it.
 */
class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var stream: TextView
    private lateinit var streamScroll: ScrollView
    private lateinit var statusView: TextView

    private val prefs by lazy { getSharedPreferences("tars", MODE_PRIVATE) }
    private val keys by lazy { KeyStore(prefs) }
    private val brain by lazy { RemoteBrain(keys) }

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

    /** Green boot log — TARS terminal tone, plus a hint at first run. */
    private fun boot() {
        val lines = listOf(
            "TARS SYSTEM // COSMOS-1A",
            "initializing core ............. ok",
            "personality matrix ........... loaded",
            "uplink: cloud brain pool ..... ${if (keys.key(keys.activeProvider()) != null) "online" else "no key"}",
        )
        var delay = 120L
        for (line in lines) { ui.postDelayed({ emit(line) }, delay); delay += 90 }
        ui.postDelayed({
            if (keys.key(keys.activeProvider()) == null) {
                emit("")
                emit("No API key yet. Quick start (free):")
                emit("  1) get a key at openrouter.ai/keys")
                emit("  2) here, type:  /key openrouter <your_key>")
                emit("  3) talk to TARS.   ( /help for all commands )")
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
            "/providers" -> emit(brain.providers.values.joinToString("\n") { p ->
                val mark = if (p.id == keys.activeProvider()) "*" else " "
                "$mark ${p.id.padEnd(11)} key=${if (keys.key(p.id) != null) "set" else "—"}  " +
                "model=${keys.model(p.id) ?: p.defaultModel}"
            } + "\n(* = active;  key at <provider>: see /help)")
            "/key" -> if (parts.size >= 3) {
                val p = parts[1].lowercase(Locale.US)
                if (!brain.providers.containsKey(p)) { emit("unknown provider '$p'. ${brain.providers.keys.joinToString()}"); return }
                keys.setKey(p, parts[2]); keys.setActive(p)
                emit("key stored for $p (now active).")
                refreshStatus()
            } else emit("usage: /key <provider> <key>")
            "/use" -> if (parts.size >= 2 && brain.providers.containsKey(parts[1].lowercase(Locale.US))) {
                keys.setActive(parts[1].lowercase(Locale.US)); emit("active provider: ${parts[1]}"); refreshStatus()
            } else emit("usage: /use <provider>   (${brain.providers.keys.joinToString()})")
            "/model" -> when {
                parts.size >= 3 -> { keys.setModel(parts[1].lowercase(Locale.US), parts[2]); emit("model for ${parts[1]} = ${parts[2]}") }
                parts.size == 2 -> { val a = keys.activeProvider(); keys.setModel(a, parts[1]); emit("model for $a = ${parts[1]}") }
                else -> emit("usage: /model [provider] <model_id>")
            }
            "/models" -> { emit("fetching OpenRouter free models…"); work.execute { emit(brain.openRouterFreeModels()) } }
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
        val a = keys.activeProvider()
        setStatus(if (keys.key(a) != null) a.uppercase(Locale.US) else "NO KEY")
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
            "  /key <provider> <key>   store a free API key & activate it",
            "  /use <provider>         switch active provider",
            "  /model [provider] <id>  set the model (override default)",
            "  /models                 list OpenRouter free model ids",
            "  /providers              show providers, keys, models",
            "  /clear                  clear the screen",
            "  /help                   this list",
            "providers: openrouter, groq, gemini, cerebras",
            "free keys: openrouter.ai/keys · console.groq.com/keys ·",
            "           aistudio.google.com/apikey · cloud.cerebras.ai",
        ).joinToString("\n")
    }
}
