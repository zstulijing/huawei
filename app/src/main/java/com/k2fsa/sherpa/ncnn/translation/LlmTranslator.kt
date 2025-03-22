package com.k2fsa.sherpa.ncnn.translation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "LlmTranslator"

class LlmTranslator() {
    private val apiKey = ""
    private val openaiUrl = "http://localhost:8080/v1/chat/completions"
    private val model = "qwen25_05"

    suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        try {
//            val sysprompt = """
//                下面给出一段文本，请完成中英互译，如果原始语料为英文，则翻译成中文，如果原始语料为中文，则翻译成英文。
//                输出格式返回一个json:
//                {"原始语料语种":"中文/英文", "译文":"这里输出译文"}
//                case 1:
//                原文: 我最喜欢秋天，因为秋天的天气很清爽;
//                {"原文语种":"中文", "译文":"I love the weather of autumn best in that I think the weather is very cool in autumn"}
//                case 2:
//                原文: She enjoys reading novels in her free time;
//                {"原文语种":"英文", "译文":"她喜欢在空闲时间读小说"}
//                case 3:
//                原文: 强化学习;
//                {"原文语种":"中文", "译文":"reinforcement learning"}
//            """.trimIndent()


            val sysprompt = """
                下面给出一段文本，请完成中英互译，如果原始语料为英文，则翻译成中文，如果原始语料为中文，则翻译成英文。
                输出格式返回一个json:
                {"原始语料语种":"中文/英文", "译文":"这里输出译文"}
                case 1:
                原文: 强化学习;
                {"原文语种":"中文", "译文":"reinforcement learning"}
            """.trimIndent()

            Log.i(TAG,"原文：$text")
            val connection = URL(openaiUrl).openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 15000
            }

            // 构建消息结构
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", sysprompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", text+ ";")
                })
            }

            // 构建请求体
            val requestBody = JSONObject().apply {
                put("model", model)
                put("messages", messages)
//                put("max_tokens", 1000)
                put("temperature", 0)
//                put("response_format", JSONObject().apply {
//                    put("type", "json_object")  // 要求返回JSON格式
//                })
            }

            // 发送请求
            OutputStreamWriter(connection.outputStream).use {
                it.write(requestBody.toString())
                it.flush()
            }

            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    Log.i(TAG,"raw_response:\n $response")
                    parseTranslationResponse(response).ifEmpty { "Translation empty"}
                }
                else -> {
                    val error = connection.errorStream.bufferedReader().use { it.readText() }
                    Log.e(TAG, "API Error (${connection.responseCode}): $error")
                    "Translation error: ${connection.responseCode}"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Translation failed: ${e.stackTraceToString()}")
            "Translation error: ${e.message ?: "unknown error"}"
        }
    }

    private fun parseTranslationResponse(response: String): String {
        return try {
            // 解析第一层API响应
            val jsonResponse = JSONObject(response)
            val content = jsonResponse
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            // 尝试解析翻译结果JSON
            try {
                JSONObject(content).getString("译文")
            } catch (e: Exception) {
                // 使用正则表达式提取译文
                val regex = """["']译文["']\s*:\s*["']([^"']+)["']""".toRegex()
                regex.find(content)?.groupValues?.get(1)?.trim()
                    ?: content.substringAfterLast(":").trim()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Response parsing failed: ${e.message}")
            // 兜底方案：尝试直接提取引号内容
            response.substringAfter("\"译文\":\"").substringBefore("\"")
        }
    }
}