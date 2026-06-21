package com.tars.app

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.audiofx.Equalizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * TARS' mouth. Base voice is the on-device system TTS (free, offline, unlimited).
 * The character comes from an effect chain on the synthesized PCM:
 *   - pitch / rate  -> deliberate, slightly pinched delivery
 *   - "nasal" EQ    -> boost ~1–3 kHz and roll off the lows/highs, the gnusavy
 *                      "VHS dub" colour (a nod to Gavrilov), tunable on the fly.
 * It won't emote, but it won't be a flat robot either.
 */
class Voice(context: Context, private val prefs: SharedPreferences, private val log: (String) -> Unit) {

    private val appContext = context.applicationContext
    private val player = Executors.newSingleThreadExecutor()
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    var enabled: Boolean
        get() = prefs.getBoolean(K_ON, true)
        set(v) = prefs.edit().putBoolean(K_ON, v).apply()
    var rate: Float
        get() = prefs.getFloat(K_RATE, 0.98f)
        set(v) = prefs.edit().putFloat(K_RATE, v).apply()
    var pitch: Float
        get() = prefs.getFloat(K_PITCH, 1.0f)
        set(v) = prefs.edit().putFloat(K_PITCH, v).apply()
    var nasal: Float
        get() = prefs.getFloat(K_NASAL, 0.7f)
        set(v) = prefs.edit().putFloat(K_NASAL, v).apply()

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status != TextToSpeech.SUCCESS) { log("voice: system TTS unavailable"); return@TextToSpeech }
            val t = tts ?: return@TextToSpeech
            val ru = t.voices?.firstOrNull { it.locale.language == "ru" && !it.isNetworkConnectionRequired }
            if (ru != null) t.voice = ru else t.language = Locale("ru", "RU")
            ready = true
            log("voice: ready (${ru?.name ?: "ru-RU"})")
        }
    }

    fun speak(text: String) {
        val t = tts ?: return
        if (!ready || !enabled) return
        val clean = text.trim()
        if (clean.isEmpty()) return
        t.setSpeechRate(rate)
        t.setPitch(pitch)
        val wav = File(appContext.cacheDir, "tars_voice.wav")
        val id = "tars-" + System.nanoTime()
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onError(utteranceId: String?) { log("voice: synth error") }
            override fun onDone(utteranceId: String?) {
                player.execute { runCatching { playWithEffects(wav) }.onFailure { log("voice: ${it.message}") } }
            }
        })
        val params = android.os.Bundle()
        if (t.synthesizeToFile(clean, params, wav, id) != TextToSpeech.SUCCESS)
            log("voice: could not start synthesis")
    }

    private fun playWithEffects(wav: File) {
        val bytes = wav.readBytes()
        if (bytes.size < 44) return
        val sampleRate = le32(bytes, 24)
        val channels = le16(bytes, 22)
        var dataAt = 12
        while (dataAt + 8 <= bytes.size) {
            val id = String(bytes, dataAt, 4, Charsets.US_ASCII)
            val sz = le32(bytes, dataAt + 4)
            if (id == "data") { dataAt += 8; break }
            dataAt += 8 + sz
        }
        if (dataAt >= bytes.size) return
        val pcmLen = bytes.size - dataAt
        val mask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(mask)
                    .build()
            )
            .setBufferSizeInBytes(pcmLen)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(bytes, dataAt, pcmLen)
        val eq = runCatching { buildEq(track.audioSessionId) }.getOrNull()
        track.play()
        val frames = pcmLen / (2 * channels.coerceAtLeast(1))
        val durMs = frames * 1000L / sampleRate.coerceAtLeast(1) + 350
        Thread.sleep(durMs)
        runCatching { track.stop() }
        eq?.let { runCatching { it.release() } }
        runCatching { track.release() }
    }

    /** Boost the nasal band, roll off lows & highs — the gnusavy dub colour. */
    private fun buildEq(sessionId: Int): Equalizer {
        val eq = Equalizer(0, sessionId)
        val n = nasal.coerceIn(0f, 1f)
        val range = eq.bandLevelRange
        val maxDn = range[0].toInt()
        val maxUp = range[1].toInt()
        for (b in 0 until eq.numberOfBands.toInt()) {
            val hz = eq.getCenterFreq(b.toShort()) / 1000
            val level = when {
                hz in 1000..3000 -> (n * 0.7f * maxUp).toInt()
                hz < 400 -> (n * 0.8f * maxDn).toInt()
                hz > 4500 -> (n * 0.7f * maxDn).toInt()
                else -> 0
            }.coerceIn(maxDn, maxUp)
            runCatching { eq.setBandLevel(b.toShort(), level.toShort()) }
        }
        eq.enabled = true
        return eq
    }

    fun shutdown() {
        player.shutdownNow()
        tts?.let { it.stop(); it.shutdown() }
        tts = null
    }

    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, i: Int) =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
        ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    companion object {
        private const val K_ON = "voice_on"
        private const val K_RATE = "voice_rate"
        private const val K_PITCH = "voice_pitch"
        private const val K_NASAL = "voice_nasal"
    }
}
