package com.k2fsa.sherpa.ncnn.control

import android.content.Context
import android.gesture.Gesture
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.k2fsa.sherpa.ncnn.Gender
import com.k2fsa.sherpa.ncnn.GenderDetector
import com.k2fsa.sherpa.ncnn.gesture.GestureDetector
//import com.k2fsa.sherpa.ncnn.translation.TranslationManager
import com.k2fsa.sherpa.ncnn.translation.LlmTranslator
import com.k2fsa.sherpa.ncnn.AudioRecorder
import com.k2fsa.sherpa.ncnn.SpeechRecognizer
import com.k2fsa.sherpa.ncnn.video.CameraManager
import com.k2fsa.sherpa.ncnn.video.VideoFrameProcessor
import com.k2fsa.sherpa.ncnn.utils.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.log
import androidx.lifecycle.MutableLiveData

class AppController private constructor() {
    private val logger = Logger(this::class.java.simpleName)
    private val eventBus = EventBus()
    private val mainScope = CoroutineScope(Dispatchers.Main)


    // Modules
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var cameraManager: CameraManager
    private lateinit var videoFrameProcessor: VideoFrameProcessor
    private lateinit var gestureDetector: GestureDetector
    private lateinit var genderDetector: GenderDetector
//    private lateinit var translationManager: TranslationManager

    private lateinit var translationManager: LlmTranslator
    // Callbacks
    private var recognitionCallback: ((String) -> Unit)? = null
    private var translationCallback: ((String) -> Unit)? = null

    private var gestureCallback: ((GestureDetector.GestureType) -> Unit)? = null
    private var genderCallback: ((Gender) -> Unit)? = null
    private var statusCallback: ((String) -> Unit)? = null

    // State management
    private var isRecording = false
    private var videoCaptureJob: Job? = null
    private var isInitialized = false

    // Initialize modules with activity context
    //, assets: AssetManager
    private lateinit var context: Context

    // request请求类
    private var request = Request()
    // 性别
    private var parasmGender = "male"
    /**
     * 卸载算法输出
     * [0]: 语音转文本，一定是0（安卓端）
     * [1]: 中英互译
     * [2]: 文本转语音
     * [3]: 图像识别男女
     * [4]: 图像识别手势
     */
    private var outVecotr = intArrayOf(0, 0, 0, 0 ,0)

    fun initialize(activity: AppCompatActivity) {
        if (isInitialized) return
        context = activity
        val assets = activity.assets

        // Initialize audio modules
        audioRecorder = AudioRecorder(activity)
        speechRecognizer = SpeechRecognizer(assets)

        // Initialize video modules
        cameraManager = CameraManager(activity)
//        videoFrameProcessor = VideoFrameProcessor()
        videoFrameProcessor = VideoFrameProcessor(activity.baseContext,debugSaveImages = true)

        // Initialize AI modules
        gestureDetector = GestureDetector(activity)
        genderDetector = GenderDetector(activity)
//        translationManager = TranslationManager()
        translationManager = LlmTranslator()

        setupEventListeners()
        isInitialized = true
    }

    private fun setupEventListeners() {
        // Handle speech recognition results
        eventBus.subscribe(EventBus.Event.SPEECH_RESULT) { data ->
            val recognizedText = data as String
            recognitionCallback?.invoke(recognizedText)

            // Send recognized text for translation
            if (recognizedText.isNotEmpty()) {
                CoroutineScope(Dispatchers.Main).launch {
                    val translatedText = translationManager.translate(recognizedText)
                    eventBus.publish(EventBus.Event.TRANSLATION_RESULT, translatedText)
                }
            }
        }

        // Handle translation results
        eventBus.subscribe(EventBus.Event.TRANSLATION_RESULT) { data ->
            val translatedText = data as String
            translationCallback?.invoke(translatedText)
        }

        // Handle gesture detection results
        eventBus.subscribe(EventBus.Event.GESTURE_DETECTED) { data ->
            val gesture = data as GestureDetector.GestureType
            gestureCallback?.invoke(gesture)
        }

        // Handle gender detection results
        eventBus.subscribe(EventBus.Event.GENDER_DETECTED) { data ->
            val gender = data as Gender
            genderCallback?.invoke(gender)
        }
    }

