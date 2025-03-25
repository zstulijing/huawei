package com.k2fsa.sherpa.ncnn.translation
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "OpenAIClient"

object LlmTranslator {
    // 单例OkHttpClient实例
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    private val openaiUrl = "http://localhost:8080/v1/chat/completions"
    private val model = "qwen25_05"
    suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        try {

            val sysprompt = """
                下面给出一段文本，请完成中英互译，如果原始语料为英文，则翻译成中文，如果原始语料为中文，则翻译成英文。仅返回译文！
            """.trimIndent()

            Log.i(TAG,"原文：$text")
            performTranslation(text,sysprompt)
        }catch (e:Exception){
            Log.e(TAG, "Translation failed: ${e.stackTraceToString()}")
            "Translation error: ${e.message ?: "unknown error"}"
        }
    }
    fun performTranslation(text: String, sysprompt: String): String {
        // 构建消息结构
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", sysprompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", "$text;")
            })
        }

        // 构建请求体
        val requestBody = JSONObject().apply {
            put("messages", messages)
            put("temperature", 0.7)
            put("top_p",1.0)
            put("stream",true)
            // 可选参数
            // put("max_tokens", 1000)
            // put("response_format", JSONObject().apply {
            //     put("type", "json_object")
            // })
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url(openaiUrl)
            .post(requestBody.toString().toRequestBody(mediaType))
//            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    Log.i(TAG, "raw_response:\n $responseBody")
                    parseTranslationResponse(responseBody).ifEmpty { "Translation empty" }
                } else {
                    val errorBody = response.body?.string() ?: ""
                    Log.e(TAG, "API Error (${response.code}): $errorBody")
                    "Translation error: ${response.code}"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Translation failed: ${e.stackTraceToString()}")
            "Translation error: ${e.message ?: "unknown error"}"
        }
    }

    // 解析API响应
    private fun parseTranslationResponse(responseJson: String): String {
        return try {
            val jsonResponse = JSONObject(responseJson)
            val choices = jsonResponse.getJSONArray("choices")
            if (choices.length() > 0) {
                val firstChoice = choices.getJSONObject(0)
                val message = firstChoice.getJSONObject("message")
                message.getString("content")
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing response: ${e.message}")
            ""
        }
    }
}