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
import com.k2fsa.sherpa.ncnn.request.TextMessage
import com.k2fsa.sherpa.ncnn.request.RegisterMessage
import com.k2fsa.sherpa.ncnn.request.TranslatedText
import com.k2fsa.sherpa.ncnn.speaker.AudioPlayer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import com.k2fsa.sherpa.ncnn.status.StatusMonitor
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel


/**
 * 查看网络时延 - tag:latency
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
    // websocket
    private lateinit var webSocketSession: DefaultClientWebSocketSession
    private var webSocketClient: HttpClient = HttpClient(CIO) {
        install(WebSockets)
    }
    private val _textResultFlow = MutableSharedFlow<String>()
    private val textResultFlow = _textResultFlow.asSharedFlow()


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
    private var outVector = intArrayOf(0, 1, 1, 0, 0)

    // 中英互译的流控制
    private var messageId: Int = 0
    private var isStart = true
    private var translatedText: TranslatedText = TranslatedText("", messageId, "")
    private var flowStartTime: Long = 0L


    /**
     * 初始化
     */
    fun initialize(activity: AppCompatActivity) {
        if (isInitialized) return
        context = activity
        val assets = activity.assets

        // Initialize audio modules
        audioRecorder = AudioRecorder(activity)
        speechRecognizer = SpeechRecognizer(assets)

        // Initialize video modules
        cameraManager = CameraManager(activity)
        videoFrameProcessor = VideoFrameProcessor(activity.baseContext,debugSaveImages = false)

        // Initialize AI modules
        gestureDetector = GestureDetector(activity)
        genderDetector = GenderDetector(activity)
        translationManager = LlmTranslator

        // Initialize Speaker modules
        audioPlayer = AudioPlayer()
        audioScope.launch {
            processTextToSpeechQueue()
        }

        algorithm = Algorithm()
        // 初始化卸载向量
//        outVector = algorithm.outputVector()

        // 初始化卸载控制器
        unloadController = UnloadController(outVector)

        /**
         * 电源监控相关初始化
         */
        // 初始化监控器
        statusMonitor = StatusMonitor(mainScope, context)
        // 初始化状态检测接收器
        statusMonitor.registerBatteryReceiver(activity)

        setupEventListeners()
        isInitialized = true
    }

    private fun checkInitialization() {
        if (!isInitialized) {
            throw IllegalStateException("AppController has not been initialized with an activity. Call initialize() first.")
        }
    }

    /**
     * 设置事件监听
     */
    private val audioJob = Job() // 创建一个 Job 用于控制协程
    private val audioScope = CoroutineScope(Dispatchers.IO + audioJob)
    private val audioMutex = Mutex()
    private val textChannel = Channel<TranslatedText>(capacity = Channel.UNLIMITED) // 任务队列
    private fun setupEventListeners() {
        // Handle speech recognition results
        eventBus.subscribe(EventBus.Event.SPEECH_RESULT) { data ->
            val recognizedText = data as String
            recognitionCallback?.invoke(recognizedText)

            // Send recognized text for translation
//            if (recognizedText.isNotEmpty()) {
//                CoroutineScope(Dispatchers.Main).launch {
//                    val translatedText = translationManager.translate(recognizedText)
//                    eventBus.publish(EventBus.Event.TRANSLATION_RESULT, translatedText)
//                }
//            }
        }

        // Handle translation results
        eventBus.subscribe(EventBus.Event.TRANSLATION_RESULT) { data ->
            val translatedText = data as String
            translationCallback?.invoke(translatedText)
        }

        // 处理文本转语音
        eventBus.subscribe(EventBus.Event.TEXT_TO_SOUND) { data ->

            val translatedTextTemp = data as TranslatedText
            audioScope.launch {
                textChannel.send(translatedTextTemp) // 将事件放入队列
            }

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
    private val recordJob = Job() // 创建一个 Job 用于控制协程
    private val recordScope = CoroutineScope(Dispatchers.IO + recordJob)
    fun startRecording() {

        Log.e("xxx", "开始识别语音")


        checkInitialization()
        if (isRecording) return

        isRecording = true
        statusCallback?.invoke("Recording started")

        // 测试输出向量
//        Log.e("vector", outVector.joinToString())

        audioRecorder.startRecording{ samples ->
            recordScope.launch {
                // 先进行语音转文本
                val soundToTextResult: String = unloadController.soundToText(speechRecognizer, eventBus, request, samples)
                Log.e("result", soundToTextResult)
                if (soundToTextResult != "") {

                    unloadController.translate(soundToTextResult, translationManager) {
                        _textResultFlow.emit(soundToTextResult) // 发送 textResult 到流
                    }

                }



//                eventBus.publish(EventBus.Event.TRANSLATION_RESULT, translatedResult)




            }


//            val startRecordTime = System.currentTimeMillis() // 单位：毫秒
//
//            speechRecognizer.recognize(samples) { result ->
//
//                val endRecordTime = System.currentTimeMillis() // 单位：毫秒
//                Log.e("xxx", "端侧语音转文本：结果：$result, 经过时间: ${endRecordTime - startRecordTime} ms")
//
//                /**
//                 * {string} - result 语言转的文本
//                 */
//                eventBus.publish(EventBus.Event.SPEECH_RESULT, result)
//
//                /**
//                 * 中英互译
//                 */
//                var translatedText = ""
//                if (outVector[1] == 0) {
//                    // 安卓端执行
//
//                    // 记录请求开始时间
//                    val startTime = System.currentTimeMillis() // 单位：毫秒
//
//                    val translator = LlmTranslator
//                    CoroutineScope(Dispatchers.Main).launch {
//                        translatedText = translator.translate(result)
//                        // 记录请求结束时间
//                        val endTime = System.currentTimeMillis()
//
//                        // 计算经过时间（单位：毫秒）
//                        val elapsedTime = endTime - startTime
//                        Log.e("xxx", "端测中英互译：结果: $translatedText, 经过时间：$elapsedTime ms")
//
//
//                        eventBus.publish(EventBus.Event.TRANSLATION_RESULT, translatedText)
//
//
//                        /**
//                         * 文本转语音
//                         */
//                        if (outVector[2] == 0) {
//                            // 安卓端执行
//                            Log.e("" , "端测文本转语音")
//                        } else if (outVector[2] == 1) {
//                            // 服务器端执行
//
//                            // 记录请求开始时间
//                            val startTime2 = System.currentTimeMillis() // 单位：毫秒
//
//                            val filePath: String = request.textToSpeech(translatedText, context, paramGender)
//
//                            // 记录请求结束时间
//                            val endTime2 = System.currentTimeMillis()
//
//                            // 计算经过时间（单位：毫秒）
//                            val elapsedTime2 = endTime2 - startTime2
//
//                            // 打印结果和经过时间
//                            Log.e("xxx", "边测文本转语音：文字: $translatedText, 存储路径：$filePath, 经过时间: $elapsedTime2 ms")
//
//                            eventBus.publish(EventBus.Event.TRANSLATION_RESULT, translatedText)
//
//                        }
//
//                    }
//
//
//
//
//                } else if (outVector[1] == 1) {
//                    // 服务器端执行
//                    scope.launch {
//                        // 记录请求开始时间
//                        val startTime = System.currentTimeMillis() // 单位：毫秒
//
//                        translatedText = request.translate(result)
//
//                        // 记录请求结束时间
//                        val endTime = System.currentTimeMillis()
//
//                        // 计算经过时间（单位：毫秒）
//                        val elapsedTime = endTime - startTime
//
//                        // 打印结果和经过时间
//                        Log.e("xxx", "边测中英互译：结果: $translatedText, 经过时间: $elapsedTime ms")
//
//
//                        eventBus.publish(EventBus.Event.TRANSLATION_RESULT, translatedText)
//
//                        /**
//                         * 文本转语音
//                         */
//                        if (outVector[2] == 0) {
//                            // 安卓端执行
//                            Log.e("" , "端测文本转语音")
//                        } else if (outVector[2] == 1) {
//                            // 服务器端执行
//
//                            // 记录请求开始时间
//                            val startTime2 = System.currentTimeMillis() // 单位：毫秒
//
//                            val filePath: String = request.textToSpeech(translatedText, context, paramGender)
//
//                            // 记录请求结束时间
//                            val endTime2 = System.currentTimeMillis()
//
//                            // 计算经过时间（单位：毫秒）
//                            val elapsedTime2 = endTime2 - startTime2
//
//                            // 打印结果和经过时间
//                            Log.e("xxx", "边测文本转语音：文字: $translatedText, 存储路径：$filePath, 经过时间: $elapsedTime2 ms")
//
//                            eventBus.publish(EventBus.Event.TRANSLATION_RESULT, translatedText)
//
//                        }
//                    }
//
//                }
//
//
//            }

        }
    }

    /**
     * 停止捕获音频
     */
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

    /**
     * 挂载生命周期
     */
    fun onResume() {
        checkInitialization()
        // Start periodic video capture for gesture and gender detection
        startPeriodicVideoCapture()

        // 状态监控
        statusMonitor.startPowerMonitoring()
        statusMonitor.startMemoryMonitoring()

        // websocket建立连接
        CoroutineScope(Dispatchers.IO).launch {
            initWebSocket()
        }
    }

    /**
     * 暂停生命周期
     */
    fun onPause() {
        checkInitialization()
        // Stop video capture
        stopPeriodicVideoCapture()


        // Ensure recording is stopped
        if (isRecording) {
            stopRecording()
        }
    }


    /**
     * 销毁生命周期
     */
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

        statusMonitor.unregisterBatteryReceiver()
        statusMonitor.stopPowerMonitoring()
        statusMonitor.stopMemoryMonitoring()

        // 清理协程
        cameraJob.cancel()
        recordScope.cancel()
        // 关闭通道
        textChannel.close()

    }



    /**
     * 捕获图像
     */
    private val cameraJob = Job() // 创建一个 Job 用于控制协程
    private val cameraScope = CoroutineScope(Dispatchers.IO + cameraJob)

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

    // 每5秒这个函数会被调用一次
    private fun captureAndProcessVideoFrames() {

        cameraManager.captureFrames(1) { frames ->

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
            cameraScope.launch {

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


    /**
     * 扬声器控制
     */
    private suspend fun processTextToSpeechQueue() {
        for (translatedText in textChannel) { // 按顺序接收事件
            val text = translatedText.text
            val language = translatedText.language
            val id = translatedText.id
            translatedText.clear() // 清空临时数据

            try {
                // 生成音频文件
                val filePath = request.textToSpeech(text, context, paramGender, language)
                if (filePath.isNotEmpty()) {
                    // 使用互斥锁确保播放顺序
                    audioMutex.withLock {
                        audioPlayer.playAudio(filePath) {
                            Log.e("speaker", "播放成功")
                            request.cleanUpAudioFile(filePath) // 清理文件
                        }
                    }
                }
            } catch (e: OutOfMemoryError) {
                Log.e("AudioModule", "Out of memory during TTS: ${e.message}")
            } catch (e: Exception) {
                Log.e("AudioModule", "Error processing audio: ${e.message}")
            }
        }
    }


    /**
     * websocket逻辑，为了方便集成到AppController
     */
    private suspend fun initWebSocket() {
        try {
            val port = 8765
            webSocketClient.webSocket("ws://${request.baseUrl}:$port") {
                webSocketSession = this
                Log.e("websocket", "已连接到 WebSocket 服务器！")
                // 注册逻辑保持不变
                val registerMsg = RegisterMessage("client_001", "APP", "LeiNiao")
                send(Json.encodeToString(registerMsg))
                Log.e("websocket", "已发送注册信息：$registerMsg")

                coroutineScope {
                    launch { sendData(this@webSocket) }
                    launch { receiveData(this@webSocket) }
                }
            }
        } catch (e: Exception) {
            Log.e("websocket", "连接错误: ${e.message}")
        }
    }
    // 数据生成
    private fun generateData(textResult: String, msgId: Int): Flow<TextMessage> = flow {
        emit(TextMessage(msgId, textResult))
    }
    // 发送数据
    private suspend fun sendData(session: DefaultClientWebSocketSession) {
        try {
            textResultFlow.collect { textResult ->
                generateData(textResult, messageId).collect { data ->
                    val json = Json.encodeToString(data)
                    Log.e("websocket", "发送中: $json")
                    session.send(json)
                    translatedText.id = messageId
                    messageId++
                    flowStartTime = System.currentTimeMillis()
                }
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
                        if (data.content == "<|EN|>") {
                            isStart = true
                            translatedText.language = "en"
                            eventBus.publish(EventBus.Event.TEXT_TO_SOUND, translatedText)

                        } else if (data.content == "<|CN|>") {
                            isStart = true
                            translatedText.language = "zh"
                            eventBus.publish(EventBus.Event.TEXT_TO_SOUND, translatedText)
                        } else  {
                            // 不是结束符
                            if (isStart) {
                                // 首token，清空屏幕，也是计时的标志
                                eventBus.publish(EventBus.Event.TRANSLATION_RESULT, "<|STX|>")
                                isStart = false
                                Log.e("latency", "首token时延${System.currentTimeMillis() - flowStartTime} ms")
                            }
                            eventBus.publish(EventBus.Event.TRANSLATION_RESULT, data.content)
                            translatedText.text += data.content
                        }
                    } catch (e: Exception) {
                        Log.e("websocket", "收到无效的 JSON 数据: $response")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("websocket", "接收数据时出错: ${e.message}")
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