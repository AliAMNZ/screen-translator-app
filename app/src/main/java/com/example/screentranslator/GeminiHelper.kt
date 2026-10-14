package com.example.screentranslator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiHelper {

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    private val MODELS = listOf(
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite",
        "gemini-2.0-flash"
    )

    suspend fun translateImage(base64Image: String, apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        val prompt = """
            نقش شما یک مترجم محاوره‌ای و بسیار خودمونی است. متن انگلیسی این تصویر را بخوان و آن را به فارسی کاملاً روان، طبیعی و محاوره‌ای (خودمونی) ترجمه کن.
            اصطلاحات تخصصی کامپیوتر و مفاهیم فنی را دقیق منتقل کن.
            تمام افعال حتماً شکسته و گفتاری باشند (مثلاً می‌شه، می‌کنه، داری، رو به جای را).
            فقط و فقط متن ترجمه نهایی را بفرست و هیچ مقدمه، توضیح اضافی یا عنوانی ننویس.
        """.trimIndent()

        val jsonBody = JSONObject().apply {
            val contents = JSONArray().apply {
                val contentObj = JSONObject().apply {
                    val parts = JSONArray().apply {
                        put(JSONObject().put("text", prompt))
                        put(JSONObject().put("inlineData", JSONObject().apply {
                            put("mimeType", "image/jpeg")
                            put("data", base64Image)
                        }))
                    }
                    put("parts", parts)
                }
                put(contentObj)
            }
            put("contents", contents)
            put("generationConfig", JSONObject().put("temperature", 0.4))
        }

        var lastError = "خطای نامشخص"

        for (model in MODELS) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseString = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    lastError = "کد خطا ${response.code} در مدل $model"
                    continue
                }

                val rootJson = JSONObject(responseString)
                val output = rootJson.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")

                return@withContext Result.success(output.trim())
            } catch (e: Exception) {
                lastError = e.message ?: "خطای شبکه"
            }
        }

        Result.failure(Exception(lastError))
    }
}
