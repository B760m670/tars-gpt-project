package com.tars.app

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
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

    private val worker = Executors.newSingleThreadExecutor()
    // Terminal commands run on their own thread so a long command (or a model
    // download on the worker) never blocks the other.
    private val termExec = Executors.newSingleThreadExecutor()
    private lateinit var bridge: PyObject

    private val diag = StringBuilder()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val ui = Handler(Looper.getMainLooper())
    private var logsView: TextView? = null      // non-null while the Logs dialog is open
    @Volatile private var lastCore = "(loading…)"

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
        val log = findViewById<TextView>(R.id.log)
        val scroll = findViewById<ScrollView>(R.id.scroll)

        log.text = getString(R.string.greeting)
        logsBtn.setOnClickListener { showLogs() }
        brainBtn.setOnClickListener { setUpLocalBrain() }
        terminalBtn.setOnClickListener { showTerminal() }

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
            autoStartLocalBrain()
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

            worker.execute {
                val reply = try {
                    bridge.callAttr("respond", text).toString()
                } catch (e: Exception) {
                    log("chat error: ${e.message}")
                    "[error] ${e.message}"
                }
                val brain = try { bridge.callAttr("active_brain").toString() } catch (e: Exception) { "?" }
                log("chat: reply via $brain")
                runOnUiThread {
                    log.append("\nTARS> $reply")
                    scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        }
    }

    /** Tapped by the user: download the recommended model (once) and start the
     *  on-device llama.cpp brain. Runs on the worker thread; the download is big. */
    private fun setUpLocalBrain() {
        log("brain: setting up on-device engine…")
        worker.execute {
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
            setPadding(40, 30, 40, 30)
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
        }
        logsView = view
        refreshLogsView()

        val dialog = AlertDialog.Builder(this)
            .setTitle("TARS — logs")
            .setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton("Close", null)
            .create()
        dialog.setOnDismissListener { logsView = null }
        dialog.show()

        // Live refresh while the dialog is open.
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

    private fun refreshLogsView() {
        val body = synchronized(this) { "--- core ---\n$lastCore\n\n--- log ---\n$diag" }
        logsView?.text = body
    }

    /** A TARS terminal: type a shell command, or `py <code>` for Python. Runs in
     *  the app's sandbox (no root) via the Python bridge. */
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
        LlamaServer.stop()
        worker.shutdownNow()
        termExec.shutdownNow()
        super.onDestroy()
    }
}
