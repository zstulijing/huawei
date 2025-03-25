package com.k2fsa.sherpa.ncnn

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.ncnn.control.AppController
import com.k2fsa.sherpa.ncnn.gesture.GestureDetector
import com.k2fsa.sherpa.ncnn.utils.Logger


class MainActivity : AppCompatActivity() {
    private val logger = Logger(this::class.java.simpleName)
    private lateinit var appController: AppController

    // UI Components
    private lateinit var recognitionResultText: TextView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var translationResultText: TextView
    private lateinit var gestureStatusText: TextView
    private lateinit var genderStatusText: TextView
    //
    private var isRecording = false

    // 现代权限请求方式
    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        handlePermissionResult(permissions)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initializeViews()
        checkAndRequestPermissions()
        initializeController()
        setupListeners()
    }

    private fun initializeViews() {
        recognitionResultText = findViewById(R.id.recognitionResultText)
        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        translationResultText = findViewById(R.id.translationResultText)
        gestureStatusText = findViewById(R.id.gestureStatusText)
        genderStatusText = findViewById(R.id.genderStatusText)

        stopButton.isEnabled = false
    }

    private fun checkAndRequestPermissions() {
        if (!hasRequiredPermissions()) {
            requestPermissionLauncher.launch(requiredPermissions)
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun handlePermissionResult(permissions: Map<String, Boolean>) {
        val allGranted = permissions.all { it.value }
        if (!allGranted) {
            Toast.makeText(
                this,
                "Some permissions are required for full functionality",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun initializeController() {
        appController = AppController.getInstance()
        appController.initialize(this)

        appController.setRecognitionCallback { result ->
            runOnUiThread { recognitionResultText.text = result }
        }

        appController.setTranslationCallback { result ->
            runOnUiThread { translationResultText.text = result }
        }

        appController.setGestureCallback { gesture ->
            runOnUiThread {
                gestureStatusText.text = "Detected gesture: $gesture"
                when (gesture) {
                    GestureDetector.GestureType.FIST -> {
                        if (isRecording) {
                            stopRecording()
                            logger.debug("Gesture detected: FIST, stopping recording.")
                        } else {
                            startRecording()
                            logger.debug("Gesture detected: FIST, starting recording.")
                        }
                    }
                    else -> logger.debug("Unhandled gesture: $gesture")
                }
            }
        }

        appController.setGenderCallback { gender ->
            runOnUiThread { genderStatusText.text = "Detected gender: $gender" }
        }

        appController.setStatusCallback { status ->
            runOnUiThread { statusText.text = status }
        }
    }

    private fun setupListeners() {
        startButton.setOnClickListener { startRecording() }
        stopButton.setOnClickListener { stopRecording() }
    }

    private fun startRecording() {
        if (!hasRequiredPermissions()) {
            Toast.makeText(this, "Missing required permissions", Toast.LENGTH_SHORT).show()
            requestPermissionLauncher.launch(requiredPermissions)
            return
        }

        appController.startRecording()
        isRecording = true
        updateButtonState(isRecording = isRecording)
    }

    private fun stopRecording() {
        appController.stopRecording()
        isRecording = false
        updateButtonState(isRecording = isRecording)
    }

    private fun updateButtonState(isRecording: Boolean) {
        startButton.isEnabled = !isRecording
        stopButton.isEnabled = isRecording
    }


    override fun onResume() {
        super.onResume()
        appController.onResume()
    }

    override fun onPause() {
        appController.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        appController.onDestroy()
        super.onDestroy()
    }
}