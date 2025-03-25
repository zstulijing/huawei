package com.k2fsa.sherpa.ncnn

import android.content.res.AssetManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "SpeechRecognizer"

class SpeechRecognizer(
    private val assetManager: AssetManager,
    private val useGPU: Boolean = true,
    private val modelType: Int = 2 // Default to bilingual model
) {

    private lateinit var model: SherpaNcnn
    private var lastText: String = ""
    private var utteranceIndex: Int = 0
    private val recognitionMutex = Mutex()

    init {
        initModel()
    }

    private fun initModel() {
        Log.i(TAG, "Initializing speech recognition model")

        try {
            val featConfig = getFeatureExtractorConfig(
                sampleRate = 16000.0f,
                featureDim = 80
            )

            val modelConfig = getModelConfig(type = modelType, useGPU = useGPU)
                ?: throw IllegalArgumentException("Invalid model type: $modelType")

            val decoderConfig = getDecoderConfig(
                method = "greedy_search",
                numActivePaths = 4
            )

            val config = RecognizerConfig(
                featConfig = featConfig,
                modelConfig = modelConfig,
                decoderConfig = decoderConfig,
                enableEndpoint = true,
                rule1MinTrailingSilence = 2.0f,
                rule2MinTrailingSilence = 0.8f,
                rule3MinUtteranceLength = 20.0f,
            )

            model = SherpaNcnn(
                assetManager = assetManager,
                config = config,
            )

            Log.i(TAG, "Speech recognition model initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize model: ${e.message}")
            throw e
        }
    }

    fun reset() {
        model.reset(true)
        lastText = ""
    }



    fun recognize(samples: FloatArray, recognitionCallback: ((String) -> Unit)?) {
        CoroutineScope(Dispatchers.IO).launch {
            recognitionMutex.withLock {
                val startTime = System.currentTimeMillis()
                model.acceptSamples(samples)
                while (model.isReady()) {
                    model.decode()
                }

                val isEndpoint = model.isEndpoint()
                val text = model.text
                val processingTime = System.currentTimeMillis() - startTime
                withContext(Dispatchers.Main) {
                    if (isEndpoint) {
                        model.reset()
                        if (text.isNotBlank()) {
                            lastText = text
                            Log.i(TAG, "语音识别结果：$lastText，处理时间：${processingTime}ms")
                            recognitionCallback?.invoke(text)
                            utteranceIndex++
                        }
                    }
                }
            }
        }
    }

    fun shutdown() {
        // Additional cleanup if needed
    }
}
