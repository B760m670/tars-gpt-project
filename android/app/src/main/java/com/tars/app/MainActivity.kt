package com.tars.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
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
 * Minimal chat UI. The thinking lives in Python (the `tars` package) via
 * Chaquopy; this Activity shuttles text in and replies out, off the UI thread.
 * A Logs button shows diagnostics, and on launch TARS checks for a newer build.
 */
class MainActivity : AppCompatActivity() {

    // Fast Python ops only (respond, keys, personality) — kept on one thread so
    // the shared TARS state is never touched concurrently.
    private val worker = Executors.newSingleThreadExecutor()
    // Heavy, slow work on its OWN threads so it never blocks chat or key-apply
    // (the 1 GB model download used to stall everything on the worker).
    private val brainExec = Executors.newSingleThreadExecutor()
    private val termExec = Executors.newSingleThreadExecutor()
    private val voiceExec = Executors.newSingleThreadExecutor()
    private lateinit var bridge: PyObject

    private val diag = StringBuilder()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val ui = Handler(Looper.getMainLooper())
    private var logsView: TextView? = null      // non-null while the Logs dialog is open
    @Volatile private var lastCore = "(loading…)"

    private lateinit var speaker: Speaker

    // Canon HUD: status line, cue light, and a speaking VU meter.
    private lateinit var statusView: TextView
    private lateinit var cueView: View
    private val vuBars = ArrayList<View>()
    private val dials = intArrayOf(75, 90, 70)   // humor, honesty, discretion
    @Volatile private var state = "STANDBY"