    /**
     * 捕获音频
     */
    fun startRecording() {
        checkInitialization()
        if (isRecording) return

        isRecording = true
        statusCallback?.invoke("Recording started")

        val job = Job() // 创建一个 Job 用于控制协程
        val scope = CoroutineScope(Dispatchers.IO + job)


        audioRecorder.startRecording{ samples ->

            var startRecordTime = System.currentTimeMillis() // 单位：毫秒
            speechRecognizer.recognize(samples) { result ->
                var endRecordTime = System.currentTimeMillis() // 单位：毫秒
                Log.e("stt", "端侧语音转文本：结果：$result, 经过时间: ${endRecordTime - startRecordTime} ms")

                /**
                 * {string} - result 语言转的文本
                 */
                eventBus.publish(EventBus.Event.SPEECH_RESULT, result)
                /**
                 * 中英互译
                 */
                var translatedText = ""
                if (outVecotr[1] == 0) {
                    // 安卓端执行

                    // 记录请求开始时间
                    val startTime = System.currentTimeMillis() // 单位：毫秒

                    val translator = LlmTranslator()
                    CoroutineScope(Dispatchers.Main).launch {
                        translatedText = translator.translate(result)
                        // 记录请求结束时间
                        val endTime = System.currentTimeMillis()

                        // 计算经过时间（单位：毫秒）
                        val elapsedTime = endTime - startTime
                        Log.e("xxx", "端测中英互译：结果: $translatedText, 经过时间：$elapsedTime ms")


                        eventBus.publish(EventBus.Event.TRANSLATION_RESULT, translatedText)


                        /**
                         * 文本转语音
                         */
                        if (outVecotr[2] == 0) {
                            // 安卓端执行
                            Log.e("" , "端测文本转语音")
                        } else if (outVecotr[2] == 1) {
                            // 服务器端执行

                            // 记录请求开始时间
                            val startTime2 = System.currentTimeMillis() // 单位：毫秒

                            val filePath: String = request.textToSpeech(translatedText, context, parasmGender)

                            // 记录请求结束时间
                            val endTime2 = System.currentTimeMillis()

                            // 计算经过时间（单位：毫秒）
                            val elapsedTime2 = endTime2 - startTime2

                            // 打印结果和经过时间
                            Log.e("xxx", "边测文本转语音：文字: $translatedText, 存储路径：$filePath, 经过时间: $elapsedTime2 ms")

                            eventBus.publish(EventBus.Event.TRANSLATION_RESULT, translatedText)

                        }

                    }




                } else if (outVecotr[1] == 1) {
                    // 服务器端执行

                    scope.launch {
                        // 记录请求开始时间
                        val startTime = System.currentTimeMillis() // 单位：毫秒

                        translatedText = request.translate(result)

                        // 记录请求结束时间
                        val endTime = System.currentTimeMillis()

                        // 计算经过时间（单位：毫秒）
                        val elapsedTime = endTime - startTime

                        // 打印结果和经过时间
                        Log.e("xxx", "边测中英互译：结果: $translatedText, 经过时间: $elapsedTime ms")


                        eventBus.publish(EventBus.Event.TRANSLATION_RESULT, translatedText)

                        /**
                         * 文本转语音
                         */
                        if (outVecotr[2] == 0) {
                            // 安卓端执行
                            Log.e("" , "端测文本转语音")
                        } else if (outVecotr[2] == 1) {
                            // 服务器端执行

                            // 记录请求开始时间
                            val startTime2 = System.currentTimeMillis() // 单位：毫秒

                            val filePath: String = request.textToSpeech(translatedText, context, parasmGender)

                            // 记录请求结束时间
                            val endTime2 = System.currentTimeMillis()

                            // 计算经过时间（单位：毫秒）
                            val elapsedTime2 = endTime2 - startTime2

                            // 打印结果和经过时间
                            Log.e("xxx", "边测文本转语音：文字: $translatedText, 存储路径：$filePath, 经过时间: $elapsedTime2 ms")

                            eventBus.publish(EventBus.Event.TRANSLATION_RESULT, translatedText)

                        }
                    }

                }


            }
        }
    }

    fun stopRecording() {
        checkInitialization()
        if (!isRecording) return

        isRecording = false
        statusCallback?.invoke("Recording stopped")

        // Stop audio recording
        audioRecorder.stopRecording()
    }

    fun onResume() {
        checkInitialization()
        // Start periodic video capture for gesture and gender detection
        startPeriodicVideoCapture()
    }

    fun onPause() {
        checkInitialization()
        // Stop video capture
        stopPeriodicVideoCapture()

        // Ensure recording is stopped
        if (isRecording) {
            stopRecording()
        }
    }

    fun onDestroy() {
        checkInitialization()
        // Clean up resources
        stopPeriodicVideoCapture()
        eventBus.clear()

        audioRecorder.release()
        speechRecognizer.shutdown()
        cameraManager.release()
        videoFrameProcessor.shutdown()
        gestureDetector.close()
        genderDetector.close()
//        translationManager.close()
    }

    private fun startPeriodicVideoCapture() {
        stopPeriodicVideoCapture()
        videoCaptureJob = mainScope.launch {
            while (true) {
                captureAndProcessVideoFrames()
                delay(5000) // Wait 5 seconds before next capture
            }
        }
    }

    private fun stopPeriodicVideoCapture() {
        videoCaptureJob?.cancel()
        videoCaptureJob = null
    }

