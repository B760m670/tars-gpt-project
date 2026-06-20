package com.tars.app

import java.io.File

/**
 * On-device model catalog + device-aware pick. Models are hosted on THIS repo's
 * GitHub release (tag "models") — the same infrastructure that reliably ships the
 * APK — so the phone never touches HuggingFace (whose redirect/CDN was the source
 * of the old "download interrupted (0 MB)" failures). A separate CI workflow
 * (.github/workflows/models.yml) mirrors the GGUFs from HuggingFace into that
 * release, where HuggingFace IS reachable (GitHub runners), not on the phone.
 */
object ModelCatalog {

    private const val RELEASE_BASE =
        "https://github.com/B760m670/tars-gpt-project/releases/download/models"

    data class Spec(
        val id: String,
        val label: String,
        val fileName: String,
        val fileMb: Int,
        val minRamMb: Int,
    ) {
        val url: String get() = "$RELEASE_BASE/$fileName"
    }

    // Light -> heavy. Q4_K_M GGUFs of Qwen3 (bilingual RU/EN, strong character).
    val catalog = listOf(
        Spec("qwen3-0.6b", "Feather", "Qwen3-0.6B-Q4_K_M.gguf", 500, 1200),
        Spec("qwen3-1.7b", "Light", "Qwen3-1.7B-Q4_K_M.gguf", 1100, 2400),
        Spec("qwen3-4b", "Standard", "Qwen3-4B-Q4_K_M.gguf", 2500, 4400),
    )

    private const val HEADROOM = 0.7

    /** Total device RAM in MB, or null if unreadable (/proc/meminfo works on Android). */
    fun totalRamMb(): Int? = try {
        File("/proc/meminfo").readLines()
            .firstOrNull { it.startsWith("MemTotal:") }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toInt()?.div(1024)
    } catch (e: Exception) { null }

    private fun fits(spec: Spec, ramMb: Int) = spec.minRamMb <= ramMb * HEADROOM

    /** Heaviest model that comfortably fits, or null → offline brain only. */
    fun recommend(ramMb: Int? = totalRamMb()): Spec? {
        val ram = ramMb ?: return null
        return catalog.lastOrNull { fits(it, ram) }
    }
}
