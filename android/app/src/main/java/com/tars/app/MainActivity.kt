package com.tars.app

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.util.concurrent.Executors

/**
 * Minimal chat UI. The thinking lives in Python (the `tars` package) via
 * Chaquopy; this Activity just shuttles text in and replies out, off the UI
 * thread so the model call never freezes the screen.
 */
class MainActivity : AppCompatActivity() {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var bridge: PyObject

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        bridge = Python.getInstance().getModule("tars.android_bridge")
        bridge.callAttr("init", filesDir.absolutePath)

        val input = findViewById<EditText>(R.id.input)
        val send = findViewById<Button>(R.id.send)
        val log = findViewById<TextView>(R.id.log)
        val scroll = findViewById<ScrollView>(R.id.scroll)

        log.text = getString(R.string.greeting)

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
                    "[error] ${e.message}"
                }
                runOnUiThread {
                    log.append("\nTARS> $reply")
                    scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
