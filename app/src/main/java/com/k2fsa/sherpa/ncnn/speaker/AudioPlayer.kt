package com.k2fsa.sherpa.ncnn.speaker
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AudioPlayer {
    companion object {
        private const val TAG = "AudioPlayer"
    }

    private var mediaPlayer: MediaPlayer? = null

    // 播放指定路径的音频文件
    suspend fun playAudio(filePath: String, onCompletion: () -> Unit = {}) {
        return suspendCoroutine { continuation ->
            try {
                // 检查文件是否存在
                if (!File(filePath).exists()) {
                    Log.e(TAG, "音频文件不存在: $filePath")
                    continuation.resume(Unit) // 文件不存在，直接结束
                    return@suspendCoroutine
                }

                // 初始化 MediaPlayer
                mediaPlayer?.release() // 释放之前的资源
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(filePath)
                    prepareAsync()
                    setOnPreparedListener { start() }
                    setOnCompletionListener {
                        releasePlayer()
                        onCompletion()
                        continuation.resume(Unit) // 播放完成后恢复协程
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "播放错误: what=$what, extra=$extra")
                        releasePlayer()
                        continuation.resume(Unit) // 出错时也恢复协程
                        true
                    }
                }
                Log.i(TAG, "开始播放: $filePath")
            } catch (e: Exception) {
                Log.e(TAG, "播放失败", e)
                releasePlayer()
                continuation.resume(Unit) // 异常时恢复协程
            }
        }
    }



    // 停止播放并释放资源
    fun stop() {
        mediaPlayer?.stop()
        releasePlayer()
    }

    // 释放 MediaPlayer 资源
    private fun releasePlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
        Log.i(TAG, "资源已释放")
    }
}