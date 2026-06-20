package com.tars.app

/**
 * A source of TARS replies. The mind is pluggable:
 *   - StubBrain  — placeholder so the terminal runs before anything is wired.
 *   - RemoteBrain (next) — a pool of free, OpenAI-compatible cloud providers
 *     (Gemini / Groq / Cerebras / OpenRouter-free …) with auto-failover when one
 *     hits its rate limit or quota.
 */
interface Brain {
    /** Produce a reply. Blocking — call from a background thread. */
    fun reply(prompt: String): String
}

class StubBrain : Brain {
    override fun reply(prompt: String): String =
        "[stub] cloud brain not configured yet. You said: \"$prompt\""
}