    @Synchronized
    private fun log(line: String) {
        diag.append(clock.format(Date())).append("  ").append(line).append('\n')
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val input = findViewById<EditText>(R.id.input)
        val send = findViewById<Button>(R.id.send)
        val logsBtn = findViewById<Button>(R.id.logs)
        val brainBtn = findViewById<Button>(R.id.brain)
        val terminalBtn = findViewById<Button>(R.id.terminal)
        val voiceBtn = findViewById<Button>(R.id.voice)
        val keyBtn = findViewById<Button>(R.id.key)
        val log = findViewById<TextView>(R.id.log)
        val scroll = findViewById<ScrollView>(R.id.scroll)

        statusView = findViewById(R.id.status)
        cueView = findViewById(R.id.cue)
        buildVu(findViewById(R.id.vu))
        setState("STANDBY")

        speaker = Speaker(this) { line -> log(line) }

        log.text = getString(R.string.greeting)
        logsBtn.setOnClickListener { showLogs() }
        brainBtn.setOnClickListener { setUpLocalBrain() }
        terminalBtn.setOnClickListener { showTerminal() }
        voiceBtn.setOnClickListener {
            speaker.enabled = !speaker.enabled
            if (!speaker.enabled) { speaker.stop(); PiperVoice.stop() }
            voiceBtn.text = getString(if (speaker.enabled) R.string.voice else R.string.muted)
            log("voice: ${if (speaker.enabled) "on" else "muted"}")
        }
        voiceBtn.setOnLongClickListener { installPiperVoice(); true }
        keyBtn.setOnClickListener { showKeyDialog() }
        log("voice: long-press Voice to install the deep TARS voices, RU + EN (~60 MB)")

        worker.execute {
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(this))
                bridge = Python.getInstance().getModule("tars.android_bridge")
                bridge.callAttr("init", filesDir.absolutePath)
                log("core: started, memory at ${filesDir.absolutePath}")
            } catch (e: Exception) {
                log("core: failed to start — ${e.message}")
            }
            // If a local model is already downloaded, bring the on-device brain
            // up automatically so TARS thinks offline from launch.
            // Apply any saved cloud keys so the smart brain is on from launch.
            applySavedKeys()
            try {
                val p = bridge.callAttr("get_personality").toString().split("|")
                dials[0] = p[0].toInt(); dials[1] = p[1].toInt(); dials[2] = p[2].toInt()
                setState(state)
            } catch (e: Exception) { /* keep defaults */ }
            brainExec.execute { autoStartLocalBrain() }
            // Load the Piper voice if it's already installed.
            PiperVoice.load(this) { line -> log(line) }
            // Check for a newer build in the background.
            Updater.checkAndPrompt(this) { line -> log(line) }
        }

        send.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            input.setText("")
            log.append("\n\nyou> $text")
            log("chat: sent \"${text.take(40)}\"")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
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
                    log.append("\nTARS> $reply")
                    scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                    if (!reply.startsWith("[error]")) speakReply(reply)
                    setState("TALKING")
                    val ms = (reply.length * 55L).coerceIn(1500L, 12000L)
                    ui.postDelayed({ if (state == "TALKING") setState("STANDBY") }, ms)
                }
            }
        }
    }

    /** Tapped by the user: download the recommended model (once) and start the
     *  on-device llama.cpp brain. Runs on the worker thread; the download is big. */
    private fun setUpLocalBrain() {
        log("brain: setting up on-device engine…")
        brainExec.execute {
            try {
                if (!LlamaServer.isSupported(this)) {
                    log("brain: no on-device engine for this CPU (need arm64). Use a key or offline.")
                    return@execute
                }
                val info = bridge.callAttr("recommended_model").toString()
                if (info.isBlank()) {
                    log("brain: this device's RAM is too low for a local model — cloud/offline only.")
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

    /** Open the logs window IMMEDIATELY (on the UI thread, never queued behind a
     *  download or a reply) and keep it refreshing live so downloads and actions
     *  show up as they happen. */
    private fun showLogs() {
        log("logs: opened")
        val view = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(40, 30, 40, 20)
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
        }
        logsView = view
        refreshLogsView()

        val copyBtn = Button(this).apply { text = getString(R.string.copy) }
        copyBtn.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("TARS logs", currentLogBody()))
            log("logs: copied to clipboard")
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                ScrollView(this@MainActivity).apply { addView(view) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )
            addView(copyBtn)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("TARS — logs")
            .setView(root)
            .setPositiveButton("Close", null)
            .create()
        dialog.setOnDismissListener { logsView = null }
        dialog.show()

        // Live refresh while the dialog is open. Only rewrite the text when it
        // actually changed, so a long-press text selection isn't wiped every tick.
        ui.postDelayed(object : Runnable {
            override fun run() {
                if (logsView !== view) return   // dialog closed (or replaced)
                refreshLogsView()
                ui.postDelayed(this, 600)
            }
        }, 600)

        // Fetch core diagnostics without blocking the window (own thread, not the
        // shared worker, so it shows even mid-download).
        Thread {
            lastCore = try {
                if (::bridge.isInitialized) bridge.callAttr("diagnostics").toString()
                else "(core still starting…)"
            } catch (e: Exception) {
                "diagnostics unavailable: ${e.message}"
            }
        }.start()
    }

    private fun currentLogBody(): String =
        synchronized(this) { "--- core ---\n$lastCore\n\n--- log ---\n$diag" }

    private fun refreshLogsView() {
        val body = currentLogBody()
        val view = logsView ?: return
        if (view.text?.toString() != body) view.text = body   // avoid wiping a selection
    }

    /** A TARS terminal: type a shell command, or `py <code>` for Python. Runs in
     *  the app's sandbox (no root) via the Python bridge. */
    private val cyrillic = Regex("[А-Яа-яЁё]")

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
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) { showDials(); return true }
        return super.onOptionsItemSelected(item)
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

    private fun prefs() = getSharedPreferences("tars", MODE_PRIVATE)

    /** Push saved cloud keys into the Python brain (call on the worker thread). */
    private fun applySavedKeys() {
        val gk = prefs().getString("gemini", "") ?: ""
        val qk = prefs().getString("groq", "") ?: ""
        if (gk.isBlank() && qk.isBlank()) return
        if (!::bridge.isInitialized) return
        val avail = try { bridge.callAttr("set_keys", gk, qk).toString() } catch (e: Exception) { "?" }
        log("keys: applied saved key(s); brains available: $avail")
    }

    /** Enter a free Gemini/Groq key so the smart cloud brain comes online. */
    private fun showKeyDialog() {
        val p = prefs()
        val info = TextView(this).apply {
            text = "Paste a FREE key to make TARS smart & fast (kept only on this device):\n" +
                "• Groq:   console.groq.com/keys\n" +
                "• Gemini: aistudio.google.com/apikey"
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }
        val groq = EditText(this).apply {
            hint = getString(R.string.key_groq_hint)
            setText(p.getString("groq", ""))
            setSingleLine(true)
        }
        val gemini = EditText(this).apply {
            hint = getString(R.string.key_gemini_hint)
            setText(p.getString("gemini", ""))
            setSingleLine(true)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(info); addView(groq); addView(gemini)
        }
        AlertDialog.Builder(this)
            .setTitle("TARS — API key")
            .setView(root)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val qk = groq.text.toString().trim()
                val gk = gemini.text.toString().trim()
                p.edit().putString("groq", qk).putString("gemini", gk).apply()
                log("keys: saved")
                worker.execute {
                    val avail = try {
                        bridge.callAttr("set_keys", gk, qk).toString()
                    } catch (e: Exception) { "error: ${e.message}" }
                    log("keys: brains available now: $avail")
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showTerminal() {
        log("terminal: opened")
        val output = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(24, 16, 24, 16)
            text = "TARS terminal — sandbox shell. Type a command, or 'py <code>' for Python.\n"
        }
        val scroll = ScrollView(this).apply { addView(output) }
        val input = EditText(this).apply {
            hint = getString(R.string.terminal_hint)
            setSingleLine(true)
        }
        val runBtn = Button(this).apply { text = getString(R.string.run) }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(runBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(row)
        }

        fun submit() {
            val cmd = input.text.toString().trim()
            if (cmd.isEmpty()) return
            input.setText("")
            output.append("\n$ $cmd\n")
            log("terminal: $ $cmd")
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            termExec.execute {
                val out = try {
                    bridge.callAttr("terminal", cmd).toString()
                } catch (e: Exception) {
                    "error: ${e.message}"
                }
                runOnUiThread {
                    output.append(out.trimEnd() + "\n")
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
        runBtn.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, _, _ -> submit(); true }

        AlertDialog.Builder(this)
            .setTitle("TARS — terminal")
            .setView(rootView)
            .setPositiveButton("Close", null)
            .show()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        logsView = null
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
