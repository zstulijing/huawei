package com.k2fsa.sherpa.ncnn.request

import android.content.Context
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.engine.cio.*
import io.ktor.http.*
import java.io.File
import android.util.Base64
import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder


class Request {

    /**
     * 服务器IP地址
     */
    val baseUrl: String = "10.109.246.210"

    // 创建带超时的 HttpClient
    private fun createClient(): HttpClient {
        return HttpClient(CIO) {
            engine {
                requestTimeout = 5_000 // 请求超时：5秒，因为每个每次捕获图像经过5秒
            }
        }
    }

    private var client: HttpClient = HttpClient(CIO)


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
     * FloatArray转wav
     */
    private fun saveFloatArrayAsWav(floatArray: FloatArray, sampleRate: Int, filePath: String) {
        val byteArray = floatArrayToPCM16(floatArray)
        val wavHeader = createWavHeader(byteArray.size, sampleRate)

        FileOutputStream(File(filePath)).use { output ->
            output.write(wavHeader)
            output.write(byteArray)
        }
        println("音频文件已保存到: $filePath")
    }
    private fun floatArrayToPCM16(floatArray: FloatArray): ByteArray {
        val byteBuffer = ByteBuffer.allocate(floatArray.size * 2)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        for (sample in floatArray) {
            val pcmSample = (sample * Short.MAX_VALUE).toInt().toShort()
            byteBuffer.putShort(pcmSample)
        }
        return byteBuffer.array()
    }
    private fun createWavHeader(dataSize: Int, sampleRate: Int): ByteArray {
        val totalSize = dataSize + 36
        val byteRate = sampleRate * 2 // 16-bit 单声道
        return ByteBuffer.allocate(44).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            put("RIFF".toByteArray()) // ChunkID
            putInt(totalSize) // ChunkSize
            put("WAVE".toByteArray()) // Format
            put("fmt ".toByteArray()) // Subchunk1ID
            putInt(16) // Subchunk1Size (PCM)
            putShort(1) // AudioFormat (PCM = 1)
            putShort(1) // NumChannels (单声道)
            putInt(sampleRate) // SampleRate
            putInt(byteRate) // ByteRate
            putShort(2) // BlockAlign
            putShort(16) // BitsPerSample
            put("data".toByteArray()) // Subchunk2ID
            putInt(dataSize) // Subchunk2Size
        }.array()
    }

    /**
     * 清空下载的mp3文件
     * @param {String} - filePath 文件路径
     */
    fun cleanUpAudioFile(filePath: String) {
        try {
            val file = File(filePath)
            if (file.exists()) {
                file.delete()
                Log.i("AudioModule", "Cleaned up audio file: $filePath")
            }
        } catch (e: Exception) {
            Log.e("AudioModule", "Error cleaning up file: ${e.message}")
        }
    }


    /**
     * 文本转语音
     * @param {String} - inputText 输入文本内容
     * @param {Context} - context 安卓上下文
     * @param {String} - gender 性别 male / female
     * @param {String} - language 语言 en / zh
     * @return {String} - filePath 文件路径
     */
    suspend fun textToSpeech(inputText: String, context: Context, gender: String = "male", language: String  = "en"): String {
        try {

            @Serializable
            data class TextPostData(val input: String, val language: String, val gender: String)
            @Serializable
            class SoundResponseData(val code: Int, val result: FloatArray, val process_time: Double)
            val client = HttpClient(CIO) {
                install(ContentNegotiation) { json() } // JSON 解析插件
            }
            val port = 10002
            val url = "http://$baseUrl:$port/text_to_speech"
            val textPostData = TextPostData(inputText, language, gender)

            val response: SoundResponseData = client.post(url) {
                contentType(ContentType.Application.Json)  // 设置请求内容类型
                setBody(textPostData)  // 发送 JSON 数据
            }.body()
            val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            val filename = "video_${System.currentTimeMillis()}.mp3"
            val filePath = "$storageDir/$filename"

            val sampleRate = 24000
            saveFloatArrayAsWav(response.result, sampleRate, filePath)
            Log.e("result", "语音文件存储路径：$filePath")
            return filePath // 返回文件路径

        } catch (e: Exception) {
            Log.e("Error", "错误: ${e.localizedMessage}")
            return ""
        }

    }

    /**
     * 性别识别
     * @param {List<Bitmap>} - gestureFrames 摄像头采集数据
     * @return {String} - result 性别识别结果 male / female
     */
    suspend fun genderRecognition(gestureFrames: List<Bitmap>): String {
        try {
            val port = 10004
            val url = "http://$baseUrl:$port/image_gender"

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
            val processTime = jsonObject.getString("process_time").toDouble() * 100
            Log.e("latency", "边测 性别识别---服务器处理时间: ${processTime.toInt()} ms")

            return result
        } catch (e: Exception) {
            Log.e("Error", "错误: ${e.localizedMessage}")
            return "male"
        }
    }

    /**
     * 手势识别
     * @param {List<Bitmap>} - gestureFrames 摄像头采集数据
     * @return {String} - result 手势识别结果 fist / none
     */
    suspend fun gestureRecognition(gestureFrames: List<Bitmap>): String {
        try {
            val port = 18888
            val url = "http://$baseUrl:$port/detect_fist"

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
            val processTime = jsonObject.getString("process_time").toDouble()
            Log.e("latency", "边测 手势识别---服务器处理时间: ${processTime.toInt()} ms")
            return result
        } catch (e: Exception) {
            Log.e("Error", "错误: ${e.localizedMessage}")
            return ""
        }
    }

    /**
     * http中英互译（已废弃）
     */
    @Deprecated("Use translateFlow() instead", ReplaceWith("translateFlow()"))
    suspend fun translate(inputText: String): String {
        val client = createClient()
        try {

            val port = 10005
            val url = "http://$baseUrl:$port/translate_text"

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
