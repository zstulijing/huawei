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
//
//
//class MainActivity : AppCompatActivity() {
//    private val logger = Logger(this::class.java.simpleName)
//    private lateinit var appController: AppController
//
//    // UI Components
//    private lateinit var recognitionResultText: TextView
//    private lateinit var statusText: TextView
//    private lateinit var startButton: Button
//    private lateinit var stopButton: Button
//    private lateinit var translationResultText: TextView
//    private lateinit var gestureStatusText: TextView
//    private lateinit var genderStatusText: TextView
//    private lateinit var usageResultText: TextView
//
//    private var isRecording = false
//
//    // 现代权限请求方式
//    private val requiredPermissions = arrayOf(
//        Manifest.permission.CAMERA,
//        Manifest.permission.RECORD_AUDIO
//    )
//
//    private val requestPermissionLauncher = registerForActivityResult(
//        ActivityResultContracts.RequestMultiplePermissions()
//    ) { permissions ->
//        handlePermissionResult(permissions)
//    }
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_main)
//        initializeViews()
//        checkAndRequestPermissions()
//        initializeController()
//        setupListeners()
//    }
//
//    private fun initializeViews() {
//        recognitionResultText = findViewById(R.id.recognitionResultText)
//        statusText = findViewById(R.id.statusText)
//        startButton = findViewById(R.id.startButton)
//        stopButton = findViewById(R.id.stopButton)
//        translationResultText = findViewById(R.id.translationResultText)
//        gestureStatusText = findViewById(R.id.gestureStatusText)
//        genderStatusText = findViewById(R.id.genderStatusText)
//        usageResultText = findViewById(R.id.usageResultText)
//        stopButton.isEnabled = false
//    }
//
//    private fun checkAndRequestPermissions() {
//        if (!hasRequiredPermissions()) {
//            requestPermissionLauncher.launch(requiredPermissions)
//        }
//    }
//
//    private fun hasRequiredPermissions(): Boolean {
//        return requiredPermissions.all {
//            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
//        }
//    }
//
//    private fun handlePermissionResult(permissions: Map<String, Boolean>) {
//        val allGranted = permissions.all { it.value }
//        if (!allGranted) {
//            Toast.makeText(
//                this,
//                "Some permissions are required for full functionality",
//                Toast.LENGTH_LONG
//            ).show()
//        }
//    }
//
//    private fun initializeController() {
//        appController = AppController.getInstance()
//        appController.initialize(this)
//
//        appController.setRecognitionCallback { result ->
//            runOnUiThread { recognitionResultText.text = result }
//        }
//
//        appController.setTranslationCallback { result ->
//            if (result == "<|STX|>") {
//               // 开始字符，清空屏幕
//                runOnUiThread {
//                    translationResultText.text = ""
//                }
//            } else {
//                // 翻译结果
//                runOnUiThread {
//                    translationResultText.append(result)
//                }
//            }
//
//        }
//
//        appController.setGestureCallback { gesture ->
//            runOnUiThread {
//                gestureStatusText.text = "Detected gesture: $gesture"
//                when (gesture) {
//                    GestureDetector.GestureType.FIST -> {
//                        if (isRecording) {
//                            stopRecording()
//                            logger.debug("Gesture detected: FIST, stopping recording.")
//                        } else {
//                            startRecording()
//                            logger.debug("Gesture detected: FIST, starting recording.")
//                        }
//                    }
//                    else -> logger.debug("Unhandled gesture: $gesture")
//                }
//            }
//        }
//
//        appController.setGenderCallback { gender ->
//            runOnUiThread { genderStatusText.text = "Detected gender: $gender" }
//        }
//
//        appController.setUsageCallback { usage ->
//            runOnUiThread { usageResultText.text = usage }
//        }
//
//        appController.setStatusCallback { status ->
//            runOnUiThread { statusText.text = status }
//        }
//
//
//    }
//
//    private fun setupListeners() {
//        startButton.setOnClickListener { startRecording() }
//        stopButton.setOnClickListener { stopRecording() }
//    }
//
//    private fun startRecording() {
//        if (!hasRequiredPermissions()) {
//            Toast.makeText(this, "Missing required permissions", Toast.LENGTH_SHORT).show()
//            requestPermissionLauncher.launch(requiredPermissions)
//            return
//        }
//
//        appController.startRecording()
//        isRecording = true
//        updateButtonState(isRecording = isRecording)
//    }
//
//    private fun stopRecording() {
//        appController.stopRecording()
//        isRecording = false
//        updateButtonState(isRecording = isRecording)
//    }
//
//    private fun updateButtonState(isRecording: Boolean) {
//        startButton.isEnabled = !isRecording
//        stopButton.isEnabled = isRecording
//    }
//
//
//    override fun onResume() {
//        super.onResume()
//        appController.onResume()
//    }
//
//    override fun onPause() {
//        appController.onPause()
//        super.onPause()
//    }
//
//    override fun onDestroy() {
//        appController.onDestroy()
//        super.onDestroy()
//    }
//}

