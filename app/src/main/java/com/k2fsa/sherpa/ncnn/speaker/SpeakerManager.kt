package com.k2fsa.sherpa.ncnn.speaker
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

private const val TAG = "SpeakerManager"

class SpeakerManager(private val activity: AppCompatActivity) {
    private var mediaPlayer: MediaPlayer? = null
    private val speakerLock = Semaphore(1)
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    /**
     * 播放 MP3 文件
     * val mp3File = File("/path/to/your/audio.mp3")
     * speakerManager.playMp3(mp3File) {
     *     Log.d(TAG, "MP3 播放完成")
     * }
     * @param {File} - mp3File MP3文件
     * @param {() -> Unit} - callback 完成播放的回调函数
     */
    fun playMp3(mp3File: File, callback: () -> Unit) {

        startBackgroundThread()
        try {
            if (!speakerLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("等待锁定扬声器访问超时")
            }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(mp3File.absolutePath)
                prepare() // 准备播放
                setOnCompletionListener {
                    callback.invoke()
                    releaseMediaPlayer()
                    speakerLock.release()
                }
                start() // 开始播放
            }
        } catch (e: Exception) {
            Log.e(TAG, "播放 MP3 出错: ${e.message}")
            speakerLock.release()
            callback.invoke()
        } finally {
            stopBackgroundThread()
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("SpeakerBackground").apply {
            start()
            backgroundHandler = Handler(looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "停止后台线程出错: ${e.message}")
        }
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
    }

    fun release() {
        try {
            speakerLock.acquire()
            releaseMediaPlayer()
            stopBackgroundThread()
            Log.d(TAG, "扬声器资源已释放")
        } catch (e: InterruptedException) {
            Log.e(TAG, "释放扬声器资源出错: ${e.message}")
        } finally {
            speakerLock.release()
        }
    }

}