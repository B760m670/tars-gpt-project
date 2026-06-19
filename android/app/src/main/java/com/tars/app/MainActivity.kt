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
import androidx.core.content.ContextCompat
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
        val micBtn = findViewById<Button>(R.id.mic)
        val eyeBtn = findViewById<Button>(R.id.eye)
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
        micBtn.setOnClickListener { toggleEars() }
        micBtn.setOnLongClickListener { installEars(); true }
        eyeBtn.setOnClickListener { toggleVision() }
        eyeBtn.setOnLongClickListener { Vision.switchLens(this) { l -> log(l) }; true }
        log("voice: long-press Voice to install the deep TARS voices, RU + EN (~60 MB)")
        log("ears: long-press Mic to install speech recognition, then say \"Hey TARS\"")
        log("vision: tap Eye to let TARS see; long-press Eye to flip camera")
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
            // If the speech models are already installed, start hands-free
            // listening so "Hey TARS" works from launch.
            Ears.load(this) { line -> log(line) }
            if (Ears.isReady() && hasMicPermission()) {
                Ears.start(this, { heard -> onHeard(heard) }) { line -> log(line) }
            }
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
        converse(text)
    }

    /** Send one utterance (typed or heard) to the brain, show + speak the reply. */
    private fun converse(text: String) {
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
                lastReplyAt = System.currentTimeMillis()
                val ms = (reply.length * 55L).coerceIn(1500L, 12000L)
                ui.postDelayed({ if (state == "TALKING") setState("STANDBY") }, ms)
            }
        }
    }

    // ---- Ears: hands-free "Hey TARS" speech input ----

    // After TARS answers, stay open for follow-ups for a short window without
    // needing the wake word again — like a real back-and-forth.
    @Volatile private var lastReplyAt = 0L
    private val wakeWord = Regex("(?i)\\b(hey[ ,]+)?(tars|тарс|тарз)\\b")

    /** A transcribed utterance from the mic. Respond only if addressed to TARS:
     *  the wake word was spoken, or we're still inside the follow-up window. */
    private fun onHeard(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        log("ears: heard \"${clean.take(60)}\"")
        val match = wakeWord.find(clean)
        val withinWindow = System.currentTimeMillis() - lastReplyAt < 20000
        val command = when {
            match != null -> clean.removeRange(match.range).trim().trim(',', '.', '!', '?', ' ')
            withinWindow -> clean
            else -> return   // overheard speech, not for TARS
        }
        runOnUiThread { appendLine("\nyou 🎙 $clean") }
        if (command.isBlank()) {
            // Just "Hey TARS" with nothing else — acknowledge and open the window.
            lastReplyAt = System.currentTimeMillis()
            runOnUiThread {
                appendLine("TARS> Слушаю.")
                speakReply("Слушаю.")
                setState("TALKING")
                ui.postDelayed({ if (state == "TALKING") setState("STANDBY") }, 1500)
            }
            return
        }
        // If he's watching and you ask about what he sees, answer from the camera.
        if (Vision.isOn() && visionQuestion.containsMatchIn(command)) {
            doGlance(command)
            return
        }
        converse(command)
    }

    private val visionQuestion =
        Regex("(?i)(виж|вид|смотр|камер|перед тобой|see|look|camera|in front)")

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Toggle hands-free listening (requesting the mic permission if needed). */
    private fun toggleEars() {
        if (Ears.isListening()) {
            Ears.stop(); log("ears: stopped listening")
            return
        }
        if (!hasMicPermission()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (!Ears.isReady()) {
            log("ears: no speech model yet — long-press Mic to install (~40 MB)")
            return
        }
        Ears.start(this, { heard -> onHeard(heard) }) { line -> log(line) }
    }

    /** Long-press Mic: download + install the speech models (one time). */
    private fun installEars() {
        log("ears: installing speech recognition (RU + EN)…")
        voiceExec.execute {
            if (::bridge.isInitialized) {
                Ears.install(this, bridge) { l -> log(l) }
                if (Ears.isReady() && hasMicPermission()) {
                    Ears.start(this, { heard -> onHeard(heard) }) { line -> log(line) }
                }
            } else {
                log("ears: core still starting — try again in a moment")
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_MIC -> if (granted) {
                log("ears: mic permission granted")
                if (Ears.isReady()) Ears.start(this, { heard -> onHeard(heard) }) { line -> log(line) }
                else installEars()
            } else log("ears: mic permission denied — voice input off")
            REQ_CAM -> if (granted) { log("vision: camera permission granted"); startVision() }
            else log("vision: camera permission denied — TARS can't see")
        }
    }

    // ---- Vision: TARS sees through the camera and comments on what he sees ----

    @Volatile private var visionOn = false
    private val glanceEvery = 30_000L   // a "glance" at most this often

    private fun hasCamPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun toggleVision() {
        if (Vision.isOn()) {
            visionOn = false
            ui.removeCallbacks(glanceTick)
            Vision.stop(this) { l -> log(l) }
            return
        }
        if (!hasCamPermission()) { requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAM); return }
        if (!LlamaServer.isRunning()) {
            log("vision: start the brain first (tap Brain) — the eyes need the vision model")
            return
        }
        startVision()
    }

    private fun startVision() {
        Vision.start(this) { l -> log(l) }
        visionOn = true
        ui.removeCallbacks(glanceTick)
        ui.postDelayed(glanceTick, 4000)   // first look shortly after the camera warms up
    }

    /** Periodic unprompted "glance": TARS looks and comments only if he feels like
     *  it (the model answers "…" when nothing's worth a remark). */
    private val glanceTick = object : Runnable {
        override fun run() {
            if (!visionOn) return
            doGlance("Коротко прокомментируй, что видишь, в характере TARS. " +
                "Если ничего интересного — ответь только «…».")
            ui.postDelayed(this, glanceEvery)
        }
    }

    /** Ask the vision model about the current frame; speak the comment if any. */
    private fun doGlance(ask: String) {
        if (!Vision.isOn()) return
        voiceExec.execute {
            val sys = try { bridge.callAttr("system_prompt").toString() } catch (e: Exception) { "You are TARS." }
            val comment = Vision.glance(sys, ask) { l -> log(l) }
            if (comment.isNotBlank()) {
                runOnUiThread {
                    appendLine("TARS 👁 $comment")
                    speakReply(comment)
                    setState("TALKING")
                    lastReplyAt = System.currentTimeMillis()
                    val ms = (comment.length * 55L).coerceIn(1500L, 12000L)
                    ui.postDelayed({ if (state == "TALKING") setState("STANDBY") }, ms)
                }
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
                // id|filename|url|size_mb|mmproj_filename|mmproj_url|mmproj_mb
                val parts = info.split("|")
                val model = java.io.File(LlamaServer.modelsDir(this), parts[1])
                log("brain: model = ${parts[0]} (~${parts.getOrElse(3) { "?" }} MB)")
                LlamaServer.downloadModel(parts[2], model) { line -> log(line) }
                val mmproj = downloadMmprojIfAny(parts)
                LlamaServer.start(this, model, mmproj) { line -> log(line) }
            } catch (e: Exception) {
                log("brain: setup failed — ${e.message}")
            }
        }
    }

    /** Download the vision projector (mmproj) for a multimodal model, if the
     *  recommendation includes one. Returns the file, or null for a text model. */
    private fun downloadMmprojIfAny(parts: List<String>): java.io.File? {
        val name = parts.getOrElse(4) { "" }
        val url = parts.getOrElse(5) { "" }
        if (name.isBlank() || url.isBlank()) return null
        val f = java.io.File(LlamaServer.modelsDir(this), name)
        log("brain: vision projector ~${parts.getOrElse(6) { "?" }} MB")
        LlamaServer.downloadModel(url, f) { line -> log(line) }
        return f
    }

    /** Start the local brain only if its model is already downloaded. */
    private fun autoStartLocalBrain() {
        try {
            if (!LlamaServer.isSupported(this)) return
            val info = bridge.callAttr("recommended_model").toString()
            if (info.isBlank()) return
            val parts = info.split("|")
            val model = java.io.File(LlamaServer.modelsDir(this), parts[1])
            if (model.exists()) {
                val mmprojName = parts.getOrElse(4) { "" }
                val mmproj = if (mmprojName.isNotBlank())
                    java.io.File(LlamaServer.modelsDir(this), mmprojName).takeIf { it.exists() } else null
                LlamaServer.start(this, model, mmproj) { line -> log(line) }
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
        Ears.stop()
        if (Vision.isOn()) Vision.stop(this) { }
        PiperVoice.stop()
        LlamaServer.stop()
        worker.shutdownNow()
        brainExec.shutdownNow()
        termExec.shutdownNow()
        voiceExec.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val REQ_MIC = 101
        private const val REQ_CAM = 102
    }
}
