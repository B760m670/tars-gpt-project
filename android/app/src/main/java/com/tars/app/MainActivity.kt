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
        val log = findViewById<TextView>(R.id.log)
        val scroll = findViewById<ScrollView>(R.id.scroll)

        log.text = getString(R.string.greeting)
        logsBtn.setOnClickListener { showLogs() }

        worker.execute {
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(this))
                bridge = Python.getInstance().getModule("tars.android_bridge")
                bridge.callAttr("init", filesDir.absolutePath)
                log("core: started, memory at ${filesDir.absolutePath}")
            } catch (e: Exception) {
                log("core: failed to start — ${e.message}")
            }
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
        worker.shutdownNow()
        super.onDestroy()
    }
}
