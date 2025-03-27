package com.k2fsa.sherpa.ncnn.translation
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource

private const val TAG = "OpenAIClient"

object LlmTranslator {
    // 单例OkHttpClient实例
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    private val openaiUrl = "http://localhost:8080/v1/chat/completions"
    private val model = "qwen25_05"

    suspend fun translate(text: String, useStreaming: Boolean = true): String = withContext(Dispatchers.IO) {
        try {
            val sysprompt = """
                下面给出一段文本，请完成中英互译，如果原始语料为英文，则翻译成中文，如果原始语料为中文，则翻译成英文。仅返回译文！
            """.trimIndent()

            Log.i(TAG,"原文：$text")
            if (useStreaming) {
                performStreamingTranslation(text, sysprompt)
            } else {
                performNonStreamingTranslation(text, sysprompt)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Translation failed: ${e.stackTraceToString()}")
            "Translation error: ${e.message ?: "unknown error"}"
        }
    }

    // 非流式请求
    private fun performNonStreamingTranslation(text: String, sysprompt: String): String {
        // 构建消息结构
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", sysprompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", "$text。")
            })
        }

        // 构建请求体
        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 1)
            put("top_p", 1.0)
            put("stream", false)
            // 可选参数
            // put("max_tokens", 1000)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url(openaiUrl)
            .post(requestBody.toString().toRequestBody(mediaType))
            .header("Content-Type", "application/json")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    Log.i(TAG, "raw_response:\n $responseBody")
                    parseNonStreamingResponse(responseBody).ifEmpty { "Translation empty" }
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

    // 流式请求
    private fun performStreamingTranslation(text: String, sysprompt: String): String {
        // 构建消息结构
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", sysprompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", "$text。")
            })
        }

        // 构建请求体
        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 1)
            put("top_p", 1.0)
            put("stream", true)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url(openaiUrl)
            .post(requestBody.toString().toRequestBody(mediaType))
            .header("Content-Type", "application/json")
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                parseStreamingResponse(response)
            } else {
                val errorBody = response.body?.string() ?: ""
                Log.e(TAG, "API Error (${response.code}): $errorBody")
                "Translation error: ${response.code}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Translation failed: ${e.stackTraceToString()}")
            "Translation error: ${e.message ?: "unknown error"}"
        }
    }

    // 解析非流式API响应
    private fun parseNonStreamingResponse(responseJson: String): String {
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
            Log.e(TAG, "Error parsing non-streaming response: ${e.message}")
            ""
        }
    }

    // 解析流式API响应
    private fun parseStreamingResponse(response: Response): String {
        val fullContent = StringBuilder()
        try {
            val body = response.body ?: return "No response body"
            val source = body.source()
            val buffer = Buffer()

            while (!source.exhausted()) {
                source.read(buffer, 8192) // Read a chunk of data

                val chunk = buffer.readUtf8()
                Log.d(TAG, "Received chunk: $chunk")

                // Stream responses are formatted as "data: {...}\n\n"
                val lines = chunk.split("\n")
                for (line in lines) {
                    if (line.startsWith("data:")) {
                        val jsonStr = line.substring("data:".length).trim()

                        // Check for [DONE] marker
                        if (jsonStr == "[DONE]") continue

                        try {
                            val jsonChunk = JSONObject(jsonStr)
                            val choices = jsonChunk.optJSONArray("choices")
                            if (choices != null && choices.length() > 0) {
                                val delta = choices.getJSONObject(0).optJSONObject("delta")
                                if (delta != null) {
                                    val content = delta.optString("content", "")
                                    if (content.isNotEmpty()) {
                                        fullContent.append(content)
                                        Log.d(TAG, "Accumulated content: $fullContent")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing chunk: $jsonStr, error: ${e.message}")
                        }
                    }
                }
            }

            body.close()
            Log.i(TAG, "Final translation: $fullContent")
            return fullContent.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading streaming response: ${e.message}")
            return "Error reading streaming response: ${e.message ?: "unknown error"}"
        }
    }
}