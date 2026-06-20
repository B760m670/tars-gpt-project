package com.tars.app

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.media.audiofx.Equalizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * TARS speaks. The base voice comes from the on-device system TTS engine
 * (offline — we pick an embedded Russian voice, never a network one), and the
 * *character* comes from an effect chain applied to the synthesized PCM:
 *
 *   - speechRate  -> slow, deliberate cadence
 *   - pitch       -> shift the tone down (AudioTrack playback pitch)
 *   - nasal       -> peak-EQ a ~2 kHz band up and roll the highs off, for the
 *                    gnusavy "VHS dub" colouring
 *
 * The effect chain is engine-independent: a Piper/sherpa-onnx engine can later
 * feed its samples through the same playWithEffects() path.
 */
class Voice(context: Context, private val prefs: SharedPreferences, private val log: (String) -> Unit) {

    private val appContext = context.applicationContext
    private val player = Executors.newSingleThreadExecutor()
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    // --- tunable params (persisted) ---
    var enabled: Boolean
        get() = prefs.getBoolean(K_ON, false)
        set(v) = prefs.edit().putBoolean(K_ON, v).apply()
    var rate: Float    // 0.5 .. 1.5  (TTS speechRate; <1 = slower)
        get() = prefs.getFloat(K_RATE, 0.9f)
        set(v) = prefs.edit().putFloat(K_RATE, v).apply()
    var pitch: Float   // 0.7 .. 1.2  (playback pitch; <1 = lower)
        get() = prefs.getFloat(K_PITCH, 0.9f)
        set(v) = prefs.edit().putFloat(K_PITCH, v).apply()
    var nasal: Float   // 0 .. 1      (mid boost + treble cut amount)
        get() = prefs.getFloat(K_NASAL, 0.6f)
        set(v) = prefs.edit().putFloat(K_NASAL, v).apply()

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status != TextToSpeech.SUCCESS) { log("voice: system TTS unavailable"); return@TextToSpeech }
            val t = tts ?: return@TextToSpeech
            // Prefer an embedded (offline) Russian voice; fall back to the locale.
            val ru = t.voices?.firstOrNull {
                it.locale.language == "ru" && !it.isNetworkConnectionRequired
            }
            if (ru != null) t.voice = ru else t.language = Locale("ru", "RU")
            ready = true
            log("voice: ready (${ru?.name ?: "ru-RU"})")
        }
    }

    /** Speak [text] aloud with the current effect settings. No-op if disabled. */
    fun speak(text: String) {
        val t = tts ?: return
        if (!ready || !enabled) return
        val clean = text.trim()
        if (clean.isEmpty()) return
        t.setSpeechRate(rate)
        val wav = File(appContext.cacheDir, "tars_voice.wav")
        val id = "tars-" + System.nanoTime()
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onError(utteranceId: String?) { log("voice: synth error") }
            override fun onDone(utteranceId: String?) {
                player.execute { runCatching { playWithEffects(wav) }.onFailure { log("voice: ${it.message}") } }
            }
        })
        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS, "true")
        }
        if (t.synthesizeToFile(clean, params, wav, id) != TextToSpeech.SUCCESS)
            log("voice: could not start synthesis")
    }

    /** Decode the WAV and play it through an AudioTrack with pitch + EQ effects. */
    private fun playWithEffects(wav: File) {
        val bytes = wav.readBytes()
        if (bytes.size < 44) return
        // Parse a minimal PCM WAV: sampleRate @24, channels @22, find "data" chunk.
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
        val p = pitch.coerceIn(0.5f, 2.0f)
        track.playbackParams = PlaybackParams().setPitch(p).setSpeed(1.0f)

        val eq = runCatching { buildEq(track.audioSessionId) }.getOrNull()

        track.play()
        // STATIC playback at pitch p stretches duration by 1/p; wait it out, then free.
        val frames = pcmLen / (2 * channels.coerceAtLeast(1))
        val durMs = (frames * 1000L / sampleRate.coerceAtLeast(1) / p).toLong() + 350
        Thread.sleep(durMs)
        runCatching { track.stop() }
        eq?.let { runCatching { it.release() } }
        runCatching { track.release() }
    }

    /** Peak the nasal band, shelve the highs down — the "VHS dub" colour. */
    private fun buildEq(sessionId: Int): Equalizer {
        val eq = Equalizer(0, sessionId)
        val n = nasal.coerceIn(0f, 1f)
        val range = eq.bandLevelRange // [min, max] in millibels
        val maxUp = range[1].toInt()
        val maxDn = range[0].toInt()
        val bands = eq.numberOfBands.toInt()
        for (b in 0 until bands) {
            val centerHz = eq.getCenterFreq(b.toShort()) / 1000 // mHz -> Hz
            val level = when {
                centerHz in 1200..3500 -> (n * 0.6f * maxUp).toInt()      // nasal boost
                centerHz >= 6000        -> (n * 0.7f * maxDn).toInt()      // treble roll-off
                centerHz < 200          -> (n * 0.4f * maxDn).toInt()      // thin out the lows
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
