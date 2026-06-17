package com.tars.app

import android.app.AlertDialog
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.EditText
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
    private lateinit var bridge: PyObject

    private val diag = StringBuilder()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

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
        val log = findViewById<TextView>(R.id.log)
        val scroll = findViewById<ScrollView>(R.id.scroll)

        log.text = getString(R.string.greeting)
        logsBtn.setOnClickListener { showLogs() }
        brainBtn.setOnClickListener { setUpLocalBrain() }

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
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }

            worker.execute {
                val reply = try {
                    bridge.callAttr("respond", text).toString()
                } catch (e: Exception) {
                    log("chat error: ${e.message}")
                    "[error] ${e.message}"
                }
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

    private fun showLogs() {
        // Build the report off the UI thread (the Python call may block briefly),
        // then show the dialog.
        worker.execute {
            val core = try {
                bridge.callAttr("diagnostics").toString()
            } catch (e: Exception) {
                "diagnostics unavailable: ${e.message}"
            }
            val body = synchronized(this) {
                "--- core ---\n$core\n\n--- log ---\n$diag"
            }
            runOnUiThread {
                val view = TextView(this).apply {
                    text = body
                    textSize = 12f
                    setPadding(40, 30, 40, 30)
                    movementMethod = ScrollingMovementMethod()
                    setTextIsSelectable(true)
                }
                AlertDialog.Builder(this)
                    .setTitle("TARS — logs")
                    .setView(ScrollView(this@MainActivity).apply { addView(view) })
                    .setPositiveButton("Close", null)
                    .show()
            }
        }
    }

    override fun onDestroy() {
        LlamaServer.stop()
        worker.shutdownNow()
        super.onDestroy()
    }
}
