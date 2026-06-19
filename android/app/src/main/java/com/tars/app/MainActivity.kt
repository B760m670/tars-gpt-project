package com.tars.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * One screen, one stream. You talk to TARS in the same console where his system
 * log scrolls and where shell/Python commands run — like a Linux terminal with a
 * character. The thinking lives in Python (the `tars` package) via Chaquopy;
 * this Activity shuttles text in and replies out, always off the UI thread.
 *
 *   plain text   → a message to TARS
 *   $ <command>  → a shell command in the app sandbox
 *   py <code>    → a line of Python
 *   · <line>     → a system log line (brain, voice, updater…)
 */
class MainActivity : AppCompatActivity() {

    // Fast Python ops only (respond, personality) — one thread so the shared TARS
    // state is never touched concurrently.
    private val worker = Executors.newSingleThreadExecutor()
    // Heavy, slow work on its OWN threads so it never blocks chat (the ~1 GB model
    // download used to stall everything on the worker).
    private val brainExec = Executors.newSingleThreadExecutor()
    private val termExec = Executors.newSingleThreadExecutor()
    private val voiceExec = Executors.newSingleThreadExecutor()
    private lateinit var bridge: PyObject

    // Full timestamped transcript, kept for "Copy log" even after lines scroll off.
    private val diag = StringBuilder()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var stream: TextView
    private lateinit var streamScroll: ScrollView

    private lateinit var speaker: Speaker

    // Canon HUD: status line, cue light, and a speaking VU meter.
    private lateinit var statusView: TextView
    private lateinit var cueView: View
    private val vuBars = ArrayList<View>()
    private val dials = intArrayOf(75, 90, 70)   // humor, honesty, discretion
    @Volatile private var state = "STANDBY"

    private val cyrillic = Regex("[А-Яа-яЁё]")

    /** Record a system log line: into the transcript AND the live stream (dimmed
     *  with a "· " marker so it reads apart from the conversation). */
    @Synchronized
    private fun log(line: String) {
        diag.append(clock.format(Date())).append("  ").append(line).append('\n')
        appendLine("· $line")
    }

    /** Append a raw line to the on-screen stream and keep it pinned to the bottom.
     *  Safe to call from any thread. */
    private fun appendLine(text: String) {
        ui.post {
            if (!::stream.isInitialized) return@post
            stream.append("\n$text")
            streamScroll.post { streamScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val input = findViewById<EditText>(R.id.input)
        val send = findViewById<Button>(R.id.send)
        val brainBtn = findViewById<Button>(R.id.brain)
        val voiceBtn = findViewById<Button>(R.id.voice)
        stream = findViewById(R.id.log)
        streamScroll = findViewById(R.id.scroll)

        statusView = findViewById(R.id.status)
        cueView = findViewById(R.id.cue)
        buildVu(findViewById(R.id.vu))
        setState("STANDBY")

        speaker = Speaker(this) { line -> log(line) }
        VoiceSettings.load(this)

        stream.text = getString(R.string.greeting)
        brainBtn.setOnClickListener { setUpLocalBrain() }
        voiceBtn.setOnClickListener {
            speaker.enabled = !speaker.enabled
            if (!speaker.enabled) { speaker.stop(); PiperVoice.stop() }
            voiceBtn.text = getString(if (speaker.enabled) R.string.voice else R.string.muted)
            log("voice: ${if (speaker.enabled) "on" else "muted"}")
        }
        voiceBtn.setOnLongClickListener { installPiperVoice(); true }
        log("voice: long-press Voice to install the deep TARS voices, RU + EN (~60 MB)")
        log("console: type to talk, or '\$ cmd' for shell, 'py code' for Python")

        worker.execute {
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(this))
                bridge = Python.getInstance().getModule("tars.android_bridge")
                bridge.callAttr("init", filesDir.absolutePath)
                log("core: started, memory at ${filesDir.absolutePath}")
            } catch (e: Exception) {
                log("core: failed to start — ${e.message}")
            }
            try {
                val p = bridge.callAttr("get_personality").toString().split("|")
                dials[0] = p[0].toInt(); dials[1] = p[1].toInt(); dials[2] = p[2].toInt()
                setState(state)
            } catch (e: Exception) { /* keep defaults */ }
            // If a local model is already downloaded, bring the on-device brain up
            // automatically so TARS thinks on-device from launch.
            brainExec.execute { autoStartLocalBrain() }
            PiperVoice.load(this) { line -> log(line) }
            Updater.checkAndPrompt(this) { line -> log(line) }
        }

        send.setOnClickListener { submit(input) }
        input.setOnEditorActionListener { _, _, _ -> submit(input); true }
    }

