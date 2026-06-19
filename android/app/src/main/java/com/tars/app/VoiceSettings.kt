package com.tars.app

import android.content.Context

/**
 * The voice "bench": the audio counterpart to the personality dials. Three 0..100
 * settings the user tunes so the voice fits TARS's delivery, and they persist
 * across launches:
 *
 *   Depth — how deep / dark the timbre (lower pitch, less top end)
 *   Pace  — speaking speed (deliberate ↔ brisk)
 *   Grit  — how much spacecraft-intercom rasp + static is mixed in (0 = clean)
 *
 * These map to concrete synthesis parameters and feed both the Piper voice and
 * the system-TTS fallback, plus the CommsFilter. When a cloned TARS voice is
 * dropped in later, it sets the *identity*; these dials still shape the delivery
 * on top of it.
 */
object VoiceSettings {
    @Volatile var depth = 60   // 0..100
    @Volatile var pace = 30    // 0..100 — deliberate; TARS doesn't gabble
    @Volatile var grit = 75    // 0..100

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("tars", Context.MODE_PRIVATE)
        depth = p.getInt("v_depth", depth)
        pace = p.getInt("v_pace", pace)
        grit = p.getInt("v_grit", grit)
        apply()
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("tars", Context.MODE_PRIVATE).edit()
            .putInt("v_depth", depth).putInt("v_pace", pace).putInt("v_grit", grit).apply()
        apply()
    }

    // --- derived synthesis parameters ---

    /** Piper synthesis speed (larger = faster). pace 0..100 → 0.75..1.25, so a
     *  low Pace is slow and deliberate. Default pace 30 → ~0.90x. */
    fun piperSpeed(): Float = 0.75f + (pace / 100f) * 0.50f

    /** System-TTS speech rate (larger = faster). → 0.70..1.30. Default → ~0.88x. */
    fun ttsRate(): Float = 0.70f + (pace / 100f) * 0.60f

    /** System-TTS pitch: more depth → lower pitch. depth 0..100 → 1.05..0.55. */
    fun ttsPitch(): Float = 1.05f - (depth / 100f) * 0.50f

    /** Push the derived comms-filter parameters into CommsFilter. */
    fun apply() {
        val g = grit / 100f
        CommsFilter.mix = g                        // 0 clean → 1 full intercom
        CommsFilter.drive = 1.0f + g * 2.0f        // 1.0..3.0
        CommsFilter.hiss = g * 0.008f              // 0..0.008
        // A deeper voice keeps less top end (darker, more chest).
        CommsFilter.highCut = 4200.0 - (depth / 100.0) * 1600.0   // 4200..2600 Hz
        CommsFilter.lowCut = 250.0
    }
}
