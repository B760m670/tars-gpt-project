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
 * commands; TARS answers in the same stream. The mind is a pluggable [Brain] —
 * currently a stub, next a pool of free cloud providers with auto-failover.
 */
class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var stream: TextView
    private lateinit var streamScroll: ScrollView
    private lateinit var statusView: TextView

    private val brain: Brain = StubBrain()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        stream = findViewById(R.id.log)
        streamScroll = findViewById(R.id.scroll)
        statusView = findViewById(R.id.status)
        stream.movementMethod = ScrollingMovementMethod()

        findViewById<Button>(R.id.send).setOnClickListener { submit() }
        findViewById<EditText>(R.id.input).setOnEditorActionListener { _, _, _ -> submit(); true }

        setStatus("BOOT")
        boot()
    }

    /** Green boot log — pure flavour, sets the TARS terminal tone. */
    private fun boot() {
        val lines = listOf(
            "TARS SYSTEM // COSMOS-1A",
            "initializing core ............. ok",
            "personality matrix ........... loaded",
            "uplink: cloud brain pool ..... not configured",
            "",
            "TARS online. No cloud providers wired yet — running on a stub mind.",
            "Type anything to test the terminal.",
        )
        var delay = 120L
        for (line in lines) {
            ui.postDelayed({ emit(line) }, delay)
            delay += 90
        }
        ui.postDelayed({ setStatus("STUB") }, delay)
    }

    private fun submit() {
        val input = findViewById<EditText>(R.id.input)
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        emit("> $text")
        work.execute {
            val reply = brain.reply(text)
            emit("TARS> $reply")
        }
    }

    // ---- terminal output ----

    /** Append a line to the screen. Safe to call from any thread. */
    private fun emit(line: String) = ui.post {
        if (stream.text.isNotEmpty()) stream.append("\n")
        stream.append(line)
        streamScroll.post { streamScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun setStatus(mode: String) = ui.post {
        statusView.text = "TARS · $mode · ${clock.format(Date())}"
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        work.shutdownNow()
        super.onDestroy()
    }
}
