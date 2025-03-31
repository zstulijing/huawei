package com.k2fsa.sherpa.ncnn.control

import android.graphics.Bitmap
import android.util.Log
import com.k2fsa.sherpa.ncnn.Gender
import com.k2fsa.sherpa.ncnn.GenderDetector
import com.k2fsa.sherpa.ncnn.recorder.SpeechRecognizer
import com.k2fsa.sherpa.ncnn.gesture.GestureDetector
import com.k2fsa.sherpa.ncnn.request.Request
import com.k2fsa.sherpa.ncnn.translation.LlmTranslator
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class UnloadController(private var outVector: IntArray) {
    /**
     * 设置新的卸载向量
     */
    private val length = 8

    fun setOutVector(newOutVector: IntArray) {
        this.outVector = newOutVector
    }

    /**
     * 语音转文本
     * @return {String} result - 识别的文本
     */
     suspend fun soundToText(
        speechRecognizer: SpeechRecognizer,
        eventBus: EventBus,
        request: Request,
        samples: FloatArray
    ): String {

        return when (outVector[0]) {
            0 -> {
                // 安卓端执行
                suspendCoroutine { continuation ->
                    speechRecognizer.recognize(samples) { result ->
                        Log.e("result", "端侧 语音转文本--结果: $result")
                        eventBus.publish(EventBus.Event.SPEECH_RESULT, result)
                        continuation.resume(result) // 将结果返回给挂起函数
                    }
                }
            }
            1 -> {
                // 服务器端执行，返回特殊字符，用于逻辑控制
                "<|WS|>"
            }
            else -> {
                ""
            }
        }

    }

    /**
     * 中英互译
     */
    suspend fun translate(result: String, translationManager: LlmTranslator, callback: suspend () -> Unit): String {
        if (outVector[1] == 0) { // 在安卓端执行

            val startTime = System.currentTimeMillis() // 单位：毫秒

            val translatedResult = translationManager.translate(result)
            // 记录请求结束时间
            val endTime = System.currentTimeMillis()

            // 计算经过时间（单位：毫秒）
            val elapsedTime = endTime - startTime
            Log.e("result", "端测 中英互译---结果: $translatedResult")
            Log.e("latency", "端测 中英互译---经过时间: $elapsedTime ms")
            return translatedResult

        } else if (outVector[1] == 1) { // 在服务器端执行
            callback()
            return ""
        }
        return ""
    }


    /**
     *
     */

    /**
     * 图像识别手势
     * @return {String} result - "fist"/"none"
     */
    public suspend fun gestureRecognition(
        gestureDetector: GestureDetector,
        eventBus: EventBus,
        request: Request,
        frame: List<Bitmap>
    ): String {

        var result = "none"

        if (outVector[4] == 0) { // 在安卓端执行

            val startTime = System.currentTimeMillis()
            // 将 detectGesture 的回调转换为挂起函数返回值

            val gesture = suspendCancellableCoroutine<GestureDetector.GestureType> { continuation ->
                gestureDetector.detectGesture(frame) { detectedGesture ->
                    continuation.resume(detectedGesture) // 恢复协程并返回结果
                }
            }
            val endTime = System.currentTimeMillis()
            val elapsedTime = endTime - startTime

            // 发布事件并设置 result
            eventBus.publish(EventBus.Event.GESTURE_DETECTED, gesture)
            result = when (gesture) {
                GestureDetector.GestureType.FIST -> "fist"
                GestureDetector.GestureType.NONE -> "none"
                else -> "none"
            }

            Log.e("result", "端测 手势识别---结果: $result")
            Log.e("latency", "端测 手势识别---经过时间: $elapsedTime ms")


        } else if (outVector[4] == 1) { // 在服务器执行

            val startTime = System.currentTimeMillis()

            val response: String = request.gestureRecognition(frame)

            val endTime = System.currentTimeMillis()
            val elapsedTime = endTime - startTime

            var gesture = GestureDetector.GestureType.NONE
            // 发布事件
            if (response == "other") {
                gesture = GestureDetector.GestureType.NONE
                result = "none"
            } else if (response == "fist") {
                gesture = GestureDetector.GestureType.FIST
                result = "fist"
            }
            eventBus.publish(EventBus.Event.GESTURE_DETECTED, gesture)

            Log.e("result", "边测 手势识别---结果: $result")
            Log.e("latency", "边测 手势识别---经过时间: $elapsedTime ms")
        }
        return result
    }

    /**
     * 图像识别男女
     * @return {String} result - "male"/"female"
     */
    public suspend fun genderRecognition(
        genderDetector: GenderDetector,
        eventBus: EventBus,
        request: Request,
        frame: List<Bitmap>): String {

        var result = "male"

        if (outVector[3] == 0) {
            // 端侧
            val startTime = System.currentTimeMillis()
            val gender = suspendCancellableCoroutine<Gender> { continuation ->
                genderDetector.detectGender(frame) { detectedGender ->
                    continuation.resume(detectedGender) // 恢复协程并返回性别
                }
            }
            val endTime = System.currentTimeMillis()
            val elapsedTime = endTime - startTime
            result = when (gender) {
                Gender.MALE -> "male"
                Gender.FEMALE -> "female"
                else -> "male" // 默认值
            }

            Log.e("result", "端测 性别识别---结果: $result")
            Log.e("latency", "端测 性别识别---经过时间: $elapsedTime ms")

        } else if (outVector[3] == 1) {
            // 在服务器端执行
            val startTime = System.currentTimeMillis() // 单位：毫秒

            val response: String = request.genderRecognition(frame)

            val endTime = System.currentTimeMillis()

            val elapsedTime = endTime - startTime

            result = response

            Log.e("result", "边测 性别识别---结果: $result")
            Log.e("latency", "边测 性别识别---经过时间: $elapsedTime ms")

        }
        return result
    }

}