    /** Route one line from the input box: a shell/Python command, or a message to
     *  TARS. Everything echoes into the single stream. */
    private fun submit(input: EditText) {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")

        if (text.startsWith("$") || text.startsWith("py ")) {
            val cmd = if (text.startsWith("$")) text.removePrefix("$").trim() else text
            if (cmd.isEmpty()) return
            appendLine("\n$ $cmd")
            log("console: \$ $cmd")
            termExec.execute {
                val out = try {
                    bridge.callAttr("terminal", cmd).toString()
                } catch (e: Exception) { "error: ${e.message}" }
                appendLine(out.trimEnd())
            }
            return
        }

        appendLine("\nyou> $text")
        log("chat: sent \"${text.take(40)}\"")
        setState("THINKING")
        worker.execute {
            val reply = try {
                bridge.callAttr("respond", text).toString()
            } catch (e: Exception) {
                log("chat error: ${e.message}")
                "[error] ${e.message}"
            }
            val report = try { bridge.callAttr("last_brain_report").toString() } catch (e: Exception) { "?" }
            log("chat: $report")
            runOnUiThread {
                appendLine("TARS> $reply")
                if (!reply.startsWith("[error]")) speakReply(reply)
                setState("TALKING")
                val ms = (reply.length * 55L).coerceIn(1500L, 12000L)
                ui.postDelayed({ if (state == "TALKING") setState("STANDBY") }, ms)
            }
        }
    }

    /** Tapped by the user: download the recommended model (once) and start the
     *  on-device llama.cpp brain. The download is big, so it runs off the worker. */
    private fun setUpLocalBrain() {
        log("brain: setting up on-device engine…")
        brainExec.execute {
            try {
                if (!LlamaServer.isSupported(this)) {
                    log("brain: this device's CPU isn't supported for the local model (needs arm64). Offline mode only.")
                    return@execute
                }
                val info = bridge.callAttr("recommended_model").toString()
                if (info.isBlank()) {
                    log("brain: this device's RAM is too low for a local model — offline brain only.")
                    return@execute
                }
                val parts = info.split("|")           // id|filename|url|size_mb
                val filename = parts[1]
                val url = parts[2]
                val model = java.io.File(LlamaServer.modelsDir(this), filename)
                log("brain: model = ${parts[0]} (~${parts.getOrElse(3) { "?" }} MB)")
                LlamaServer.downloadModel(url, model) { line -> log(line) }
                LlamaServer.start(this, model) { line -> log(line) }
            } catch (e: Exception) {
                log("brain: setup failed — ${e.message}")
            }
        }
    }

    /** Start the local brain only if its model is already downloaded. */
    private fun autoStartLocalBrain() {
        try {
            if (!LlamaServer.isSupported(this)) return
            val info = bridge.callAttr("recommended_model").toString()
            if (info.isBlank()) return
            val filename = info.split("|")[1]
            val model = java.io.File(LlamaServer.modelsDir(this), filename)
            if (model.exists()) {
                LlamaServer.start(this, model) { line -> log(line) }
            } else {
                log("brain: tap 'Brain' to download the on-device model (one time).")
            }
        } catch (e: Exception) {
            log("brain: autostart check failed — ${e.message}")
        }
    }

    /** Speak a reply with the deep Piper voice for its language; fall back to
     *  system TTS if that voice isn't installed. */
    private fun speakReply(text: String) {
        if (!speaker.enabled) return
        val lang = if (cyrillic.containsMatchIn(text)) "ru" else "en"
        if (PiperVoice.isReady(lang)) {
            voiceExec.execute {
                val ok = PiperVoice.speak(text, lang) { l -> log(l) }
                if (!ok) runOnUiThread { speaker.speak(text) }
            }
        } else {
            speaker.speak(text)
        }
    }

