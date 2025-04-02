package com.k2fsa.sherpa.ncnn.control

import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.k2fsa.sherpa.ncnn.Gender
import com.k2fsa.sherpa.ncnn.GenderDetector
import com.k2fsa.sherpa.ncnn.gesture.GestureDetector
import com.k2fsa.sherpa.ncnn.translation.LlmTranslator
import com.k2fsa.sherpa.ncnn.recorder.AudioRecorder
import com.k2fsa.sherpa.ncnn.recorder.SpeechRecognizer
import com.k2fsa.sherpa.ncnn.video.CameraManager
import com.k2fsa.sherpa.ncnn.video.VideoFrameProcessor
import com.k2fsa.sherpa.ncnn.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.k2fsa.sherpa.ncnn.algorithm.Algorithm
import com.k2fsa.sherpa.ncnn.recorder.calculateRMS
import com.k2fsa.sherpa.ncnn.request.AudioMessage
import com.k2fsa.sherpa.ncnn.request.AudioResponseMessage
import com.k2fsa.sherpa.ncnn.request.Request
import com.k2fsa.sherpa.ncnn.request.TextMessage
import com.k2fsa.sherpa.ncnn.request.RegisterMessage
import com.k2fsa.sherpa.ncnn.request.TranslatedText
import com.k2fsa.sherpa.ncnn.request.getAvgRTT
import com.k2fsa.sherpa.ncnn.speaker.AudioPlayer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import com.k2fsa.sherpa.ncnn.status.StatusMonitor
import com.k2fsa.sherpa.ncnn.translation.isChinese
import com.k2fsa.sherpa.ncnn.translation.tokenizeChinese
import com.k2fsa.sherpa.ncnn.translation.tokenizeEnglish
import com.k2fsa.sherpa.onnx.KokoroTTS
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


/**
 * 查看时延 - tag:latency
 * 查看电量/CPU/内存/温度 - tag:usage
 * 查看控制逻辑 - tag:controller
 * 查看模块结果 - tag:result
 * 查看websocket - tag:websocket
 * 各种异常 - tag:exception
 */
class AppController private constructor() {


    /**
     * 卸载算法输出
     * [0]: 语音转文本
     * [1]: 中英互译
     * [2]: 文本转语音
     * [3]: 图像识别男女
     * [4]: 图像识别手势
     */
    private var outVector = intArrayOf(-1, -1, -1, 1, -1)
    private var whisper = false
    private var sttProcessingTime: Long = 0L
    // Modules
    private val logger = Logger(this::class.java.simpleName)
    private val eventBus = EventBus()
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var cameraManager: CameraManager
    private lateinit var videoFrameProcessor: VideoFrameProcessor
    private lateinit var gestureDetector: GestureDetector
    private lateinit var genderDetector: GenderDetector

    // 扬声器
    private lateinit var audioPlayer: AudioPlayer

    // 卸载算法
    private lateinit var algorithm: Algorithm
    // 控制逻辑
    private lateinit var unloadController: UnloadController

    // 翻译器
    private lateinit var translationManager: LlmTranslator

    // Callbacks
    private var recognitionCallback: ((String) -> Unit)? = null
    private var translationCallback: ((String) -> Unit)? = null
    private var gestureCallback: ((GestureDetector.GestureType) -> Unit)? = null
    private var genderCallback: ((Gender) -> Unit)? = null
    private var statusCallback: ((String) -> Unit)? = null
    private var usageCallback: ((String) -> Unit)? = null
    private var bandWidthCallback: ((String) -> Unit)? = null
    private var rttCallback: ((String) -> Unit)? = null
    private var batteryCallback: ((String) -> Unit)? = null
    private var algorithmCallback: ((String) -> Unit)? = null
    private var firstTokenCallback: ((String) -> Unit)? = null
    private var powerCallback: ((String) -> Unit)? = null
    private var temperatureCallback: ((String) -> Unit)? = null


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
    private lateinit var translateWebSocketSession: DefaultClientWebSocketSession
    private lateinit var soundToTextWebSocketSession: DefaultClientWebSocketSession
    private lateinit var whisperWebSocketSession: DefaultClientWebSocketSession
    private var webSocketClient: HttpClient = HttpClient(CIO) {
        install(WebSockets)
    }
    private val _textResultFlow = MutableSharedFlow<String>()
    private val textResultFlow = _textResultFlow.asSharedFlow()
    private val _soundResultFlow = MutableSharedFlow<FloatArray>()
    private val soundResultFlow = _soundResultFlow.asSharedFlow()

