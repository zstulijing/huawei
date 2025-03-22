package com.k2fsa.sherpa.ncnn.control


import android.content.Context
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.engine.cio.*
import io.ktor.http.*
import io.ktor.util.*
import java.io.File
import android.util.Base64
import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import kotlin.math.log


class Request {

    /**
     * 服务器IP地址
     */
    private val base_url: String = "http://10.109.246.210"

    // 创建带超时的 HttpClient
    private fun createClient(): HttpClient {
        return HttpClient(CIO) {
            engine {
                requestTimeout = 10_000 // 请求超时：10秒（毫秒）
            }
        }
    }

    /**
     * List<Bitmap>转base64
     * @param {List<Bitmap>} - gestureFrames
     * @param {Int} - frameIndex
     */
    private fun bitmapToBase64(gestureFrames: List<Bitmap>, frameIndex: Int = 0): String? {
        // 检查列表是否为空或索引是否有效
        if (gestureFrames.isEmpty() || frameIndex < 0 || frameIndex >= gestureFrames.size) {
            return null
        }

        // 获取指定帧的 Bitmap
        val bitmap = gestureFrames[frameIndex]

        // 将 Bitmap 压缩为字节数组
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream) // PNG 格式，质量 100
        val byteArray = outputStream.toByteArray()

        // 将字节数组转换为 Base64 字符串
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    /**
     * 文本转语音
     * @param {String} - inputText 输入文本内容
     * @param {String} - filePath 输出文件路径名
     */
    suspend fun textToSpeech(inputText: String, context: Context, gender: String = "male", language: String  = "en"): String {
        val client = createClient()
        try {
            val port = 10002
            val url = "$base_url:$port/text_to_speech"
            val jsonBody = """{"input": "$inputText", "gender": "$gender", "language": "$language"}"""

            val response: HttpResponse = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(jsonBody)
            }
            val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            val filename = "video_${System.currentTimeMillis()}.mp3"
            val videFile = File(storageDir, filename)

            videFile.writeBytes(response.bodyAsChannel().toByteArray())
            return "$storageDir/$filename" // 返回文件路径
        } catch (e: Exception) {
            Log.e("Error", "错误: ${e.localizedMessage}")
            return ""
        } finally {
            client.close()
        }
    }

    /**
     * 性别识别
     */
    suspend fun genderRecognition(gestureFrames: List<Bitmap>): String {
        val client = HttpClient(CIO)
        try {
            val port = 10004
            val url = "$base_url:$port/image_gender"

            // 手动构造 JSON 请求体
            val base64String = bitmapToBase64(gestureFrames)

            val jsonBody = """{"image": ${if (base64String != null) "\"$base64String\"" else "null"}}"""


            val response: HttpResponse = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(jsonBody)
            }

            // 手动解析 JSON 响应
            val responseText = response.bodyAsText()
            val jsonObject = JSONObject(responseText)
            
            val result = jsonObject.getString("result").lowercase()
            return result
        } catch (e: Exception) {
            Log.e("Error", "错误: ${e.localizedMessage}")
            return ""
        } finally {
            client.close()
        }
    }

    /**
     * 手势识别
     */
    suspend fun gestureRecognition(gestureFrames: List<Bitmap>): String {
        val client = HttpClient(CIO)
        try {
            val port = 18888
            val url = "$base_url:$port/detect_fist"

            // 手动构造 JSON 请求体
            val base64String = bitmapToBase64(gestureFrames)

            val jsonBody = """{"image": ${if (base64String != null) "\"$base64String\"" else "null"}}"""


            val response: HttpResponse = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(jsonBody)
            }

            // 手动解析 JSON 响应
            val responseText = response.bodyAsText()
            val jsonObject = JSONObject(responseText)
            val result = jsonObject.getString("result")
            return result
        } catch (e: Exception) {
            Log.e("Error", "错误: ${e.localizedMessage}")
            return ""
        } finally {
            client.close()
        }
    }

    /**
     * 中英互译
     */
    suspend fun translate(inputText: String): String {
        val client = createClient()
        try {

            val port = 10005
            val url = "$base_url:$port/translate_text"

            // 手动构造请求的 JSON 字符串
            val jsonRequestBody = """{"input": "$inputText"}"""

            val response: HttpResponse = client.post(url) {
                contentType(ContentType.Application.Json) // 设置请求内容类型
                setBody(jsonRequestBody) // 发送 JSON 字符串
            }

            // 获取响应文本并手动解析 JSON
            val responseText = response.bodyAsText()
            val jsonResponse = JSONObject(responseText)
            val result = jsonResponse.getString("result") // 提取 "result" 字段

            client.close()
            return result // 返回翻译结果
        } catch (e: Exception) {
            Log.e("Error", "错误: ${e.localizedMessage}")
            return ""
        } finally {
            client.close()
        }

    }

}
