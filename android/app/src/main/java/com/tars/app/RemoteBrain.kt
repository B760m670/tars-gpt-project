package com.tars.app

import android.content.SharedPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The cloud mind: a pool of free, OpenAI-compatible providers with auto-failover.
 * Each provider speaks the same /chat/completions dialect, so one HTTP path
 * serves all. The active provider is tried first; on a rate-limit / quota / auth
 * error we fall through to any other provider that has a key set.
 *
 * Keys are the user's own free keys, stored locally (never shipped in the app).
 */
class RemoteBrain(private val keys: KeyStore) : Brain {

    data class Provider(
        val id: String,
        val label: String,
        val baseUrl: String,
        val defaultModel: String,
        val keysUrl: String,
    )

    // Known free, OpenAI-compatible providers. OpenRouter is the one-key starter;
    // the others add daily volume. Model ids drift — override with /model.
    val providers: LinkedHashMap<String, Provider> = linkedMapOf(
        "openrouter" to Provider("openrouter", "OpenRouter",
            "https://openrouter.ai/api/v1", "deepseek/deepseek-chat-v3-0324:free", "openrouter.ai/keys"),
        "groq" to Provider("groq", "Groq",
            "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "console.groq.com/keys"),
        "gemini" to Provider("gemini", "Gemini",
            "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash", "aistudio.google.com/apikey"),
        "cerebras" to Provider("cerebras", "Cerebras",
            "https://api.cerebras.ai/v1", "llama-3.3-70b", "cloud.cerebras.ai"),
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val system =
        "You are TARS, the robot from the film Interstellar. Dry, deadpan wit; " +
        "loyal and direct. Keep answers concise. Always reply in the user's " +
        "language — Russian or English — with correct, natural grammar."

    override fun reply(prompt: String): String {
        // Failover order: active provider first, then any other that has a key.
        val active = keys.activeProvider()
        val order = (listOf(active) + providers.keys).distinct().filter { keys.key(it) != null }
        if (order.isEmpty())
            return "no API key set. get a free one at openrouter.ai/keys, then:\n" +
                   "  /key openrouter <your_key>"
        var lastErr = "?"
        for (pid in order) {
            val p = providers[pid] ?: continue
            val key = keys.key(pid) ?: continue
            val model = keys.model(pid) ?: p.defaultModel
            val (ok, text) = call(p, key, model, prompt)
            if (ok) return text
            lastErr = "[$pid] $text"
        }
        return "all providers failed. last error: $lastErr"
    }

    private fun call(p: Provider, key: String, model: String, prompt: String): Pair<Boolean, String> {
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", prompt)))
        val req = Request.Builder()
            .url("${p.baseUrl}/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                val s = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return false to "HTTP ${resp.code}: ${s.take(160)}"
                val content = JSONObject(s).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()
                true to content.ifBlank { "(empty reply)" }
            }
        } catch (e: Exception) {
            false to (e.message ?: "network error")
        }
    }

    /** List currently-free model ids from OpenRouter, so /model picks a valid one. */
    fun openRouterFreeModels(): String {
        val req = Request.Builder().url("https://openrouter.ai/api/v1/models").build()
        return try {
            http.newCall(req).execute().use { resp ->
                val s = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return "HTTP ${resp.code}"
                val arr = JSONObject(s).getJSONArray("data")
                val free = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val cost = m.optJSONObject("pricing")?.optString("prompt") ?: "1"
                    if (cost == "0" || cost == "0.0") free.add(m.getString("id"))
                }
                if (free.isEmpty()) "no free models found" else free.take(40).joinToString("\n")
            }
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }
}

/** Local, plaintext storage for the user's own free API keys + model/active pick. */
class KeyStore(private val prefs: SharedPreferences) {
    fun key(provider: String): String? = prefs.getString("key_$provider", null)?.ifBlank { null }
    fun setKey(provider: String, key: String) = prefs.edit().putString("key_$provider", key).apply()
    fun model(provider: String): String? = prefs.getString("model_$provider", null)?.ifBlank { null }
    fun setModel(provider: String, model: String) = prefs.edit().putString("model_$provider", model).apply()
    fun activeProvider(): String = prefs.getString("active", "openrouter") ?: "openrouter"
    fun setActive(provider: String) = prefs.edit().putString("active", provider).apply()
}
