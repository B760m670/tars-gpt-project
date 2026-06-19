package com.tars.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.chaquo.python.PyObject
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * TARS's ears: offline speech-to-text, on-device, via sherpa-onnx (the same
 * engine that already does his voice). The mic is read continuously; a Silero
 * VAD finds where someone actually speaks, and each spoken segment is transcribed
 * by a multilingual Whisper model (Russian + English). The transcript is handed
 * back to the app, which decides whether it was meant for TARS ("Hey TARS").
 *
 *   mic (AudioRecord) -> VAD (speech segments) -> Whisper ASR -> text
 */
object Ears {

    private const val SAMPLE_RATE = 16000
    private const val VAD_WINDOW = 512   // Silero processes 512-sample windows

    // Multilingual Whisper-tiny (RU + EN) and the Silero VAD, from sherpa-onnx's
    // model releases. Whisper-tiny is the light end — fast on a phone CPU; we can
    // move to "base" later for more accuracy.
    private const val ASR_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2"
    private const val VAD_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"

    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var listening = false
    private var thread: Thread? = null

    private fun root(ctx: Context): File = File(ctx.filesDir, "ears").apply { mkdirs() }
    private fun marker(ctx: Context): File = File(root(ctx), "paths.txt")
    private fun vadModel(ctx: Context): File = File(root(ctx), "silero_vad.onnx")

    fun isReady(): Boolean = recognizer != null

    fun isListening(): Boolean = listening

    /** Rebuild the recognizer from already-downloaded models (fast path on launch). */
    fun load(ctx: Context, log: (String) -> Unit) {
        if (recognizer != null) return
        val m = marker(ctx)
        if (!m.exists() || !vadModel(ctx).exists()) return
        try {
            val (enc, dec, tok) = m.readText().split("|").let {
                Triple(it[0], it.getOrElse(1) { "" }, it.getOrElse(2) { "" })
            }
            if (File(enc).exists() && File(dec).exists() && File(tok).exists()) {
                recognizer = buildRecognizer(enc, dec, tok)
                log("ears: speech recognition ready")
            }
        } catch (e: Exception) {
            log("ears: failed to load ASR — ${e.message}")
        }
    }

    /** Download + unpack the ASR + VAD models (one time, ~100 MB). Heavy — off UI. */
    fun install(ctx: Context, bridge: PyObject, log: (String) -> Unit) {
        try {
            System.loadLibrary("sherpa-onnx-jni")
        } catch (e: Throwable) { /* AAR usually self-loads */ }
        try {
            if (recognizer == null) {
                val archive = File(root(ctx), "asr.tar.bz2")
                if (!archive.exists() || archive.length() == 0L) {
                    log("ears: downloading speech model (~40 MB)…")
                    download(ASR_URL, archive, log)
                }
                log("ears: unpacking speech model…")
                val dir = bridge.callAttr("extract_archive", archive.absolutePath, root(ctx).absolutePath).toString()
                val enc = pick(dir, "encoder")
                val dec = pick(dir, "decoder")
                val tok = pick(dir, "tokens")
                if (enc.isBlank() || dec.isBlank() || tok.isBlank()) {
                    log("ears: speech model incomplete (encoder/decoder/tokens missing)")
                    return
                }
                if (!vadModel(ctx).exists()) {
                    log("ears: downloading voice detector…")
                    download(VAD_URL, vadModel(ctx), log)
                }
                recognizer = buildRecognizer(enc, dec, tok)
                marker(ctx).writeText("$enc|$dec|$tok")
                archive.delete()
                log("ears: TARS can hear now. Say \"Hey TARS\".")
            }
        } catch (e: Exception) {
            log("ears: install failed — ${e.message}")
        }
    }

    /** Pick a file inside dir whose name contains [needle] (prefer the int8 one
     *  for the model files — smaller and faster). */
    private fun pick(dir: String, needle: String): String {
        val files = File(dir).listFiles()?.filter {
            it.name.contains(needle) && it.name.endsWith(if (needle == "tokens") ".txt" else ".onnx")
        } ?: return ""
        if (files.isEmpty()) return ""
        return (files.firstOrNull { it.name.contains("int8") } ?: files.first()).absolutePath
    }

    private fun buildRecognizer(enc: String, dec: String, tokens: String): OfflineRecognizer {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(encoder = enc, decoder = dec, task = "transcribe"),
                tokens = tokens,
                numThreads = 2,
                modelType = "whisper",
            ),
        )
        return OfflineRecognizer(config = config)
    }

    /** Start continuous listening. [onHeard] is called (off the UI thread) with
     *  each transcribed utterance. No-op if already listening or not installed. */
    fun start(ctx: Context, onHeard: (String) -> Unit, log: (String) -> Unit) {
        val rec = recognizer ?: run { log("ears: no speech model yet — long-press the mic to install"); return }
        if (listening) return
        val vadFile = vadModel(ctx)
        if (!vadFile.exists()) { log("ears: voice detector missing"); return }
        listening = true
        thread = Thread {
            var record: AudioRecord? = null
            var vad: Vad? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT
                ).coerceAtLeast(SAMPLE_RATE * 2)
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT, minBuf
                )
                vad = Vad(
                    config = VadModelConfig(
                        sileroVadModelConfig = SileroVadModelConfig(
                            model = vadFile.absolutePath,
                            threshold = 0.5f,
                            minSilenceDuration = 0.4f,
                            minSpeechDuration = 0.25f,
                            windowSize = VAD_WINDOW,
                        ),
                        sampleRate = SAMPLE_RATE,
                        numThreads = 1,
                    )
                )
                record.startRecording()
                log("ears: listening…")
                val buf = FloatArray(VAD_WINDOW)
                while (listening) {
                    val n = record.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    if (n <= 0) continue
                    vad.acceptWaveform(if (n == buf.size) buf else buf.copyOf(n))
                    while (!vad.empty()) {
                        val segment = vad.front()
                        vad.pop()
                        val text = transcribe(rec, segment.samples)
                        if (text.isNotBlank()) onHeard(text)
                    }
                }
            } catch (e: Exception) {
                log("ears: listening error — ${e.message}")
            } finally {
                try { record?.stop(); record?.release() } catch (e: Exception) {}
                try { vad?.release() } catch (e: Exception) {}
            }
        }.apply { isDaemon = true; start() }
    }

    private fun transcribe(rec: OfflineRecognizer, samples: FloatArray): String {
        return try {
            val stream = rec.createStream()
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            val text = rec.getResult(stream).text.trim()
            stream.release()
            text
        } catch (e: Exception) {
            ""
        }
    }

    fun stop() {
        listening = false
        try { thread?.join(800) } catch (e: Exception) {}
        thread = null
    }

    private fun download(url: String, dest: File, log: (String) -> Unit) {
        val tmp = File(dest.absolutePath + ".part")
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 20000
        c.readTimeout = 60000
        val total = c.contentLengthLong
        var done = 0L
        var lastPct = -1
        c.inputStream.use { input ->
            FileOutputStream(tmp).use { out ->
                val b = ByteArray(1 shl 16)
                while (true) {
                    val r = input.read(b)
                    if (r < 0) break
                    out.write(b, 0, r)
                    done += r
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct >= lastPct + 10) { lastPct = pct; log("ears: downloading… $pct%") }
                    }
                }
            }
        }
        tmp.renameTo(dest)
    }
}
