package com.k2fsa.sherpa.ncnn

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.face.Face
import org.tensorflow.lite.Interpreter
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.common.InputImage
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.ResizeOp.ResizeMethod
import java.nio.ByteBuffer
import java.util.concurrent.ExecutionException
import org.tensorflow.lite.support.common.ops.NormalizeOp
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


enum class Gender {
    UNKNOWN,
    MALE,
    FEMALE
}

class GenderDetector(private val context: Context) {
    // 单线程执行器（用于后台处理）
    private val executor = Executors.newSingleThreadExecutor()
    // UI线程Handler（用于结果回调）
    private val mainHandler = Handler(Looper.getMainLooper())

    // 图像处理相关
    private val inputImageSize = 128
    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(inputImageSize, inputImageSize, ResizeMethod.BILINEAR))
        .add(NormalizeOp(0f, 255f))
        .build()

    // TFLite模型解释器
    private var interpreter: Interpreter? = null

    // ML Kit人脸检测器
    private val faceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setMinFaceSize(0.1f)
            .enableTracking()
            .build()
        FaceDetection.getClient(options)
    }

    init {
        initializeInterpreter()
    }

    private fun initializeInterpreter() {
        try {
            val modelFile = FileUtil.loadMappedFile(context, "model_gender_nonq.tflite")
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseXNNPACK(true)
            }
            interpreter = Interpreter(modelFile, options)
        } catch (e: Exception) {
            Log.e("GenderClassification", "模型加载失败", e)
        }
    }

    /**
     * 异步性别检测入口
     * @param bitmaps 需要处理的帧列表
     * @param genderCallback 结果回调（在主线程执行）
     */
    fun detectGender(bitmaps: List<Bitmap>, genderCallback: (Gender) -> Unit) {
        if (bitmaps.isEmpty()) {
            notifyResult(Gender.UNKNOWN, genderCallback)
            return
        }

        executor.execute {
            val result = processFramesSequentially(bitmaps)
            notifyResult(result, genderCallback)
        }
    }

    /**
     * 顺序处理帧队列
     */
    private fun processFramesSequentially(bitmaps: List<Bitmap>): Gender {
        for (bitmap in bitmaps) {
            val result = detectGenderInSingleFrame(bitmap)
            if (result != Gender.UNKNOWN) {
                return result
            }
        }
        return Gender.UNKNOWN
    }

    /**
     * 单帧处理（在后台线程执行）
     */
    private fun detectGenderInSingleFrame(bitmap: Bitmap): Gender {
        return try {
            Log.i("GenderClassifier", "Processing frame: ${bitmap.width}x${bitmap.height}")

            // 人脸检测（同步调用，但已在后台线程）
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val faces = Tasks.await(faceDetector.process(inputImage))

            if (faces.isEmpty()) return Gender.UNKNOWN

            val bestFace = selectBestFace(faces, bitmap)
            if (!validateFaceSize(bestFace, bitmap)) return Gender.UNKNOWN

            // 裁剪并分类
            val faceBitmap = cropFace(bitmap, bestFace.boundingBox)
            classifyGender(faceBitmap)
        } catch (e: Exception) {
            Log.e("GenderClassifier", "Detection error: ${e.message}")
            Gender.UNKNOWN
        }
    }

    // 选择最佳人脸（保持原有逻辑）
    private fun selectBestFace(faces: List<Face>, bitmap: Bitmap): Face {
        return if (faces.size > 1) {
            faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: faces[0]
        } else {
            faces[0]
        }
    }

    // 面部尺寸验证
    private fun validateFaceSize(face: Face, bitmap: Bitmap): Boolean {
        return face.boundingBox.width() >= bitmap.width * 0.15 &&
                face.boundingBox.height() >= bitmap.height * 0.15
    }

    // 裁剪人脸区域（保持原有逻辑）
    private fun cropFace(source: Bitmap, bbox: Rect): Bitmap {
        val widthPadding = (bbox.width() * 0.3).toInt()
        val heightPadding = (bbox.height() * 0.3).toInt()
        val left = max(0, bbox.left - widthPadding)
        val top = max(0, bbox.top - heightPadding)
        val width = min(source.width - left, bbox.width() + 2 * widthPadding)
        val height = min(source.height - top, bbox.height() + 2 * heightPadding)
        return Bitmap.createBitmap(source, left, top, width, height)
    }

    // 性别分类（保持原有逻辑）
    private fun classifyGender(faceBitmap: Bitmap): Gender {
        val tensorImage = TensorImage.fromBitmap(faceBitmap)
        val processedImage = imageProcessor.process(tensorImage)
        val output = Array(1) { FloatArray(2) }
        interpreter?.run(processedImage.buffer, output)
        return if (output[0][0] > output[0][1]) Gender.MALE else Gender.FEMALE
    }

    // 安全线程回调
    private fun notifyResult(result: Gender, callback: (Gender) -> Unit) {
        mainHandler.post { callback(result) }
    }

    fun close() {
        executor.shutdownNow()
        interpreter?.close()
        interpreter = null
    }

