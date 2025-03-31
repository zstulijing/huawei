package com.k2fsa.sherpa.onnx

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val TAG = "KokoroTTS"

public class KokoroTTS(private val context: Context) {

    private lateinit var tts: OfflineTts
    private lateinit var track: AudioTrack
    private var stopped = false

    init {
        initializeModel()
    }

    /**
     * 初始化 TTS 引擎以及 AudioTrack
     */
    private fun initializeModel() {

        var modelDir: String?
        var modelName: String?
        var acousticModelName: String?
        var vocoder: String?
        var voices: String?
        var ruleFsts: String?
        var ruleFars: String?
        var lexicon: String?
        var dataDir: String?
        var dictDir: String?

        // Matcha -- begin
        acousticModelName = null
        vocoder = null
        // Matcha -- end

        modelDir = null
        ruleFsts = null
        ruleFars = null
        lexicon = null
        dataDir = null
        dictDir = null

        // kokoro-int8-multi-lang-v1_1
        modelDir = "kokoro-int8-multi-lang-v1_1"
        modelName = "model.int8.onnx"
        voices = "voices.bin"
        dataDir = "kokoro-int8-multi-lang-v1_1/espeak-ng-data"
        dictDir = "kokoro-int8-multi-lang-v1_1/dict"
        lexicon = "kokoro-int8-multi-lang-v1_1/lexicon-us-en.txt,kokoro-int8-multi-lang-v1_1/lexicon-zh.txt"
        ruleFsts = "$modelDir/phone-zh.fst,$modelDir/date-zh.fst,$modelDir/number-zh.fst"

        if (dataDir != null) {
            val newDir = copyDataDir(dataDir!!)
//            dataDir = "$newDir/$dataDir"
            dataDir = "$newDir/"
        }

        if (dictDir != null) {
            val newDir = copyDataDir(dictDir!!)
//            dictDir = "$newDir/$dictDir"
            dictDir = "$newDir/"
            if (ruleFsts == null) {
                ruleFsts = "$modelDir/phone.fst,$modelDir/date.fst,$modelDir/number.fst"
            }
        }

        // 若 dataDir 中有数据文件，复制到外部目录（用于离线加载）
//        val actualDataDir = copyDataDir(dataDir)

        // 获取 OfflineTts 的配置，注意此函数及 OfflineTts 类需要你在项目中已有实现
        val config = getOfflineTtsConfig(
            modelDir = modelDir!!,
            modelName = modelName ?: "",
            acousticModelName = acousticModelName ?: "",
            vocoder = vocoder ?: "",
            voices = voices ?: "",
            lexicon = lexicon ?: "",
            dataDir = dataDir ?: "",
            dictDir = dictDir ?: "",
            ruleFsts = ruleFsts ?: "",
            ruleFars = ruleFars ?: "",
        )!!

        tts = OfflineTts(assetManager = context.assets, config = config)

        // 初始化 AudioTrack
        val sampleRate = tts.sampleRate()
        val bufLength = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        val attr = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()

        track = AudioTrack(
            attr, format, bufLength, AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track.play()
        Log.i(TAG, "KokoroTTS init finished. sampleRate: $sampleRate, buffer length: $bufLength")
    }

    /**
     * 文本转语音接口
     * @param text 输入的文本，默认为使用 speakerId=0, speed=1.0
     * speakerId 0 ... 58 female
     * speakerId >= 58 male
     */
    suspend fun speak(text: String, speakerId: Int, speed: Float = 1.0f) = withContext(Dispatchers.Default) {
        if (text.isBlank()) {
            Log.e(TAG, "输入文本为空")
            return@withContext
        }

        stopped = false
        track.pause()
        track.flush()
        track.play()
        val startTime = System.currentTimeMillis() // 记录开始时间
        try {
            tts.generateWithCallback(
                text = text,
                sid = speakerId,
                speed = speed,
                callback = { samples ->
                    val elapsedTime = System.currentTimeMillis() - startTime // 计算经过时间
                    Log.e("latency", "端侧 文本转语音--经过时间: $elapsedTime ms")
                    if (!stopped) {
                        track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                        1
                    } else {
                        track.stop()
                        0
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "TTS 生成失败: $e")
            stop()
        }
    }

    /**
     * 内部回调函数，TTS 生成过程中会不断回调输出音频采样
     */
    private fun callback(samples: FloatArray): Int {
        return if (!stopped) {
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            1
        } else {
            track.stop()
            0
        }
    }

    /**
     * 停止当前 TTS 播放（可单独调用）
     */
    private fun stop() {
        stopped = true
        track.pause()
        track.flush()
    }

    /**
     * 释放相关资源
     */
    fun destroy() {
        stop()
        track.release()
        tts.release()
        // 如果 OfflineTts 有专门的资源回收接口，可在此调用
        Log.i(TAG, "KokoroTTS destroyed")
    }

    /**
     * 将 assets 中的 dataDir 文件夹复制到外部文件目录中
     */
    private fun copyDataDir(dataDir: String): String {
        copyAssets(dataDir)
        return context.getExternalFilesDir(null)?.absolutePath + "/$dataDir"
    }

    /**
     * 递归复制 assets 中指定路径下的所有文件
     */
    private fun copyAssets(path: String) {
        try {
            val assetsList = context.assets.list(path)
            if (assetsList.isNullOrEmpty()) {
                copyFile(path)
            } else {
                val fullPath = "${context.getExternalFilesDir(null)}/$path"
                val dir = File(fullPath)
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                for (asset in assetsList) {
                    val subPath = if (path.isEmpty()) asset else "$path/$asset"
                    copyAssets(subPath)
                }
            }
        } catch (ex: IOException) {
            Log.e(TAG, "复制 assets 目录 $path 失败：$ex")
        }
    }

    /**
     * 复制单个文件，从 assets 到外部文件目录
     */
    private fun copyFile(filename: String) {
        try {
            context.assets.open(filename).use { inputStream ->
                val outFile = File(context.getExternalFilesDir(null), filename)
                // 确保父目录存在
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { outputStream ->
                    val buffer = ByteArray(1024)
                    var read: Int
                    while (inputStream.read(buffer).also { read = it } != -1) {
                        outputStream.write(buffer, 0, read)
                    }
                    outputStream.flush()
                }
            }
            Log.i(TAG, "复制文件 $filename 成功")
        } catch (ex: Exception) {
            Log.e(TAG, "复制文件 $filename 失败：$ex")
        }
    }
}
