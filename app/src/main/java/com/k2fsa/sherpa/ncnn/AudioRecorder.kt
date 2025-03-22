package com.k2fsa.sherpa.ncnn

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread


private const val TAG = "AudioRecorder"

class AudioRecorder(private val activity: AppCompatActivity) {

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    private val audioSource = MediaRecorder.AudioSource.MIC
    private val sampleRateInHz = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val isRecording = AtomicBoolean(false)

    // Added to track if the AudioRecord is initialized
    private var isInitialized = false

    init {
        initAudioRecord()
    }

    // Moved initialization to a separate method so it can be called when needed
    private fun initAudioRecord() {
        if (hasPermission() && !isInitialized) {
            val numBytes = AudioRecord.getMinBufferSize(
                sampleRateInHz,
                channelConfig,
                audioFormat
            )
            try {
                audioRecord = AudioRecord(
                    audioSource,
                    sampleRateInHz,
                    channelConfig,
                    audioFormat,
                    numBytes * 2 // 双倍 buffer
                )
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord initialization failed")
                    audioRecord = null
                } else {
                    isInitialized = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing AudioRecord: ${e.message}")
                audioRecord = null
            }
        } else if (!hasPermission()) {
            Log.e(TAG, "Recording permission not granted in init")
        }
    }

    fun hasPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            activity,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun startRecording(recordingCallback: ((FloatArray) -> Unit)?): Boolean {
        if (isRecording.get()) return true

        if (!hasPermission()) {
            Log.e(TAG, "Recording permission not granted")
            return false
        }

        // Initialize AudioRecord if it's not already initialized
        if (!isInitialized) {
            initAudioRecord()
        }

        if (audioRecord == null) {
            Log.e(TAG, "AudioRecord is not initialized")
            return false
        }

        try {
            audioRecord?.startRecording()
            isRecording.set(true)
            recordingThread = thread(start = true) {
                //处理音频数据
                val interval = 0.1 // 100ms chunks
                val samplesPerInterval = (interval * sampleRateInHz).toInt()
                val buffer = ShortArray(samplesPerInterval)

                while (isRecording.get()) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize > 0) {
                        val samples = FloatArray(readSize) { buffer[it] / 32768.0f }
                        recordingCallback?.invoke(samples)
                    }
                }
            }
            Log.i(TAG, "Started recording")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording: ${e.message}")
            release() // Release resources on error
            return false
        }
    }

    // Modified to only stop recording but not release resources
    fun stopRecording() {
        if (!isRecording.get()) return

        isRecording.set(false)

        try {
            // Just stop the recording, don't release the AudioRecord
            audioRecord?.stop()

            // Wait for the recording thread to finish
            recordingThread?.join(1000)
            recordingThread = null

            Log.i(TAG, "Stopped recording")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
        }
    }

    // New method to release all resources
    fun release() {
        stopRecording() // Make sure recording is stopped
        try {
            audioRecord?.release()
            audioRecord = null
            isInitialized = false
            Log.i(TAG, "Released all audio resources")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord: ${e.message}")
        }
    }

    fun getSampleRate(): Int = sampleRateInHz
}