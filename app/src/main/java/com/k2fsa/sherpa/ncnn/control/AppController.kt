package com.k2fsa.sherpa.ncnn.control

import android.content.Context
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.k2fsa.sherpa.ncnn.algorithm.Algorithm
import com.k2fsa.sherpa.ncnn.request.Request
import com.k2fsa.sherpa.ncnn.speaker.AudioPlayer

// 电量检测
import com.k2fsa.sherpa.ncnn.status.StatusMonitor

/**
 * 查看网络实验 - tag:latency
 * 查看电量/CPU/内存 - tag:usage
 * 查看控制逻辑 - tag:control
 * 查看模块结果 - tag:result
 * 查看websocket - tag:websocket
 */
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
    // 扬声器
    private lateinit var audioPlayer: AudioPlayer
//    private lateinit var translationManager: TranslationManager
    // 卸载算法
    private lateinit var algorithm: Algorithm
    // 控制逻辑
    private lateinit var unloadController: UnloadController

//    private lateinit var translationManager: LlmTranslator
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

    // 功耗检测
    private lateinit var statusMonitor: StatusMonitor

    // request请求类
    private var request = Request()
    // 性别
    private var paramGender = "male"
    /**
     * 卸载算法输出
     * [0]: 语音转文本，一定是0（安卓端）
     * [1]: 中英互译
     * [2]: 文本转语音
     * [3]: 图像识别男女
     * [4]: 图像识别手势
     */
    private var outVector = intArrayOf(0, -1, -1, -1, 0)


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
        translationManager = LlmTranslator

        // Initialize Speaker modules
        audioPlayer = AudioPlayer()

        algorithm = Algorithm()
        // 初始化卸载向量
//        outVector = algorithm.outputVector()

        // 初始化卸载控制器
        unloadController = UnloadController(outVector)

        // 初始化监控器
        statusMonitor = StatusMonitor(mainScope, context)

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

        Log.e("xxx", "开始识别语音")


        checkInitialization()
        if (isRecording) return

        isRecording = true
        statusCallback?.invoke("Recording started")

        // 测试监控
//        statusMonitor.startPowerMonitoring()
//        statusMonitor.startMemoryMonitoring()


        val job = Job() // 创建一个 Job 用于控制协程
        val scope = CoroutineScope(Dispatchers.IO + job)
        // 测试websocket
//        scope.launch {
//            request.streamData()
//        }


        // 测试上行速率
//        scope.launch {
//            val upLatency = request.testUpSpeed(30)
//            Log.e("latency", "上行延迟：$upLatency ms")
//        }

        // 测试播放
//        val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
//        val filename = "chinese1.mp3"
//        Log.e("speaker", "$storageDir/$filename")
//        audioPlayer.playAudio("$storageDir/$filename") {
//            Log.e("speaker", "播放成功")
//        }



        // 测试输出向量
//        Log.e("vector", outVector.joinToString())

        audioRecorder.startRecording{ samples ->

            val startRecordTime = System.currentTimeMillis() // 单位：毫秒

            speechRecognizer.recognize(samples) { result ->

                val endRecordTime = System.currentTimeMillis() // 单位：毫秒
                Log.e("xxx", "端侧语音转文本：结果：$result, 经过时间: ${endRecordTime - startRecordTime} ms")

                /**
                 * {string} - result 语言转的文本
                 */
                eventBus.publish(EventBus.Event.SPEECH_RESULT, result)

                /**
                 * 中英互译
                 */
                var translatedText = ""
                if (outVector[1] == 0) {
                    // 安卓端执行

                    // 记录请求开始时间
                    val startTime = System.currentTimeMillis() // 单位：毫秒

                    val translator = LlmTranslator
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
                        if (outVector[2] == 0) {
                            // 安卓端执行
                            Log.e("" , "端测文本转语音")
                        } else if (outVector[2] == 1) {
                            // 服务器端执行

                            // 记录请求开始时间
                            val startTime2 = System.currentTimeMillis() // 单位：毫秒

                            val filePath: String = request.textToSpeech(translatedText, context, paramGender)

                            // 记录请求结束时间
                            val endTime2 = System.currentTimeMillis()

                            // 计算经过时间（单位：毫秒）
                            val elapsedTime2 = endTime2 - startTime2

                            // 打印结果和经过时间
                            Log.e("xxx", "边测文本转语音：文字: $translatedText, 存储路径：$filePath, 经过时间: $elapsedTime2 ms")

                            eventBus.publish(EventBus.Event.TRANSLATION_RESULT, translatedText)

                        }

                    }




                } else if (outVector[1] == 1) {
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
                        if (outVector[2] == 0) {
                            // 安卓端执行
                            Log.e("" , "端测文本转语音")
                        } else if (outVector[2] == 1) {
                            // 服务器端执行

                            // 记录请求开始时间
                            val startTime2 = System.currentTimeMillis() // 单位：毫秒

                            val filePath: String = request.textToSpeech(translatedText, context, paramGender)

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

        // 停止监控
        statusMonitor.startMemoryMonitoring()
        statusMonitor.stopPowerMonitoring()
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

            // 对每个帧进行处理
            frames.forEach { frameInfo ->
                videoFrameProcessor.addFrame(
                    frameInfo.data,
                    frameInfo.width,
                    frameInfo.height
                )
            }

            // 只取一帧frame
            val frame = videoFrameProcessor.getFramesForAllDetection(1)

            // 先进行手势识别
            scope.launch {

                val gesture: String = unloadController.gestureRecognition(gestureDetector, eventBus, request, frame)

                Log.e("result", "手势识别结果为$gesture")

                if (gesture == "none") {

                    // 识别到none，此时可能正在录音，或还没开始录音
                    if (isRecording) {

                        // 正在录音，识别男女
                        val gender: String = unloadController.genderRecognition(genderDetector, eventBus, request, frame)
                        paramGender = gender
                        Log.e("control", "正在录音，识别男女")
                        Log.e("result", "识别男女结果$gender")

                    } else {
                        // 不需要识别男女
                        Log.e("control", "还没开始录音，不需要识别男女")
                    }

                } else if (gesture == "fist") {
                    // 识别到fist，此时可能想要结束录音，或想要开始录音
                    if (isRecording) {
                        // 结束录音，不需要识别男女
                        Log.e("control", "录音结束，不需要识别男女")
                    } else {
                        // 开始录音，识别男女
                        val gender: String = unloadController.genderRecognition(genderDetector, eventBus, request, frame)
                        paramGender = gender
                        Log.e("control", "开始录音，识别男女")
                        Log.e("result", "识别男女结果$gender")
                    }
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