    // 性别
    private var paramGender = "male"

    // 中英互译的流控制
    private var messageId: Int = 0
    private var isStart = true
    private var translatedText: TranslatedText = TranslatedText("", messageId, "")
    private var flowStartTime1: Long = 0L
    private var flowStartTime3: Long = 0L
    private var flowEnd = true
    private var flowStartTime5: Long = 0L
    // 文本转语音
    private lateinit var kokoroTTS: KokoroTTS


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


        kokoroTTS = KokoroTTS(activity.baseContext)

        algorithm = Algorithm(eventBus)

        // 初始化卸载控制器
        unloadController = UnloadController(outVector)

        /**
         * 电源监控相关初始化
         */
        // 初始化监控器
        statusMonitor = StatusMonitor(mainScope, context, eventBus)
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
    private fun setupEventListeners() {
        // Handle speech recognition results
        eventBus.subscribe(EventBus.Event.SPEECH_RESULT) { data ->
            val recognizedText = data as String
            recognitionCallback?.invoke(recognizedText)
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
                val text = translatedText.text
                val language = translatedText.language
                val id = translatedText.id
                translatedText.clear() // 清空临时数据
                try {

                    if (outVector[2] == 0) {
                        audioMutex.withLock {

                            if (paramGender == "male") {
                                kokoroTTS.speak(text, 60)
                            } else if (paramGender == "female") {
                                kokoroTTS.speak(text, 0)
                            }

                        }
                    } else if (outVector[2] == 1) {
                        // 生成音频文件
                        val filePath = request.textToSpeech(text, context, paramGender, language)
                        if (filePath.isNotEmpty()) {
                            // 使用互斥锁确保播放顺序
                            audioMutex.withLock {
                                audioPlayer.playAudio(filePath)
                                request.cleanUpAudioFile(filePath) // 清理文件
                            }
                        }
                    }

                } catch (e: Exception) {
                    Log.e("exception", "Error processing audio: ${e.message}")
                }
            }

        }

        eventBus.subscribe(EventBus.Event.USAGE) { data ->
        val usage = data as String
            usageCallback?.invoke(usage)
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

        eventBus.subscribe(EventBus.Event.BAND_WIDTH) { data ->
            val bandWidth = data as String
            bandWidthCallback?.invoke(bandWidth)
        }
        eventBus.subscribe(EventBus.Event.RTT) { data ->
            val rtt = data as String
            rttCallback?.invoke(rtt)
        }
        eventBus.subscribe(EventBus.Event.BATTERY) { data ->
            val battery = data as String
            batteryCallback?.invoke(battery)
        }
        eventBus.subscribe(EventBus.Event.ALGORITHM) { data ->
            val algorithm = data as String
            algorithmCallback?.invoke(algorithm)
        }
        eventBus.subscribe(EventBus.Event.FIRST_TOKEN) { data ->
            val firstToken = data as String
            firstTokenCallback?.invoke(firstToken)
        }
        eventBus.subscribe(EventBus.Event.POWER) { data ->
            val power = data as String
            powerCallback?.invoke(power)
        }
        eventBus.subscribe(EventBus.Event.TEMPERATURE) { data ->
            val temperature = data as String
            temperatureCallback?.invoke(temperature)
        }

    }


