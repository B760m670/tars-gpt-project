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
 * The cloud mind — Google Gemini via its OpenAI-compatible endpoint. Gemini's
 * free tier just works with an API key (no data-sharing / billing gate): ~1500
 * requests/day on Flash models, resets midnight Pacific. Excellent Russian.
 *
 * The user's own API key lives locally (see [KeyStore]); nothing is shipped.
 */
class RemoteBrain(private val store: KeyStore) : Brain {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val base = "https://generativelanguage.googleapis.com/v1beta/openai"
    val defaultModel = "gemini-3-flash"

    private val system =
        "You are TARS, the robot from the film Interstellar: dry, deadpan wit, " +
        "loyal and direct, concise. Always reply in the user's language — " +
        "Russian or English — with correct, natural grammar."

    override fun reply(prompt: String): String {
        val key = store.key() ?: return "no API key set. add it with:  /key <your_gemini_key>"
        val model = store.model() ?: defaultModel
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", prompt)))
        val req = Request.Builder()
            .url("$base/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                val s = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return "error HTTP ${resp.code}: ${s.take(220)}"
                JSONObject(s).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim().ifBlank { "(empty reply)" }
            }
        } catch (e: Exception) {
            "network error: ${e.message}"
        }
    }

    /** List model ids this key can use (so /model picks a valid one). */
    fun listModels(): String {
        val key = store.key() ?: return "set a key first:  /key <key>"
        val req = Request.Builder().url("$base/models")
            .addHeader("Authorization", "Bearer $key").build()
        return try {
            http.newCall(req).execute().use { resp ->
                val s = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return "error HTTP ${resp.code}: ${s.take(160)}"
                val arr = JSONObject(s).getJSONArray("data")
                val ids = ArrayList<String>()
                for (i in 0 until arr.length()) ids.add(arr.getJSONObject(i).getString("id"))
                ids.map { it.removePrefix("models/") }.filter { it.startsWith("gemini") }.sorted().joinToString("\n")
                    .ifBlank { ids.sorted().joinToString("\n") }
            }
        } catch (e: Exception) {
            "network error: ${e.message}"
        }
    }
}

/** Local storage for the user's own Gemini key + chosen model. */
class KeyStore(private val prefs: SharedPreferences) {
    fun key(): String? = prefs.getString("gemini_key", null)?.ifBlank { null }
    fun setKey(k: String) = prefs.edit().putString("gemini_key", k).apply()
    fun model(): String? = prefs.getString("gemini_model", null)?.ifBlank { null }
    fun setModel(m: String) = prefs.edit().putString("gemini_model", m).apply()
}
