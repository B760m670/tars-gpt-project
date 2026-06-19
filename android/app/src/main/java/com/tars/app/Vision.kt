package com.tars.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * TARS's eyes. CameraX feeds still frames (back or front camera) to the on-device
 * multimodal model running in llama-server, which describes — in character — what
 * it sees. No preview surface is shown: TARS just looks, like a robot would.
 *
 *   camera (CameraX) -> JPEG frame -> llama-server /v1/chat/completions (+image)
 */
object Vision {

    @Volatile private var imageCapture: ImageCapture? = null
    @Volatile private var bound = false
    @Volatile var lensFront = false   // back camera by default — his outward eye
    private val captureExec = Executors.newSingleThreadExecutor()

    fun isOn(): Boolean = bound

    /** Bind the camera to the activity lifecycle (on the main thread). */
    fun start(activity: AppCompatActivity, log: (String) -> Unit) {
        if (bound) return
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            try {
                val provider = future.get()
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val selector = if (lensFront) CameraSelector.DEFAULT_FRONT_CAMERA
                else CameraSelector.DEFAULT_BACK_CAMERA
                provider.unbindAll()
                provider.bindToLifecycle(activity, selector, capture)
                imageCapture = capture
                bound = true
                log("vision: camera on (${if (lensFront) "front" else "back"}) — TARS is watching")
            } catch (e: Exception) {
                log("vision: couldn't open camera — ${e.message}")
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    fun stop(activity: AppCompatActivity, log: (String) -> Unit) {
        if (!bound) return
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            try { future.get().unbindAll() } catch (e: Exception) {}
            imageCapture = null
            bound = false
            log("vision: camera off")
        }, ContextCompat.getMainExecutor(activity))
    }

    fun switchLens(activity: AppCompatActivity, log: (String) -> Unit) {
        lensFront = !lensFront
        if (bound) { bound = false; start(activity, log) }
    }

    /** Capture one frame and ask the multimodal model about it, in character.
     *  Returns TARS's comment, or "" if the camera/model isn't ready or there's
     *  nothing worth saying. Blocks — call off the UI thread. */
    fun glance(systemPrompt: String, ask: String, log: (String) -> Unit): String {
        val capture = imageCapture ?: return ""
        val jpeg = grabFrame(capture, log) ?: return ""
        val b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
        return query(systemPrompt, ask, b64, log)
    }

    /** Take one in-memory photo, downscaled to keep the model fast. */
    private fun grabFrame(capture: ImageCapture, log: (String) -> Unit): ByteArray? {
        val latch = CountDownLatch(1)
        var out: ByteArray? = null
        capture.takePicture(captureExec, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val buf = image.planes[0].buffer
                    val raw = ByteArray(buf.remaining()); buf.get(raw)
                    out = downscale(raw, image.imageInfo.rotationDegrees)
                } catch (e: Exception) {
                    log("vision: frame decode failed — ${e.message}")
                } finally {
                    image.close(); latch.countDown()
                }
            }

            override fun onError(exc: ImageCaptureException) {
                log("vision: capture error — ${exc.message}")
                latch.countDown()
            }
        })
        return try {
            if (latch.await(8, TimeUnit.SECONDS)) out else null
        } catch (e: Exception) { null }
    }

    /** Decode, rotate upright, scale to a max edge of 768px, re-encode JPEG. */
    private fun downscale(jpeg: ByteArray, rotation: Int): ByteArray {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val maxEdge = 768
        val scale = maxEdge.toFloat() / maxOf(bmp.width, bmp.height)
        val m = Matrix()
        if (scale < 1f) m.postScale(scale, scale)
        if (rotation != 0) m.postRotate(rotation.toFloat())
        val fixed = if (m.isIdentity) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val bos = ByteArrayOutputStream()
        fixed.compress(Bitmap.CompressFormat.JPEG, 80, bos)
        return bos.toByteArray()
    }

    /** POST the frame + prompt to llama-server's OpenAI-compatible vision API. */
    private fun query(systemPrompt: String, ask: String, b64: String, log: (String) -> Unit): String {
        return try {
            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
            val content = JSONArray()
            content.put(JSONObject().put("type", "text").put("text", ask))
            content.put(
                JSONObject().put("type", "image_url").put(
                    "image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")
                )
            )
            messages.put(JSONObject().put("role", "user").put("content", content))
            val payload = JSONObject()
                .put("messages", messages)
                .put("temperature", 0.7)
                .put("top_p", 0.8)

            val c = URL("http://127.0.0.1:8080/v1/chat/completions").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 5000
            c.readTimeout = 120000
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(payload.toString().toByteArray()) }
            val body = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val text = JSONObject(body)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
            // The model is told to answer "…" when nothing's worth a remark.
            if (text == "…" || text == "...") "" else text
        } catch (e: Exception) {
            log("vision: model query failed — ${e.message}")
            ""
        }
    }
}