    /** Long-press on Voice: download + install the deep Piper voices (one time,
     *  RU + EN). Idempotent — installs only what's missing. */
    private fun installPiperVoice() {
        log("voice: installing TARS voices (RU + EN)…")
        voiceExec.execute {
            if (::bridge.isInitialized) {
                PiperVoice.install(this, bridge) { l -> log(l) }
            } else {
                log("voice: core still starting — try again in a moment")
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Personality")
        menu.add(0, 4, 1, "Voice")
        menu.add(0, 2, 2, "Diagnostics")
        menu.add(0, 3, 3, getString(R.string.copy))
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> { showDials(); return true }
            4 -> { showVoiceBench(); return true }
            2 -> { showDiagnostics(); return true }
            3 -> { copyLog(); return true }
        }
        return super.onOptionsItemSelected(item)
    }

    /** The voice bench: live Depth / Pace / Grit sliders that shape TARS's
     *  delivery (the audio twin of the personality dials). Changes apply live and
     *  persist; releasing a slider speaks a short test line so you hear it. */
    private fun showVoiceBench() {
        val names = listOf("Depth", "Pace", "Grit")
        val cur = intArrayOf(VoiceSettings.depth, VoiceSettings.pace, VoiceSettings.grit)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        fun commit(test: Boolean) {
            VoiceSettings.depth = cur[0]; VoiceSettings.pace = cur[1]; VoiceSettings.grit = cur[2]
            VoiceSettings.save(this)
            speaker.applyTuning()
            log("voice: depth=${cur[0]} pace=${cur[1]} grit=${cur[2]}")
            if (test) speakReply("Голос настроен. Это TARS.")
        }
        for (i in 0 until 3) {
            val label = TextView(this).apply { text = "${names[i]}: ${cur[i]}%"; textSize = 14f }
            val bar = SeekBar(this).apply {
                max = 100
                progress = cur[i]
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                        cur[i] = value
                        label.text = "${names[i]}: $value%"
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) { commit(true) }
                })
            }
            root.addView(label)
            root.addView(bar)
        }
        AlertDialog.Builder(this)
            .setTitle("TARS — voice")
            .setView(ScrollView(this).apply { addView(root) })
            .setPositiveButton("Done") { _, _ -> commit(false) }
            .show()
    }

    /** Pull a fresh core status report into the stream (own thread so it shows
     *  even mid-download). */
    private fun showDiagnostics() {
        log("diagnostics: requested")
        Thread {
            val report = try {
                if (::bridge.isInitialized) bridge.callAttr("diagnostics").toString()
                else "(core still starting…)"
            } catch (e: Exception) { "diagnostics unavailable: ${e.message}" }
            appendLine("\n--- diagnostics ---\n$report\n-------------------")
        }.start()
    }

    private fun copyLog() {
        val body = synchronized(this) { diag.toString() }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("TARS log", body))
        log("log: copied to clipboard")
    }

    /** The TARS "settings bench": live sliders for Humor / Honesty / Discretion /
     *  Sarcasm, tuned exactly like Cooper tunes him in the film. */
    private fun showDials() {
        worker.execute {
            val raw = try { bridge.callAttr("get_personality").toString() } catch (e: Exception) { "75|90|70|30" }
            val v = raw.split("|").map { it.toIntOrNull() ?: 50 }
            runOnUiThread { buildDialsDialog(v) }
        }
    }

    private fun buildDialsDialog(initial: List<Int>) {
        val names = listOf("Humor", "Honesty", "Discretion", "Sarcasm")
        val cur = IntArray(4) { initial.getOrElse(it) { 50 } }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        for (i in 0 until 4) {
            val label = TextView(this).apply { text = "${names[i]}: ${cur[i]}%"; textSize = 14f }
            val bar = SeekBar(this).apply {
                max = 100
                progress = cur[i]
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                        cur[i] = value
                        label.text = "${names[i]}: $value%"
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) { applyDials(cur) }
                })
            }
            root.addView(label)
            root.addView(bar)
        }
        AlertDialog.Builder(this)
            .setTitle("TARS — settings")
            .setView(ScrollView(this).apply { addView(root) })
            .setPositiveButton("Done") { _, _ -> applyDials(cur) }
            .show()
    }

    private fun applyDials(v: IntArray) {
        dials[0] = v[0]; dials[1] = v[1]; dials[2] = v[2]
        setState(state)
        worker.execute {
            val msg = try {
                bridge.callAttr("set_personality", v[0], v[1], v[2], v[3]).toString()
            } catch (e: Exception) { "error: ${e.message}" }
            log("settings: $msg")
        }
    }

    // ---- Canon HUD: status line, cue light, speaking VU meter ----

    private fun buildVu(vu: LinearLayout) {
        vuBars.clear()
        repeat(20) {
            val bar = View(this)
            bar.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                .apply { marginEnd = 4 }
            bar.setBackgroundColor(0xFF7A5A00.toInt())
            bar.scaleY = 0.08f
            vu.addView(bar)
            vuBars.add(bar)
        }
    }

    private fun setState(s: String) {
        state = s
        runOnUiThread {
            statusView.text = "TARS · $s · H${dials[0]} HON${dials[1]} D${dials[2]}"
            cueView.setBackgroundColor(if (s == "TALKING") 0xFFFFB000.toInt() else 0xFF3A2A00.toInt())
            if (s == "TALKING") startVu() else stopVu()
        }
    }

    private val vuTick = object : Runnable {
        override fun run() {
            if (state != "TALKING") return
            for (b in vuBars) {
                b.pivotY = b.height.toFloat()
                b.scaleY = 0.12f + Math.random().toFloat() * 0.88f
            }
            ui.postDelayed(this, 90)
        }
    }

    private fun startVu() { ui.removeCallbacks(vuTick); ui.post(vuTick) }

    private fun stopVu() {
        ui.removeCallbacks(vuTick)
        for (b in vuBars) { b.pivotY = b.height.toFloat(); b.scaleY = 0.08f }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        if (::speaker.isInitialized) speaker.shutdown()
        PiperVoice.stop()
        LlamaServer.stop()
        worker.shutdownNow()
        brainExec.shutdownNow()
        termExec.shutdownNow()
        voiceExec.shutdownNow()
        super.onDestroy()
    }
}
