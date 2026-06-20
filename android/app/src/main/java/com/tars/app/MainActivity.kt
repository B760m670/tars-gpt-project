package com.tars.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * One screen, one stream. You talk to TARS; his system log scrolls in the same
 * view. v1 is brain + text only: the on-device llama.cpp engine does the real
 * thinking, with the scripted offline brain as a never-dead fallback. Voice,
 * ears and vision return as later, verified steps.
 */
class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val brainExec = Executors.newSingleThreadExecutor()

    private lateinit var stream: TextView
    private lateinit var streamScroll: ScrollView
    private lateinit var statusView: TextView

    private val diag = StringBuilder()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val personality = Personality()
    private lateinit var brain: LlamaBrain

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        stream = findViewById(R.id.log)
        streamScroll = findViewById(R.id.scroll)
        statusView = findViewById(R.id.status)
        stream.movementMethod = ScrollingMovementMethod()
        stream.text = getString(R.string.greeting)

        brain = LlamaBrain(this)

        findViewById<Button>(R.id.brain).setOnClickListener { setUpBrain() }
        findViewById<Button>(R.id.send).setOnClickListener { submit() }
        findViewById<EditText>(R.id.input).setOnEditorActionListener { _, _, _ -> submit(); true }

        setStatus()
        log("console: type to talk — English or Russian.")
        log("brain: tap 'Brain' to download the on-device model (one time), then TARS truly thinks.")

        // If a model is already downloaded, bring the brain up automatically.
        brainExec.execute { autoStart() }
    }

    private fun submit() {
        val input = findViewById<EditText>(R.id.input)
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        appendLine("\nyou> $text")
        brainExec.execute { converse(text) }
    }

    private fun converse(text: String) {
        if (brain.ready) {
            runOnUiThread { appendInline("\nTARS> ") }
            val full = brain.reply(text) { chunk -> runOnUiThread { appendInline(chunk) } }
            if (full.isBlank()) runOnUiThread { appendInline("…") }
        } else {
            val reply = OfflineBrain.reply(text)
            runOnUiThread { appendLine("TARS> $reply") }
        }
    }

    // ---- Brain setup ----

    private fun modelsDir(): File = File(filesDir, "models").apply { mkdirs() }

    private fun autoStart() {
        val spec = ModelCatalog.recommend() ?: return
        val model = File(modelsDir(), spec.fileName)
        if (model.exists() && model.length() > 0) {
            log("brain: found ${spec.id}, loading on-device engine…")
            loadBrain(model)
        } else {
            log("brain: no model yet — tap 'Brain' to download ${spec.id} (~${spec.fileMb} MB).")
        }
    }

    private fun setUpBrain() {
        log("brain: setting up on-device engine…")
        brainExec.execute {
            val ram = ModelCatalog.totalRamMb()
            val spec = ModelCatalog.recommend(ram)
            if (spec == null) {
                log("brain: device RAM (${ram ?: "?"} MB) too low for a local model — offline brain only.")
                return@execute
            }
            val model = File(modelsDir(), spec.fileName)
            if (!(model.exists() && model.length() > 0)) {
                log("brain: model = ${spec.id} (~${spec.fileMb} MB) from GitHub release")
                val err = ModelDownloader.download(spec.url, model) { line -> log(line) }
                if (err != null) { log(err); return@execute }
            }
            loadBrain(model)
        }
    }

    private fun loadBrain(model: File) {
        if (brain.ready) return
        log("brain: loading model into llama.cpp (a few seconds)…")
        val err = brain.load(model.absolutePath, personality.systemPrompt())
        if (err != null) {
            log("brain: $err")
            return
        }
        log("brain: engine READY — running a quick self-test…")
        val proof = brain.selfTest()
        log("brain: self-test — TARS says: \"$proof\"")
        log("brain: TARS now thinks on-device. Talk to him.")
        setStatus()
    }

    // ---- Personality dials ----

    private fun showDials() {
        val names = listOf("Humor", "Honesty", "Discretion", "Sarcasm")
        val cur = intArrayOf(personality.humor, personality.honesty, personality.discretion, personality.sarcasm)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        for (i in 0 until 4) {
            val label = TextView(this).apply { text = "${names[i]}: ${cur[i]}%"; textSize = 14f }
            val bar = SeekBar(this).apply {
                max = 100; progress = cur[i]
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                        cur[i] = v; label.text = "${names[i]}: $v%"
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            root.addView(label); root.addView(bar)
        }
        AlertDialog.Builder(this)
            .setTitle("TARS — settings")
            .setView(ScrollView(this).apply { addView(root) })
            .setPositiveButton("Apply") { _, _ ->
                personality.humor = cur[0]; personality.honesty = cur[1]
                personality.discretion = cur[2]; personality.sarcasm = cur[3]
                setStatus()
                log("settings: humor=${cur[0]} honesty=${cur[1]} discretion=${cur[2]} sarcasm=${cur[3]}" +
                    if (brain.ready) " (re-tap 'Brain' to re-imprint the running engine)" else "")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Personality")
        menu.add(0, 2, 1, "Copy log")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> { showDials(); return true }
            2 -> { copyLog(); return true }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("TARS log", diag.toString()))
        log("log: copied to clipboard")
    }

    // ---- stream helpers ----

    @Synchronized
    private fun log(line: String) {
        diag.append(clock.format(Date())).append("  ").append(line).append('\n')
        appendLine("· $line")
    }

    private fun appendLine(text: String) = ui.post {
        stream.append("\n$text"); scrollDown()
    }

    private fun appendInline(text: String) = ui.post {
        stream.append(text); scrollDown()
    }

    private fun scrollDown() = streamScroll.post { streamScroll.fullScroll(ScrollView.FOCUS_DOWN) }

    private fun setStatus() = runOnUiThread {
        val mind = if (::brain.isInitialized && brain.ready) "ON-DEVICE" else "OFFLINE"
        statusView.text = "TARS · $mind · H${personality.humor} HON${personality.honesty} S${personality.sarcasm}"
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        if (::brain.isInitialized) brain.shutdown()
        brainExec.shutdownNow()
        super.onDestroy()
    }
}
