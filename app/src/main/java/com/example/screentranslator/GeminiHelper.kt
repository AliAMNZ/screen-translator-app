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

data class InPlaceTextBlock(
    val ymin: Int,
    val xmin: Int,
    val ymax: Int,
    val xmax: Int,
    val text: String
)

object GeminiHelper {

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private val MODELS = listOf(
        "gemini-3.5-flash-lite",
        "gemini-3.5-flash",
        "gemini-3.1-flash-lite-preview",
        "gemini-2.5-flash"
    )

    // ۱. ترجمه کادر انتخابی (پاپ‌آپ)
    suspend fun translateImage(base64Image: String, apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) return@withContext Result.failure(Exception("کلید API خالی است."))

        val prompt = """
            نقش شما یک مترجم محاوره‌ای و بسیار خودمونی است. متن انگلیسی این تصویر را بخوان و آن را به فارسی کاملاً روان، طبیعی و محاوره‌ای (خودمونی) ترجمه کن.
            تمام افعال حتماً شکسته و گفتاری باشند (مثلاً: می‌شه، می‌کنه، داری، می‌گه، رو به جای را).
            فقط و فقط متن ترجمه نهایی را بفرست و هیچ مقدمه یا توضیح اضافی ننویس.
        """.trimIndent()

        executeGeminiTextCall(cleanKey, prompt, base64Image, false)
    }

    // ۲. ترجمه درجای کل صفحه (In-Place) با استخراج مختصات دقیق هر متن روی صفحه
    suspend fun translateFullPageInPlace(base64Image: String, apiKey: String): Result<List<InPlaceTextBlock>> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) return@withContext Result.failure(Exception("کلید API خالی است."))

        val prompt = """
            تمامی بلاک‌های متنی، تیترها و جملات انگلیسی موجود در این اسکرین‌شات را پیدا کن و هرکدام را به فارسی کاملاً خودمونی و محاوره‌ای (افعال شکسته مثل می‌شه، می‌کنه و "رو" به جای "را") ترجمه کن.
            
            خروجی باید منحصراً یک آرایه JSON معتبر باشد. هر آیتم آرایه باید دقیقاً شامل این دو کلید باشد:
            1. "box_2d": آرایه‌ای از 4 عدد صحیح به صورت [ymin, xmin, ymax, xmax] در مقیاس 0 تا 1000 که موقعیت دقیق آن متن در تصویر را مشخص می‌کند.
            2. "text": رشته ترجمه‌شده به فارسی خودمونی.
            
            هیچ توضیحی خارج از JSON ننویس.
        """.trimIndent()

        val rawRes = executeGeminiTextCall(cleanKey, prompt, base64Image, true)
        rawRes.fold(
            onSuccess = { jsonStr ->
                try {
                    val cleaned = jsonStr
                        .replace(Regex("^```json\\s*", RegexOption.IGNORE_CASE), "")
                        .replace(Regex("^```\\s*"), "")
                        .replace(Regex("\\s*```$"), "")
                        .trim()

                    val arr = JSONArray(cleaned)
                    val list = mutableListOf<InPlaceTextBlock>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val box = obj.getJSONArray("box_2d")
                        val txt = obj.getString("text")
                        if (box.length() == 4 && txt.isNotBlank()) {
                            list.add(
                                InPlaceTextBlock(
                                    ymin = box.getInt(0),
                                    xmin = box.getInt(1),
                                    ymax = box.getInt(2),
                                    xmax = box.getInt(3),
                                    text = txt
                                )
                            )
                        }
                    }
                    Result.success(list)
                } catch (e: Exception) {
                    Result.failure(Exception("خطا در پردازش ساختار درجا: ${e.message}"))
                }
            },
            onFailure = { err -> Result.failure(err) }
        )
    }

    private fun executeGeminiTextCall(
        apiKey: String,
        prompt: String,
        base64Image: String,
        jsonMode: Boolean
    ): Result<String> {
        val genConfig = JSONObject().apply {
            put("temperature", 0.3)
            if (jsonMode) {
                put("responseMimeType", "application/json")
            }
        }

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
            put("generationConfig", genConfig)
        }

        val errorLogs = mutableListOf<String>()

        for (model in MODELS) {
            try {
                val url = "[https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey](https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey)"
                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseString = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val errMsg = try {
                        JSONObject(responseString).getJSONObject("error").getString("message")
                    } catch (e: Exception) {
                        "HTTP ${response.code}"
                    }
                    errorLogs.add("$model: $errMsg")
                    continue
                }

                val rootJson = JSONObject(responseString)
                val output = rootJson.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")

                return Result.success(output.trim())
            } catch (e: Exception) {
                errorLogs.add("$model: ${e.message}")
            }
        }

        return Result.failure(Exception(errorLogs.joinToString("\n")))
    }
}
