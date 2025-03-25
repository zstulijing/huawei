package com.k2fsa.sherpa.ncnn.speaker
import android.media.MediaPlayer
import android.util.Log
import java.io.File

class AudioPlayer {
    companion object {
        private const val TAG = "AudioPlayer"
    }

    private var mediaPlayer: MediaPlayer? = null

    // 播放指定路径的音频文件
    fun playAudio(filePath: String, onCompletion: () -> Unit = {}) {
        try {
            // 检查文件是否存在
            if (!File(filePath).exists()) {
                Log.e(TAG, "音频文件不存在: $filePath")
                return
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
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "播放错误: what=$what, extra=$extra")
                    releasePlayer()
                    true
                }
            }
            Log.i(TAG, "开始播放: $filePath")
        } catch (e: Exception) {
            Log.e(TAG, "播放失败", e)
            releasePlayer()
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