    /**
     * 捕获图像
     */
    private fun captureAndProcessVideoFrames() {
        val job = Job() // 创建一个 Job 用于控制协程
        val scope = CoroutineScope(Dispatchers.IO + job)

        cameraManager.captureFrames(5) { frames ->
            frames.forEach { frameInfo ->
                videoFrameProcessor.addFrame(
                    frameInfo.data,
                    frameInfo.width,
                    frameInfo.height
                )
            }

            /**
             * 图像识别男女
             */
            val genderFrames = videoFrameProcessor.getFramesForGenderDetection(1)
            if (outVecotr[3] == 0) {
                // 在安卓端执行

                // 记录请求开始时间
                val startTime = System.currentTimeMillis() // 单位：毫秒

                genderDetector.detectGender(genderFrames) { gender ->
                    eventBus.publish(EventBus.Event.TRANSLATION_RESULT, gender)
                    if (gender == Gender.MALE) {
                        parasmGender = "male"
                    } else if (gender == Gender.FEMALE) {
                        parasmGender = "female"
                    } else {
                        parasmGender = "male"
                    }
                    // 记录请求结束时间
                    val endTime = System.currentTimeMillis()

                    // 计算经过时间（单位：毫秒）
                    val elapsedTime = endTime - startTime

                    Log.e("xxx", "端测图像识别男女：结果: $parasmGender, 经过时间: $elapsedTime ms")
                }


            } else if (outVecotr[3] == 1) {
                // 在服务器端执行
                scope.launch {
                    // 记录请求开始时间
                    val startTime = System.currentTimeMillis() // 单位：毫秒

                    var result: String = request.genderRecognition(genderFrames)

                    // 记录请求结束时间
                    val endTime = System.currentTimeMillis()

                    // 计算经过时间（单位：毫秒）
                    val elapsedTime = endTime - startTime

                    // 打印结果和经过时间
                    Log.e("xxx", "边测图像识别男女：结果: $result, 经过时间: $elapsedTime ms")

//                    parasmGender = result

                    // 发布事件
                    eventBus.publish(EventBus.Event.TRANSLATION_RESULT, result)

                }


            }


            /**
             * 图像识别姿势
             */
            val gestureFrames = genderFrames
            if (outVecotr[4] == 0) {
                // 在安卓端执行
                // 记录请求开始时间
                val startTime = System.currentTimeMillis() // 单位：毫秒
                var paramGesture = "none"
                gestureDetector.detectGesture(gestureFrames) { gesture ->
                    eventBus.publish(EventBus.Event.GESTURE_DETECTED, gesture)
                    if (gesture == GestureDetector.GestureType.FIST) {
                        paramGesture = "fist"
                    } else if (gesture == GestureDetector.GestureType.NONE) {
                        paramGesture = "none"
                    }
                    // 记录请求结束时间
                    val endTime = System.currentTimeMillis()

                    // 计算经过时间（单位：毫秒）
                    val elapsedTime = endTime - startTime

                    Log.e("xxx", "端测图像识别姿势：结果: $paramGesture, 经过时间: $elapsedTime ms")
                }



            } else if (outVecotr[4] == 1) {
                // 在服务器执行
//                Log.e("xxx", "服务器执行图像识别姿势")
                scope.launch {
                    // 记录请求开始时间
                    val startTime = System.currentTimeMillis() // 单位：毫秒

                    var result: String = request.gestureRecognition(gestureFrames)

                    // 记录请求结束时间
                    val endTime = System.currentTimeMillis()

                    // 计算经过时间（单位：毫秒）
                    val elapsedTime = endTime - startTime

                    // 打印结果和经过时间
                    Log.e("xxx", "边测姿势识别：结果: $result, 经过时间: $elapsedTime ms")

                    var gesture = GestureDetector.GestureType.NONE
                    // 发布事件
                    if (result == "other") {
                        gesture = GestureDetector.GestureType.NONE
                    } else if (result == "fist") {
                        gesture = GestureDetector.GestureType.FIST
                    }

//                    eventBus.publish(EventBus.Event.TRANSLATION_RESULT, result)
                    eventBus.publish(EventBus.Event.GESTURE_DETECTED, gesture)

                }
            }
        }
    }

    fun setRecognitionCallback(callback: (String) -> Unit) {
        recognitionCallback = callback
    }

    fun setTranslationCallback(callback: (String) -> Unit) {
        translationCallback = callback
    }

    fun setGestureCallback(callback: (GestureDetector.GestureType) -> Unit) {
        gestureCallback = callback
    }

    fun setGenderCallback(callback: (Gender) -> Unit) {
        genderCallback = callback
    }

    fun setStatusCallback(callback: (String) -> Unit) {
        statusCallback = callback
    }

    private fun checkInitialization() {
        if (!isInitialized) {
            throw IllegalStateException("AppController has not been initialized with an activity. Call initialize() first.")
        }
    }

    companion object {
        @Volatile
        private var instance: AppController? = null

        fun getInstance(): AppController {
            return instance ?: synchronized(this) {
                instance ?: AppController().also { instance = it }
            }
        }
    }
}