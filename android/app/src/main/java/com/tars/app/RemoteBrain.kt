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
 * The cloud mind. For now a single provider — OpenAI (ChatGPT) via its
 * /v1/chat/completions endpoint. Free daily tokens require opting into data
 * sharing on the OpenAI account. Kept deliberately small; more providers can
 * join behind the same [Brain] interface later.
 *
 * The user's own API key lives locally (see [KeyStore]); nothing is shipped.
 */
class RemoteBrain(private val store: KeyStore) : Brain {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val base = "https://api.openai.com/v1"
    val defaultModel = "gpt-5"

    private val system =
        "You are TARS, the robot from the film Interstellar: dry, deadpan wit, " +
        "loyal and direct, concise. Always reply in the user's language — " +
        "Russian or English — with correct, natural grammar."

    override fun reply(prompt: String): String {
        val key = store.key() ?: return "no API key set. add it with:  /key <your_openai_key>"
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
                val chat = ids.filter { it.startsWith("gpt") || it.startsWith("o") }.sorted()
                (if (chat.isNotEmpty()) chat else ids.sorted()).joinToString("\n")
            }
        } catch (e: Exception) {
            "network error: ${e.message}"
        }
    }
}

/** Local storage for the user's own OpenAI key + chosen model. */
class KeyStore(private val prefs: SharedPreferences) {
    fun key(): String? = prefs.getString("openai_key", null)?.ifBlank { null }
    fun setKey(k: String) = prefs.edit().putString("openai_key", k).apply()
    fun model(): String? = prefs.getString("openai_model", null)?.ifBlank { null }
    fun setModel(m: String) = prefs.edit().putString("openai_model", m).apply()
}
