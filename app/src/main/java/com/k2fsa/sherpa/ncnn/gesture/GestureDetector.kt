package com.k2fsa.sherpa.ncnn.gesture

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.solutions.hands.Hands
import com.google.mediapipe.solutions.hands.HandsOptions
import com.google.mediapipe.solutions.hands.HandsResult
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.pow

private const val TAG = "GestureDetector"

class GestureDetector(private val context: Context) {

    enum class GestureType {
        NONE,
        FIST
    }


    private val executor = Executors.newSingleThreadExecutor()
    private var hands: Hands? = null
    private var isInitialized = false

    init {
        initializeModel()
    }

    private fun initializeModel() {
        executor.execute {
            try {
                // Configure MediaPipe Hands
                val options = HandsOptions.builder()
                    .setStaticImageMode(true)
                    .setMaxNumHands(1)
                    .setMinDetectionConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .build()

                hands = Hands(context, options)
                isInitialized = true
                Log.i(TAG, "Gesture detection model initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing gesture detection model: ${e.message}")
            }
        }
    }

    fun detectGesture(bitmaps: List<Bitmap>, gestureCallback: ((GestureType) -> Unit)?) {
        if (!isInitialized) {
            Log.e(TAG, "Model not initialized")
            return
        }

        executor.execute {
            try {
                var foundFist = false

                for (bitmap in bitmaps) {
                    // Process each bitmap
                    val resultRef = AtomicReference<HandsResult>()
                    val latch = CountDownLatch(1)

                    hands?.setResultListener { handsResult: HandsResult ->
                        resultRef.set(handsResult)
                        latch.countDown()
                    }

                    hands?.setErrorListener { message, e ->
                        Log.e(TAG, "MediaPipe Hands error: $message", e)
                        latch.countDown()
                    }
                    hands?.send(bitmap)

                    // Wait for the result
                    latch.await()

                    // Check if a fist was detected
                    val result = resultRef.get()
                    if (result != null && isFistGesture(result)) {
                        foundFist = true
                        break  // Exit early if a fist is found
                    }
                }

                // Call the callback with the final result
                val gestureType = if (foundFist) GestureType.FIST else GestureType.NONE
                Log.i(TAG, "gesture type:${gestureType}")
                gestureCallback?.invoke(gestureType)

            } catch (e: Exception) {
                Log.e(TAG, "Error detecting gesture: ${e.message}")
                gestureCallback?.invoke(GestureType.NONE)
            }
        }
    }

    private fun isFistGesture(result: HandsResult): Boolean {
        // 检查是否检测到手部
        if (result.multiHandLandmarks().isEmpty()) {
            Log.i(TAG, "hand not found")
            return false
        }

        // 获取第一个检测到的手部关键点
        val landmarks = result.multiHandLandmarks().first().landmarkList

        // 关键点索引
        val WRIST = 0        // 手腕
        val INDEX_TIP = 8    // 食指尖
        val MIDDLE_TIP = 12  // 中指尖
        val RING_TIP = 16    // 无名指尖
        val PINKY_TIP = 20   // 小指尖

        // 获取关键点坐标
        fun getPoint(index: Int) = landmarks[index].let { android.graphics.PointF(it.x, it.y) }

        // 计算两点距离
        fun distance(p1: android.graphics.PointF, p2: android.graphics.PointF): Float {
            return Math.sqrt(
                (p1.x - p2.x).toDouble().pow(2) +
                        (p1.y - p2.y).toDouble().pow(2)
            ).toFloat()
        }

        // 手掌参考距离（手腕到食指根部）
        val palmSize = distance(getPoint(WRIST), getPoint(5))

        // 伸直判断阈值（动态调整）
        val STRAIGHT_THRESHOLD = palmSize * 1.2f

        // 计算非拇指手指到手腕的距离
        val indexDist = distance(getPoint(INDEX_TIP), getPoint(WRIST))
        val middleDist = distance(getPoint(MIDDLE_TIP), getPoint(WRIST))
        val ringDist = distance(getPoint(RING_TIP), getPoint(WRIST))
        val pinkyDist = distance(getPoint(PINKY_TIP), getPoint(WRIST))

        // 统计伸直的非拇指手指数量
        val straightFingers = listOf(indexDist, middleDist, ringDist, pinkyDist)
            .count { it > STRAIGHT_THRESHOLD }

        // 当伸直的手指少于2个时判断为拳头
        return straightFingers < 2
    }

    fun close() {
        try {
            executor.shutdown()
            hands?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down gesture detector: ${e.message}")
        }
    }
}

//    private fun isFistGesture(result: HandsResult): Boolean {
//        // Check if any hands were detected
//        if (result.multiHandLandmarks().isEmpty()) {
//            Log.i(TAG,"hand not found")
//            return false
//        }
//
//        // Get the landmarks for the first detected hand
//        val landmarks = result.multiHandLandmarks().first().landmarkList
//
//        // Key landmark indices
//        val WRIST = 0       // Wrist
//        val THUMB_TIP = 4   // Thumb tip
//        val INDEX_TIP = 8   // Index finger tip
//        val MIDDLE_TIP = 12 // Middle finger tip
//        val RING_TIP = 16   // Ring finger tip
//        val PINKY_TIP = 20  // Pinky tip
//
//        // Calculate distances
//        fun getPoint(index: Int) = landmarks[index].let { android.graphics.PointF(it.x, it.y) }
//
//        // Calculate Euclidean distance between two points
//        fun distance(p1: android.graphics.PointF, p2: android.graphics.PointF): Float {
//            return Math.sqrt(
//                Math.pow((p1.x - p2.x).toDouble(), 2.0) +
//                        Math.pow((p1.y - p2.y).toDouble(), 2.0)
//            ).toFloat()
//        }
//
//        // Reference palm size (distance from wrist to index MCP)
//        val palmSize = distance(getPoint(WRIST), getPoint(5))
//
//        // Threshold for determining if a finger is extended or not
//        val STRAIGHT_THRESHOLD = palmSize * 0.5
//
//        // Check distances from fingertips to palm center
//        val palmCenter = getPoint(9) // Middle finger MCP is roughly the palm center
//
//        // Check if tips are close to palm (bent fingers)
//        val indexToPalm = distance(getPoint(INDEX_TIP), palmCenter)
//        val middleToPalm = distance(getPoint(MIDDLE_TIP), palmCenter)
//        val ringToPalm = distance(getPoint(RING_TIP), palmCenter)
//        val pinkyToPalm = distance(getPoint(PINKY_TIP), palmCenter)
//
//        // Fingers are considered bent if close to palm
//        val fingersBent = listOf(
//            indexToPalm < STRAIGHT_THRESHOLD,
//            middleToPalm < STRAIGHT_THRESHOLD,
//            ringToPalm < STRAIGHT_THRESHOLD,
//            pinkyToPalm < STRAIGHT_THRESHOLD
//        ).count { it } >= 3 // At least 3 fingers need to be bent
//
//        // Extra check: fingertips should be lower than knuckles for a fist
//        val indexBent = getPoint(INDEX_TIP).y > getPoint(5).y
//        val middleBent = getPoint(MIDDLE_TIP).y > getPoint(9).y
//        val ringBent = getPoint(RING_TIP).y > getPoint(13).y
//        val pinkyBent = getPoint(PINKY_TIP).y > getPoint(17).y
//
//        val fingersCurled = listOf(indexBent, middleBent, ringBent, pinkyBent).count { it } >= 3
//
//        // Detect fist: fingers are bent and curled
//        return fingersBent && fingersCurled
//    }