    /**
     * 捕获音频
     */
    private val recordJob = Job() // 创建一个 Job 用于控制协程
    private val recordScope = CoroutineScope(Dispatchers.IO + recordJob)
    fun startRecording() {

        // 测试输出向量
//        Log.e("vector", outVector.joinToString())

        Log.e("controller", "开始识别语音")

        checkInitialization()
        if (isRecording) return

        isRecording = true
        statusCallback?.invoke("Recording started")


        var interval = 0.1 // 本地文本转语音 默认间隔100ms
        if (outVector[0] == 1) {
            // 端侧文本转语音 -> 中英互译 -> 设置间隔为400ms
//            interval = 0.4  // 一般环境
            interval = 0.6 // 服务器环境
        }
        audioRecorder.startRecording(interval) { samples ->


            recordScope.launch {

                // 先进行语音转文本
                val startTime = System.currentTimeMillis()
                val soundToTextResult: String = unloadController.soundToText(speechRecognizer, eventBus, request, samples)
                sttProcessingTime = System.currentTimeMillis() - startTime

                Log.e("latency", "端侧 语音转文本--经过时间: $sttProcessingTime ms")
                if (soundToTextResult == "<|WS|>") { // 特殊控制字符，边测文本转语音+中英互译
                    // 计算样本的 RMS（均方根）值
                    val rms = calculateRMS(samples)
//                    val silenceThreshold = 0.01f // 一般环境
                    val silenceThreshold = 0.045f // 服务器环境

                    // 判断是否为空语音
                    if (rms < silenceThreshold) {
                        // 空语音
//                        Log.e("test", "空音频")
                        _soundResultFlow.emit(FloatArray(0)) // 发送空数组到流，同时指定id = -1（断句标识）
                        if (!flowEnd) {
                            flowStartTime3 = System.currentTimeMillis()
                            flowStartTime5 = System.currentTimeMillis()
                            flowEnd = true
                        }
                    } else {
                        // 非空语音
//                        Log.e("test", "非空")
                        _soundResultFlow.emit(samples) // 发送 samples (FloatArray) 到流
                        flowEnd = false
                    }

                } else if (soundToTextResult != "") { // 本地文本转语音，且转出来的值不是空
                    val sttStart = System.currentTimeMillis() // 单位：毫秒
                    val localResultText = unloadController.translate(soundToTextResult, translationManager) {
                        // 服务端执行
                        _textResultFlow.emit(soundToTextResult) // 发送 soundToTextResult (String) 到流
                    }
                    val elapsedTime = System.currentTimeMillis() - sttStart
                    Log.e("latency", "端测 中英互译---经过时间: $elapsedTime ms")
                    Log.e("latency", "路径1/4 首Token时延: ${sttProcessingTime + elapsedTime} ms")
                    eventBus.publish(EventBus.Event.FIRST_TOKEN, "${sttProcessingTime + elapsedTime}")

                    if (localResultText != "") {
                        // 本地非流式输出
                        eventBus.publish(EventBus.Event.TRANSLATION_RESULT, "<|STX|>")
                        translatedText.text = localResultText
                        translatedText.id = -1
                        if (isChinese(localResultText)) {
                            translatedText.language = "zh"
                            val tokens = tokenizeChinese(localResultText)
                            tokens.forEach {
                                eventBus.publish(EventBus.Event.TRANSLATION_RESULT, it)
                                delay(100)
                            }
                        } else {
                            translatedText.language = "en"
                            val tokens = tokenizeEnglish(localResultText)
                            tokens.forEach {
                                eventBus.publish(EventBus.Event.TRANSLATION_RESULT, "$it ")
                                delay(100)
                            }
                        }
                        eventBus.publish(EventBus.Event.TEXT_TO_SOUND, translatedText)
                    }
                }
            }
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

    }

    /**
     * 挂载生命周期
     */
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val timeJob = Job()
    private val timeScope = CoroutineScope(Dispatchers.IO + timeJob)
    fun onResume() {
        checkInitialization()
        // Start periodic video capture for gesture and gender detection
        startPeriodicVideoCapture()

        // 状态监控
        statusMonitor.startPowerMonitoring()
        statusMonitor.startMemoryMonitoring()
        statusMonitor.startTemperatureMonitoring()

        // websocket建立连接
        if (whisper) {
            coroutineScope.launch {
                initWhisperWebSocket()
            }
        } else if (outVector[0] == 1 || outVector[1] == 1) {
            coroutineScope.launch {
                initTranslateWebSocket()
            }
            coroutineScope.launch {
                initSoundToTextWebSocket()
            }
        }

        // 卸载算法：每五分钟执行一次
        timeScope.launch {
            while (true) {
                algorithm.outputVector()
                delay(5 * 60 * 1000)
            }
        }

        // 网络带宽 / RTT：每一分钟执行一次
        timeScope.launch {
            while (true) {
                val bandwidth = request.testSpeed(1000 * 1000) // 1MB
                Log.e("speed", "当前网络带宽约为 ${bandwidth / 1024} Mb/s")
                val rtt = getAvgRTT(request.baseUrl)
                Log.e("speed", "RTT: $rtt ms")
                eventBus.publish(EventBus.Event.BAND_WIDTH, "${bandwidth / 1024}")
                eventBus.publish(EventBus.Event.RTT, "$rtt")
                delay(1 * 60 * 1000)
            }
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

        // 关闭文本转语音模块
        kokoroTTS.destroy()

        // 关闭状态监控
        statusMonitor.unregisterBatteryReceiver()
        statusMonitor.stopPowerMonitoring()
        statusMonitor.stopMemoryMonitoring()
        statusMonitor.stopTemperatureMonitoring()

        // 清理协程
        cameraJob.cancel()
        recordScope.cancel()
        coroutineScope.cancel() // 取消所有协程
        webSocketClient.close() // 关闭 WebSocket 客户端

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

    // 处理摄像头捕获的图像（每5秒这个函数会被调用一次）
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
                        Log.e("controller", "正在录音，识别男女")
                        Log.e("result", "识别男女结果$gender")

                    } else {
                        // 不需要识别男女
                        Log.e("controller", "还没开始录音，不需要识别男女")
                    }

                } else if (gesture == "fist") {
                    // 识别到fist，此时可能想要结束录音，或想要开始录音
                    if (isRecording) {
                        // 结束录音，不需要识别男女
                        Log.e("controller", "录音结束，不需要识别男女")
                    } else {
                        // 开始录音，识别男女
                        val gender: String = unloadController.genderRecognition(genderDetector, eventBus, request, frame)
                        paramGender = gender
                        Log.e("controller", "开始录音，识别男女")
                        Log.e("result", "识别男女结果$gender")
                    }
                }
            }

        }
    }



    /**
     * websocket逻辑，为了方便集成到AppController
     */
    private suspend fun initTranslateWebSocket() {
        try {
            val port = 8765
            webSocketClient.webSocket("ws://${request.baseUrl}:$port") {
                translateWebSocketSession = this
                Log.e("websocket", "已连接到翻译 WebSocket 服务器 (8765)！")
                // 注册逻辑保持不变
                val registerMsg = RegisterMessage("client_001", "APP", "LeiNiao")
                send(Json.encodeToString(registerMsg))
                Log.e("websocket", "已发送注册信息：$registerMsg")

                coroutineScope {
                    launch { sendTranslateData(this@webSocket) }
                    launch { receiveTranslateData(this@webSocket) }
                }
            }
        } catch (e: Exception) {
            Log.e("exception", "连接错误: ${e.message}")
        }
    }

    private suspend fun initSoundToTextWebSocket() {
        try {
            val port = 10003
            webSocketClient.webSocket("ws://${request.baseUrl}:$port") {
                soundToTextWebSocketSession = this
                Log.e("websocket", "已连接到语音转文本 WebSocket 服务器 (10003)！")
                // 注册逻辑保持不变
                val registerMsg = RegisterMessage("client_001", "APP", "LeiNiao")
                send(Json.encodeToString(registerMsg))
                Log.e("websocket", "已发送注册信息：$registerMsg")
                // 10003 只需要发不需要收
                coroutineScope {
                    launch { sendSoundToTextData(this@webSocket) }
                    launch { receiveSoundData(this@webSocket) }
                }
            }
        } catch (e: Exception) {
            Log.e("exception", "连接错误: ${e.message}")
        }
    }

    private suspend fun initWhisperWebSocket() {
        try {
            val port = 10006
//            Log.e("websocket", "ws://${request.baseUrl}:$port")
            webSocketClient.webSocket("ws://${request.baseUrl}:$port") {
                whisperWebSocketSession = this
                Log.e("websocket", "已连接到whisper WebSocket 服务器 (10006)！")
                // 注册逻辑保持不变
                val registerMsg = RegisterMessage("client_001", "APP", "LeiNiao")
                send(Json.encodeToString(registerMsg))
                Log.e("websocket", "已发送注册信息：$registerMsg")

                coroutineScope {
                    launch { sendSoundToTextData(this@webSocket) }
                    launch { receiveSoundData(this@webSocket) }
                }
            }
        } catch (e: Exception) {
            Log.e("exception", "连接错误: ${e.message}")
        }
    }


    // 翻译数据生成
    private fun generateTranslateData(textResult: String, msgId: Int): Flow<TextMessage> = flow {
        emit(TextMessage(msgId, textResult))
    }
    // 发送翻译数据
    private suspend fun sendTranslateData(session: DefaultClientWebSocketSession) {
        try {
            textResultFlow.collect { textResult ->
                generateTranslateData(textResult, messageId).collect { data ->
                    val json = Json.encodeToString(data)
                    Log.e("websocket", "发送中: $json")
                    session.send(json)
                    translatedText.id = messageId
                    messageId++
                    flowStartTime1 = System.currentTimeMillis()
                }
            }
        } catch (e: Exception) {
            Log.e("exception", "发送翻译数据时出错: ${e.message}")
        }
    }
    // 接收翻译数据
    private suspend fun receiveTranslateData(session: DefaultClientWebSocketSession) {
        try {
            for (frame in session.incoming) {

                if (frame is Frame.Text) {
                    val response = frame.readText()
                    try {
                        val data = Json.decodeFromString<TextMessage>(response)
                        Log.e("websocket", "8765收到: msg_id=${data.msg_id}, content=${data.content}")
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
                                if (outVector[0] == 0) { // 路径2
                                    val firstToken = System.currentTimeMillis() - flowStartTime1
                                    Log.e("latency", "路径2 首token时延: ${sttProcessingTime + firstToken} ms")
                                    eventBus.publish(EventBus.Event.FIRST_TOKEN, "${sttProcessingTime + firstToken}")

                                } else if (outVector[0] == 1 && !whisper) { // 路径3
                                    val firstToken = System.currentTimeMillis() - flowStartTime3
                                    Log.e("latency", "路径3 首token时延: $firstToken ms")
                                    eventBus.publish(EventBus.Event.FIRST_TOKEN, "$firstToken")
                                }
                            }
                            eventBus.publish(EventBus.Event.TRANSLATION_RESULT, data.content)
                            translatedText.text += data.content
                        }
                    } catch (e: Exception) {
                        Log.e("exception", "收到无效的 JSON 数据: $response")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("exception", "接收数据时出错: ${e.message}")
        }
    }

    // 语音数据生成
    private fun generateSoundData(id: String, msgId: Int, samples: FloatArray, sampleRate: Int): Flow<AudioMessage> = flow {
        emit(AudioMessage(id, msgId, samples, sampleRate))
    }
    // 发送语音数据 or Whisper数据
    private suspend fun sendSoundToTextData(session: DefaultClientWebSocketSession) {
        try {
            soundResultFlow.collect { soundResult ->
                generateSoundData("client_001", messageId, soundResult, 16000).collect { data ->
                    if (soundResult.isEmpty()) {
                        data.msg_id = -1
                    }
                    val json = Json.encodeToString(data)
                    Log.e("websocket", "发送中: $json")
                    session.send(json)
                    translatedText.id = messageId
                    if (soundResult.isNotEmpty()) {
                        messageId++
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("exception", "发送语音数据时出错: ${e.message}")
        }
    }
    // 接收语音数据：语音转文本结果  or whisper数据
    private suspend fun receiveSoundData(session: DefaultClientWebSocketSession) {
        try {
            for (frame in session.incoming) {
                if (frame is Frame.Text) {
                    val response = frame.readText()
                    try {
                        val data = Json.decodeFromString<AudioResponseMessage>(response)
                        if (whisper) {
                            Log.e("websocket", "10006收到: msg_id=${data.msg_id}, content=${data.content}")
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
                                    // 路径5
                                    val firstToken = System.currentTimeMillis() - flowStartTime5
                                    Log.e("latency", "路径5 首token时延: $firstToken ms")
                                    eventBus.publish(EventBus.Event.FIRST_TOKEN, "$firstToken")
                                }
                                eventBus.publish(EventBus.Event.TRANSLATION_RESULT, data.content)
                                translatedText.text += data.content
                            }
                        } else {
                            Log.e("websocket", "10003收到: msg_id=${data.msg_id}, content=${data.content}")
                            // 10003返回的是原文
                            eventBus.publish(EventBus.Event.SPEECH_RESULT, data.content)
                        }


                    } catch (e: Exception) {
                        Log.e("exception", "收到无效的 JSON 数据: $response")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("exception", "接收数据时出错: ${e.message}")
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

    fun setUsageCallback(callback: (String) -> Unit) {
        usageCallback = callback
    }

    fun setBandWidthCallback(callback: (String) -> Unit) {
        bandWidthCallback = callback
    }
    fun setRttCallback(callback: (String) -> Unit) {
        rttCallback = callback
    }
    fun setBatteryCallback(callback: (String) -> Unit) {
        batteryCallback = callback
    }
    fun setAlgorithmCallback(callback: (String) -> Unit) {
        algorithmCallback = callback
    }
    fun setFirstTokenCallback(callback: (String) -> Unit) {
        firstTokenCallback = callback
    }
    fun setPowerCallback(callback: (String) -> Unit) {
        powerCallback = callback
    }
    fun setTemperatureCallback(callback: (String) -> Unit) {
        temperatureCallback = callback
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