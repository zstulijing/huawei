package com.k2fsa.sherpa.ncnn.control

import android.graphics.Bitmap
import android.util.Log
import com.k2fsa.sherpa.ncnn.Gender
import com.k2fsa.sherpa.ncnn.GenderDetector
import com.k2fsa.sherpa.ncnn.gesture.GestureDetector
import com.k2fsa.sherpa.ncnn.request.Request
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class UnloadController(private var outVector: IntArray) {
    /**
     * 设置新的卸载向量
     */
    public fun setOutVector(newOutVector: IntArray) {
        this.outVector = newOutVector
    }

    /**
     * 调用手势识别
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
            // 发布事件并设置 result
            eventBus.publish(EventBus.Event.GESTURE_DETECTED, gesture)
            result = when (gesture) {
                GestureDetector.GestureType.FIST -> "fist"
                GestureDetector.GestureType.NONE -> "none"
                else -> "none"
            }

            val endTime = System.currentTimeMillis()
            val elapsedTime = endTime - startTime
            Log.e("xxx", "端测图像识别姿势---结果：$result, 经过时间：$elapsedTime ms")

//            gestureDetector.detectGesture(frame) { gesture ->
//                eventBus.publish(EventBus.Event.GESTURE_DETECTED, gesture)
//                if (gesture == GestureDetector.GestureType.FIST) {
//                    result = "fist"
//                } else if (gesture == GestureDetector.GestureType.NONE) {
//                    result = "none"
//                }
//                val endTime = System.currentTimeMillis()
//                val elapsedTime = endTime - startTime
//                Log.e("xxx", "端测图像识别姿势---结果：$result, 经过时间：$elapsedTime ms")

//            }


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

            Log.e("xxx", "边测姿势识别---结果: $result, 经过时间：$elapsedTime ms")

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

            // 在安卓端执行
//            val startTime = System.currentTimeMillis()
//            genderDetector.detectGender(frame) { gender ->
//
//                eventBus.publish(EventBus.Event.TRANSLATION_RESULT, gender)
//
//                if (gender == Gender.MALE) {
//                    result = "male"
//                } else if (gender == Gender.FEMALE) {
//                    result = "female"
//                } else {
//                    result = "male"
//                }
//
//                val endTime = System.currentTimeMillis()
//
//                val elapsedTime = endTime - startTime
//
//                Log.e("xxx", "端测图像识别男女：结果: $result, 经过时间: $elapsedTime ms")
//            }
            val startTime = System.currentTimeMillis()
            val gender = suspendCancellableCoroutine<Gender> { continuation ->
                genderDetector.detectGender(frame) { detectedGender ->
                    continuation.resume(detectedGender) // 恢复协程并返回性别
                }
            }

            // 发布事件并设置 result
            eventBus.publish(EventBus.Event.TRANSLATION_RESULT, gender)
            result = when (gender) {
                Gender.MALE -> "male"
                Gender.FEMALE -> "female"
                else -> "male" // 默认值
            }

            val endTime = System.currentTimeMillis()
            val elapsedTime = endTime - startTime
            Log.e("xxx", "端测图像识别男女：结果: $result, 经过时间: $elapsedTime ms")

        } else if (outVector[3] == 1) {
            // 在服务器端执行
            val startTime = System.currentTimeMillis() // 单位：毫秒

            val response: String = request.genderRecognition(frame)

            val endTime = System.currentTimeMillis()

            val elapsedTime = endTime - startTime

            result = response

            Log.e("xxx", "边测图像识别男女：结果: $result, 经过时间: $elapsedTime ms")

            // 发布事件
            var gender = Gender.MALE
            if (result == "male") {
                gender = Gender.MALE
            } else if (result == "female") {
                gender = Gender.FEMALE
            } else {
                gender = Gender.MALE
            }
            eventBus.publish(EventBus.Event.TRANSLATION_RESULT, gender)

        }
        return result
    }

}