//    enum class Gender { MALE, FEMALE, UNKNOWN }
}

//package com.k2fsa.sherpa.ncnn
//
//import android.content.Context
//import android.graphics.Bitmap
//import android.graphics.Rect
//import android.util.Log
//import com.google.android.gms.tasks.Tasks
//import com.google.mlkit.vision.face.Face
//import org.tensorflow.lite.Interpreter
//import com.google.mlkit.vision.face.FaceDetection
//import com.google.mlkit.vision.face.FaceDetectorOptions
//import com.google.mlkit.vision.common.InputImage
//import org.tensorflow.lite.support.common.FileUtil
//import org.tensorflow.lite.support.image.TensorImage
//import org.tensorflow.lite.support.image.ImageProcessor
//import org.tensorflow.lite.support.image.ops.ResizeOp
//import org.tensorflow.lite.support.image.ops.ResizeOp.ResizeMethod
//import java.nio.ByteBuffer
//import java.util.concurrent.ExecutionException
//import org.tensorflow.lite.support.common.ops.NormalizeOp
//import kotlin.math.abs
//import kotlin.math.max
//import kotlin.math.min
//
//enum class Gender {
//    UNKNOWN,
//    MALE,
//    FEMALE
//}
//
//class GenderDetector(private val context: Context) {
//
//    // 输入尺寸和图像处理器
//    private val inputImageSize = 128
//    private val imageProcessor = ImageProcessor.Builder()
//        .add(ResizeOp(inputImageSize, inputImageSize, ResizeMethod.BILINEAR))
//        .add(NormalizeOp(0f, 255f))
//        .build()
//
//    // TFLite解释器和推理时间
//    private var interpreter: Interpreter? = null
//    var inferenceTime: Long = 0
//
//    // ML Kit人脸检测器
//    private val faceDetector by lazy {
//        val options = FaceDetectorOptions.Builder()
//            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
//            .setMinFaceSize(0.1f) // Keep this to detect smaller faces
//            .enableTracking() // Keep tracking for consistent detection
//            .build()
//        FaceDetection.getClient(options)
//    }
//
//    init {
//        initializeInterpreter()
//    }
//
//    private fun initializeInterpreter() {
//        try {
//            val modelFile = FileUtil.loadMappedFile(context, "model_gender_nonq.tflite")
//
//            // Add interpreter options for better performance
//            val options = Interpreter.Options().apply {
//                setNumThreads(4) // Adjust based on device capabilities
//                setUseXNNPACK(true) // Ensure XNNPACK is enabled
//            }
//
//            interpreter = Interpreter(modelFile, options)
//        } catch (e: Exception) {
//            Log.e("GenderClassification", "模型加载失败", e)
//        }
//    }
//
//    /**
//     * Detect gender from a list of bitmaps (frames)
//     * Returns as soon as a gender is detected, or UNKNOWN if no gender detected in any frame
//     */
//    fun detectGender(bitmaps: List<Bitmap>, genderCallback: ((Gender) -> Unit)?) {
//        if (bitmaps.isEmpty()) {
//            genderCallback?.invoke(Gender.UNKNOWN)
//            return
//        }
//
//        for (bitmap in bitmaps) {
//            val result = detectGenderInSingleFrame(bitmap)
//            if (result != Gender.UNKNOWN) {
//                genderCallback?.invoke(result)
//                return
//            }
//        }
//        genderCallback?.invoke(Gender.UNKNOWN)
//    }
//
//    private fun detectGenderInSingleFrame(bitmap: Bitmap): Gender {
//        return try {
//            Log.i("GenderClassifier", "Processing frame: ${bitmap.width}x${bitmap.height}")
//
//            // Step 1: Face detection
//            val inputImage = InputImage.fromBitmap(bitmap, 0)
//            val startTime = System.currentTimeMillis()
//            val faces = Tasks.await(faceDetector.process(inputImage))
//            val detectionTime = System.currentTimeMillis() - startTime
//
//            Log.i("GenderClassifier", "Face detection completed in ${detectionTime}ms, found ${faces.size} faces")
//
//            if (faces.isEmpty()) {
//                Log.w("GenderClassifier", "No faces detected in the frame")
//                Gender.UNKNOWN
//            } else {
//                // Get the face with highest confidence or largest area
//                val bestFace = if (faces.size > 1) {
//                    // Use area as primary selection criteria
//                    faces.maxByOrNull {
//                        it.boundingBox.width() * it.boundingBox.height()
//                    } ?: faces[0]
//                } else {
//                    faces[0]
//                }
//
//                Log.i("GenderClassifier", "Selected best face: bounds=${bestFace.boundingBox}")
//
//                // Image quality check
//                if (bitmap.width < 100 || bitmap.height < 100) {
//                    Log.w("GenderClassifier", "Image quality too low: ${bitmap.width}x${bitmap.height}")
//                    return Gender.UNKNOWN
//                }
//
//                // Face size check
//                if (bestFace.boundingBox.width() < bitmap.width * 0.15 ||
//                    bestFace.boundingBox.height() < bitmap.height * 0.15) {
//                    Log.w("GenderClassifier", "Face too small: " +
//                            "face=${bestFace.boundingBox.width()}x${bestFace.boundingBox.height()}, " +
//                            "threshold=${bitmap.width * 0.15}x${bitmap.height * 0.15}")
//                    return Gender.UNKNOWN
//                }
//
//                // Head pose check
//                bestFace.headEulerAngleY?.let {
//                    if (abs(it) > 15) {
//                        Log.w("GenderClassifier", "Face angle too large: Y=${it} degrees")
//                        return Gender.UNKNOWN
//                    }
//                }
//
//                // Crop face and classify
//                val faceBitmap = cropFace(bitmap, bestFace.boundingBox)
//                Log.i("GenderClassifier", "Cropped face dimensions: ${faceBitmap.width}x${faceBitmap.height}")
//
//                val result = classifyGender(faceBitmap)
//                Log.i("GenderClassifier", "Classification result: $result")
//                result
//            }
//        } catch (e: ExecutionException) {
//            Log.e("GenderClassifier", "Face detection failed with ExecutionException", e)
//            Gender.UNKNOWN
//        } catch (e: InterruptedException) {
//            Log.e("GenderClassifier", "Face detection interrupted", e)
//            Gender.UNKNOWN
//        } catch (e: Exception) {
//            Log.e("GenderClassifier", "Unexpected error during classification", e)
//            Gender.UNKNOWN
//        }
//    }
//
//    private fun cropFace(source: Bitmap, bbox: Rect): Bitmap {
//        // Use proportional padding based on face size
//        val widthPadding = (bbox.width() * 0.3).toInt()
//        val heightPadding = (bbox.height() * 0.3).toInt()
//
//        val left = max(0, bbox.left - widthPadding)
//        val top = max(0, bbox.top - heightPadding)
//        val width = min(source.width - left, bbox.width() + 2 * widthPadding)
//        val height = min(source.height - top, bbox.height() + 2 * heightPadding)
//
//        return Bitmap.createBitmap(source, left, top, width, height)
//    }
//
//    private fun classifyGender(faceBitmap: Bitmap): Gender {
//        val startTime = System.currentTimeMillis()
//
//        val tensorImage = TensorImage.fromBitmap(faceBitmap)
//        val processedImage = imageProcessor.process(tensorImage)
//        val inputBuffer: ByteBuffer = processedImage.buffer
//        val output = Array(1) { FloatArray(2) }
//
//        interpreter?.run(inputBuffer, output)
//        inferenceTime = System.currentTimeMillis() - startTime
//
//        Log.d("GenderClassification",
//            "性别概率: [男=${"%.2f".format(output[0][0])}, 女=${"%.2f".format(output[0][1])}] " +
//                    "耗时: ${inferenceTime}ms")
//
//        return if (output[0][0] > output[0][1]) Gender.MALE else Gender.FEMALE
//    }
//
//    fun close() {
//        interpreter?.close()
//        interpreter = null
//    }
//}
