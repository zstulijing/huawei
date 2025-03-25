package com.k2fsa.sherpa.ncnn.request


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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import kotlin.random.Random
import kotlinx.coroutines.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString



class Request {

    /**
     * 服务器IP地址
     */
    private val baseUrl: String = "10.109.246.210"

    // 创建带超时的 HttpClient
    private fun createClient(): HttpClient {
        return HttpClient(CIO) {
            engine {
                requestTimeout = 5_000 // 请求超时：5秒，因为每个每次捕获图像经过5秒
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
     * @param {Context} - context 安卓上下文
     * @param {String} - gender 性别 male / female
     * @param {String} - language 语言 en / zh
     * @return {String} - filePath 文件路径
     */
    suspend fun textToSpeech(inputText: String, context: Context, gender: String = "male", language: String  = "en"): String {
        val client = createClient()
        try {
            val port = 10002
            val url = "http://$baseUrl:$port/text_to_speech"
            val jsonBody = """{"input": "$inputText", "gender": "$gender", "language": "$language"}"""

            val response: HttpResponse = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(jsonBody)
            }
            val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
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
     * @param {List<Bitmap>} - gestureFrames 摄像头采集数据
     * @return {String} - result 性别识别结果 male / female
     */
    suspend fun genderRecognition(gestureFrames: List<Bitmap>): String {
        val client = HttpClient(CIO)
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
     * @param {List<Bitmap>} - gestureFrames 摄像头采集数据
     * @return {String} - result 手势识别结果 fist / none
     */
    suspend fun gestureRecognition(gestureFrames: List<Bitmap>): String {
        val client = HttpClient(CIO)
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
            return result
        } catch (e: Exception) {
            Log.e("Error", "错误: ${e.localizedMessage}")
            return ""
        } finally {
            client.close()
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

    /**
     * 流式翻译
     */

    // 模拟流式数据生成
    private fun generateData(): Flow<TextMessage> = flow {
        var msg_id = 0
        while (true) {
            emit(TextMessage(msg_id, "这是一个数据--${Random.nextInt(1, 100)}"))
            msg_id++
            delay(10000) // 每秒生成一个数据
        }
    }

    // 发送数据
    private suspend fun sendData(session: DefaultClientWebSocketSession) {
        try {
            generateData().collect { data ->
                val json = Json.encodeToString(data)
                Log.e("websocket", "发送中: $json")
                session.send(json)
            }
        } catch (e: Exception) {
            Log.e("websocket", "发送数据时出错: ${e.message}")
        }
    }

    // 接收数据
    private suspend fun receiveData(session: DefaultClientWebSocketSession) {
        try {
            for (frame in session.incoming) {

                if (frame is Frame.Text) {
                    val response = frame.readText()
                    try {
                        val data = Json.decodeFromString<TextMessage>(response)
                        Log.e("websocket", "收到: msg_id=${data.msg_id}, content=${data.content}")
                    } catch (e: Exception) {
                        Log.e("websocket", "收到无效的 JSON 数据: $response")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("websocket", "接收数据时出错: ${e.message}")
        }
    }

    // 连接 WebSocket 服务器
    suspend fun streamData() {

        val client = HttpClient(CIO) {
            install(WebSockets)
        }

        try {
            val port = 8765
            client.webSocket("ws://$baseUrl:$port") {
                Log.e("websocket", "已连接到 WebSocket 服务器！")

                // 发送注册消息
                val registerMsg = RegisterMessage("client_001", "APP", "LeiNiao")
                send(Json.encodeToString(registerMsg))
                Log.e("websocket", "已发送注册信息：$registerMsg")

                // 启动发送和接收的协程
                coroutineScope {
                    launch { sendData(this@webSocket) }
                    launch { receiveData(this@webSocket) }
                }
            }
        } catch (e: Exception) {
            Log.e("websocket", "连接错误: ${e.message}")
        } finally {
            client.close()
        }
    }

    /**
     * 上行情况测试
     */
    suspend fun testUpSpeed(size: Int): Long {
        val client = createClient()
        try {
            val port = 10005
            val url = "http://$baseUrl:$port/test_speed"

            // 手动构造请求的 JSON 字符串
            val jsonRequestBody = """{"type": "test_up","size": $size}"""



            val startTime = System.nanoTime()

            val response: HttpResponse = client.post(url) {
                contentType(ContentType.Application.Json) // 设置请求内容类型
                setBody(jsonRequestBody) // 发送 JSON 字符串
            }

            // 获取响应文本并手动解析 JSON
//            val responseText = response.bodyAsText()
//
//            val jsonResponse = JSONObject(responseText)
//            val receiveTime = jsonResponse.getString("receive_time").toLong() // 提取 "receive_time" 字段
//            val serverTimestamp = jsonResponse.getString("server_timestamp").toLong() // 提取 "server_timestamp" 字段
//
//            val timeOffset = serverTimestamp - startTime // 计算服务器时间和本地时间的差异
//            val adjustedReceiveTime = receiveTime + timeOffset // 校正 receive_time

            val endTime = System.nanoTime()
            val elapsedTimeMs = (endTime - startTime) / 1_000_000 // 转换为毫秒

            return elapsedTimeMs

        } catch (e: Exception) {
            Log.e("Error", "错误: ${e.localizedMessage}")
            return 0
        } finally {
            client.close()
        }
    }

}