class MainActivity : AppCompatActivity() {
    private val logger = Logger(this::class.java.simpleName)
    private lateinit var appController: AppController

    // UI Components
    // 左半边
    private lateinit var translationTextLeft: TextView
    private lateinit var bandwidthTextLeft: TextView
    private lateinit var rttTextLeft: TextView
    private lateinit var batteryTextLeft: TextView
    private lateinit var algorithmTextLeft: TextView
    private lateinit var powerTextLeft: TextView
    private lateinit var temperatureTextLeft: TextView
    private lateinit var firstTokenTextLeft: TextView
    // 右半边
    private lateinit var translationTextRight: TextView
    private lateinit var bandwidthTextRight: TextView
    private lateinit var rttTextRight: TextView
    private lateinit var batteryTextRight: TextView
    private lateinit var algorithmTextRight: TextView
    private lateinit var powerTextRight: TextView
    private lateinit var temperatureTextRight: TextView
    private lateinit var firstTokenTextRight: TextView

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
        supportActionBar?.hide() // 隐藏整个 ActionBar，包括标题
    }

    private fun initializeViews() {
        // 左半边
        translationTextLeft = findViewById(R.id.translation_text_left)
        translationTextLeft.background = null
        bandwidthTextLeft = findViewById(R.id.left_bandwidth)
        rttTextLeft = findViewById(R.id.left_rtt)
        batteryTextLeft = findViewById(R.id.left_battery)
        algorithmTextLeft = findViewById(R.id.left_algorithm)
        powerTextLeft = findViewById(R.id.left_power)
        temperatureTextLeft = findViewById(R.id.left_temperature)
        firstTokenTextLeft = findViewById(R.id.left_first_token)

        // 右半边
        translationTextRight = findViewById(R.id.translation_text_right)
        translationTextRight.background = null
        bandwidthTextRight = findViewById(R.id.right_bandwidth)
        rttTextRight = findViewById(R.id.right_rtt)
        batteryTextRight = findViewById(R.id.right_battery)
        algorithmTextRight = findViewById(R.id.right_algorithm)
        powerTextRight = findViewById(R.id.right_power)
        temperatureTextRight = findViewById(R.id.right_temperature)
        firstTokenTextRight = findViewById(R.id.right_first_token)
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
            runOnUiThread { }
        }

        appController.setTranslationCallback { result ->
            if (result == "<|STX|>") {
                // 开始字符，清空屏幕
                runOnUiThread {
                    translationTextLeft.text = ""
                    translationTextRight.text = ""
                }
            } else {
                // 翻译结果
                runOnUiThread {
                    translationTextLeft.append(result)
                    translationTextRight.append(result)
                }
            }

        }

        appController.setGestureCallback { gesture ->
            runOnUiThread { }
        }

        appController.setGenderCallback { gender ->
            runOnUiThread { }
        }

        appController.setUsageCallback { usage ->
            runOnUiThread { }
        }

        appController.setStatusCallback { status ->
            runOnUiThread { }
        }

        /**
         * add
         */
        appController.setBandWidthCallback { bandWidth ->
            runOnUiThread {
                bandwidthTextLeft.text = "带宽：$bandWidth Mbps"
                bandwidthTextRight.text = "带宽：$bandWidth Mbps"
            }
        }
        appController.setRttCallback { rtt ->
            runOnUiThread {
                rttTextLeft.text = "RTT：$rtt ms"
                rttTextRight.text = "RTT：$rtt ms"
            }
        }
        appController.setBatteryCallback { battery ->
            runOnUiThread {
                batteryTextLeft.text = "电量：$battery %"
                batteryTextRight.text = "电量：$battery %"
            }
        }
        appController.setAlgorithmCallback { algorithm ->
            runOnUiThread {
                algorithmTextLeft.text = "使用策略：$algorithm"
                algorithmTextRight.text = "使用策略：$algorithm"
            }
        }
        appController.setPowerCallback { power ->
            runOnUiThread {
                powerTextLeft.text = "功耗：$power w"
                powerTextRight.text = "功耗：$power w"
            }
        }
        appController.setTemperatureCallback { temperature ->
            runOnUiThread {
                temperatureTextLeft.text = "CPU温度：$temperature °C"
                temperatureTextRight.text = "CPU温度：$temperature °C"
            }
        }
        appController.setFirstTokenCallback { firstToken ->
            runOnUiThread {
                firstTokenTextLeft.text = "首Token时延：$firstToken ms"
                firstTokenTextRight.text = "首Token时延：$firstToken ms"
            }
        }

    }

    private fun setupListeners() {

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