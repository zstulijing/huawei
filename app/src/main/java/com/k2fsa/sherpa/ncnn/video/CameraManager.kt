package com.k2fsa.sherpa.ncnn.video

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

private const val TAG = "CameraManager"

class CameraManager(private val activity: AppCompatActivity) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    private val cameraOpenCloseLock = Semaphore(1)
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    /**
     * Captures a specified number of frames from the camera and returns them via callback.
     * This method handles the complete lifecycle: opening camera, capturing frames, and closing.
     *
     * @param frameCount Number of frames to capture
     * @param callback Callback that receives the list of captured frames as ByteArray
     */
    fun captureFrames(frameCount: Int, callback: (List<Frame>) -> Unit) {
        if (!hasPermission()) {
            Log.e(TAG, "Camera permission not granted")
            return
        }

//        val frames = mutableListOf<ByteArray>()
        val frames = mutableListOf<Frame>()
        val frameLock = Object()

        // Start background thread and prepare camera resources
        startBackgroundThread()

        // Set up image reader callback to collect frames
//        val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
//            val image = reader.acquireLatestImage()
//            image?.let {
//                try {
//                    // Process the image data - extracting Y channel for simplicity
//                    val width = it.width
//                    val height = it.height
//                    val plane = it.planes[0]
//                    val buffer = plane.buffer
//                    val bytes = ByteArray(buffer.remaining())
//                    buffer.get(bytes)
//
//                    synchronized(frameLock) {
//                        frames.add(Frame(bytes, width, height))
//                        Log.d(TAG, "Captured frame ${frames.size}/$frameCount (${width}x${height})")
////                        frames.add(bytes)
////                        Log.d(TAG, "Captured frame ${frames.size}/$frameCount")
//
//                        if (frames.size >= frameCount) {
//                            frameLock.notify()
//                        }
//                    }
//                } finally {
//                    it.close()
//                }
//            }
//        }
        val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
            val image = reader.acquireLatestImage()
            image?.let {
                try {
                    val width = it.width
                    val height = it.height
                    val nv21Bytes = convertYUV420888ToNV21(it) // 转换函数

                    synchronized(frameLock) {
                        frames.add(Frame(nv21Bytes, width, height))
                        if (frames.size >= frameCount) {
                            frameLock.notify()
                        }
                    }
                } finally {
                    it.close()
                }
            }
        }

        // Open camera and start capture
        openCamera(imageAvailableListener)

        // Wait for frames to be collected or timeout
        synchronized(frameLock) {
            if (frames.size < frameCount) {
                try {
                    frameLock.wait(2000) // 5 second timeout
                } catch (e: InterruptedException) {
                    Log.e(TAG, "Frame capture interrupted: ${e.message}")
                }
            }
        }

        // Clean up resources
        closeCamera()
        stopBackgroundThread()

        // Return whatever frames we've collected
        callback(frames)
    }

    private fun hasPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            activity,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply {
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
            Log.e(TAG, "Error stopping background thread: ${e.message}")
        }
    }

    private fun openCamera(imageAvailableListener: ImageReader.OnImageAvailableListener) {
        val cameraManager = activity.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager

        try {
            // Find appropriate camera (prefer front camera if available)
            val cameraId = cameraManager.cameraIdList.firstOrNull {
                val characteristics = cameraManager.getCameraCharacteristics(it)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList[0]


            // Set up ImageReader for receiving frames
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw RuntimeException("Cannot get available preview sizes")

            val largest = map.getOutputSizes(ImageReader::class.java).maxByOrNull { it.width * it.height }
                ?: throw RuntimeException("Cannot find suitable preview size")

            imageReader = ImageReader.newInstance(
                largest.width, largest.height,
                android.graphics.ImageFormat.YUV_420_888, 1
            ).apply {
                setOnImageAvailableListener(imageAvailableListener, backgroundHandler)
            }

            // Open the camera
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening")
            }

            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                cameraOpenCloseLock.release()
                return
            }

            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera: ${e.message}")
            cameraOpenCloseLock.release()
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice = camera
            createCaptureSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
            Log.e(TAG, "Camera device error: $error")
        }
    }

    private fun createCaptureSession() {
        try {
            val surface = imageReader?.surface ?: return

            cameraDevice?.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return

                        captureSession = session
                        try {
                            val captureBuilder = cameraDevice?.createCaptureRequest(
                                CameraDevice.TEMPLATE_PREVIEW
                            )?.apply {
                                addTarget(surface)
                                // Optimize for faster frame capture
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            }

                            captureBuilder?.let {
                                session.setRepeatingRequest(it.build(), null, backgroundHandler)
                            }
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "Error setting up capture request: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Failed to configure camera session")
                    }
                },
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error creating camera capture session: ${e.message}")
        }
    }

    private fun convertYUV420888ToNV21(image: Image): ByteArray {
        // Step 1: 提前复制所有数据到本地内存
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()

        // 创建本地拷贝
        val yBytes = ByteArray(yBuffer.remaining()).apply { yBuffer.get(this) }
        val uBytes = ByteArray(uBuffer.remaining()).apply { uBuffer.get(this) }
        val vBytes = ByteArray(vBuffer.remaining()).apply { vBuffer.get(this) }

        // Step 2: 构建 NV21 数据
        val width = image.width
        val height = image.height
        val nv21 = ByteArray(width * height * 3 / 2)

        // 填充 Y 平面
        System.arraycopy(yBytes, 0, nv21, 0, yBytes.size)

        // Step 3: 处理 UV 平面（考虑像素跨距和行跨距）
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        val uvWidth = width / 2
        val uvHeight = height / 2

        var uvIndex = 0
        var nv21Index = width * height

        for (row in 0 until uvHeight) {
            var uRowStart = row * uvRowStride
            var vRowStart = row * uvRowStride

            for (col in 0 until uvWidth) {
                val uPos = uRowStart + col * uvPixelStride
                val vPos = vRowStart + col * uvPixelStride

                // 交替填充 V/U（NV21 格式要求 V 在前）
                nv21[nv21Index++] = vBytes[vPos]
                nv21[nv21Index++] = uBytes[uPos]
            }
        }

        return nv21
    }

//    // 新增 YUV420_888 转 NV21 工具函数
//    private fun convertYUV420888ToNV21(image: Image): ByteArray {
//        val yPlane = image.planes[0]
//        val uPlane = image.planes[1]
//        val vPlane = image.planes[2]
//
//        val yBuffer = yPlane.buffer
//        val uBuffer = uPlane.buffer
//        val vBuffer = vPlane.buffer
//
//        val ySize = yBuffer.remaining()
//        val nv21 = ByteArray(ySize + (image.width * image.height / 2))
//
//        // Copy Y channel
//        yBuffer.get(nv21, 0, ySize)
//
//        val uvRowStride = uPlane.rowStride
//        val uvPixelStride = uPlane.pixelStride
//        val uvWidth = image.width / 2
//        val uvHeight = image.height / 2
//
//        // Interleave U/V channels with V first
//        var offset = ySize
////        var uIndex = 0
////        var vIndex = 0
//        for (i in 0 until uvHeight) {
//            for (j in 0 until uvWidth) {
//                val uvOffset = i * uvRowStride + j * uvPixelStride
//                nv21[offset++] = vBuffer.get(uvOffset)
//                nv21[offset++] = uBuffer.get(uvOffset)
//            }
//        }
//
//        return nv21
//    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error closing camera: ${e.message}")
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    fun release(){
        closeCamera()
    }
}

//package com.k2fsa.sherpa.ncnn.video
//
//import android.Manifest
//import android.content.Context
//import android.content.pm.PackageManager
//import android.hardware.camera2.*
//import android.media.ImageReader
//import android.os.Handler
//import android.os.HandlerThread
//import android.util.Log
//import android.util.Size
//import android.view.Surface
//import androidx.appcompat.app.AppCompatActivity
//import androidx.core.app.ActivityCompat
//import java.util.concurrent.Semaphore
//import java.util.concurrent.TimeUnit
//import kotlin.concurrent.thread
//
//private const val TAG = "CameraManager"
//private const val REQUEST_CAMERA_PERMISSION = 201
//
//class CameraManager(private val activity: AppCompatActivity) {
//    private val permissions = arrayOf(Manifest.permission.CAMERA)
//
//    private var cameraDevice: CameraDevice? = null
//    private var captureSession: CameraCaptureSession? = null
//    private var imageReader: ImageReader? = null
//
//    private val cameraOpenCloseLock = Semaphore(1)
//    private var backgroundThread: HandlerThread? = null
//    private var backgroundHandler: Handler? = null
//
//    private var isCapturing = false
//    private var scheduledCaptureThread: Thread? = null
//
//    private var frameCallback: ((ByteArray, Int, Int) -> Unit)? = null
//
//
//    fun hasPermission(): Boolean {
//        return ActivityCompat.checkSelfPermission(
//            activity,
//            Manifest.permission.CAMERA
//        ) == PackageManager.PERMISSION_GRANTED
//    }
//
//
//    fun startBackgroundThread() {
//        backgroundThread = HandlerThread("CameraBackground").apply {
//            start()
//            backgroundHandler = Handler(looper)
//        }
//    }
//
//    fun stopBackgroundThread() {
//        backgroundThread?.quitSafely()
//        try {
//            backgroundThread?.join()
//            backgroundThread = null
//            backgroundHandler = null
//        } catch (e: InterruptedException) {
//            Log.e(TAG, "Error stopping background thread: ${e.message}")
//        }
//    }
//
//
//    fun captureFrames(frameCount: Int, callback: (List<ByteArray>) -> Unit) {
//        if (!hasPermission()) {
//            Log.e(TAG, "Camera permission not granted")
////            requestPermissions()
//            return
//        }
//
//        val frames = mutableListOf<ByteArray>()
//        val frameLock = Object()
//
//        // Set a temporary frame callback that collects frames
//        val originalCallback = frameCallback
//        frameCallback = { data, width, height ->
//            synchronized(frameLock) {
//                frames.add(data)
//                if (frames.size >= frameCount) {
//                    // Reset the callback
//                    frameCallback = originalCallback
//                    callback(frames)
//                    frameLock.notify()
//                }
//            }
//        }
//
//        // Open camera, capture frames, then close
//        startBackgroundThread()
//        openCamera()
//
//        // Wait for frames to be collected or timeout
//        synchronized(frameLock) {
//            if (frames.size < frameCount) {
//                try {
//                    frameLock.wait(5000) // 5 second timeout
//                } catch (e: InterruptedException) {
//                    Log.e(TAG, "Frame capture interrupted: ${e.message}")
//                }
//            }
//        }
//
//        // Clean up if timeout occurred
//        if (frames.size < frameCount) {
//            frameCallback = originalCallback
//            callback(frames)
//        }
//
//        closeCamera()
//        stopBackgroundThread()
//    }
//
//    fun openCamera() {
//        if (!hasPermission()) {
//            Log.e(TAG, "Camera permission not granted")
////            requestPermissions()
//            return
//        }
//
//        val cameraManager = activity.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
//
//        try {
//            val cameraId = cameraManager.cameraIdList.firstOrNull {
//                val characteristics = cameraManager.getCameraCharacteristics(it)
//                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
//                facing == CameraCharacteristics.LENS_FACING_FRONT
//            } ?: cameraManager.cameraIdList[0]
//
//            // Set up ImageReader for receiving frames
//            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
//            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
//                ?: throw RuntimeException("Cannot get available preview sizes")
//
//            val largest = map.getOutputSizes(ImageReader::class.java).maxByOrNull { it.width * it.height }
//                ?: throw RuntimeException("Cannot find suitable preview size")
//
//            imageReader = ImageReader.newInstance(
//                largest.width, largest.height,
//                android.graphics.ImageFormat.YUV_420_888, 2
//            ).apply {
//                setOnImageAvailableListener({ reader ->
//                    val image = reader.acquireLatestImage()
//                    if (image != null) {
//                        try {
//                            // Process the image data
//                            val planes = image.planes
//
//                            // Here we'd typically convert YUV to RGB
//                            // For simplicity, just passing Y channel
//                            val buffer = planes[0].buffer
//                            val bytes = ByteArray(buffer.remaining())
//                            buffer.get(bytes)
//
//                            // Send frame to callback
//                            frameCallback?.invoke(bytes, image.width, image.height)
//                        } finally {
//                            image.close()
//                        }
//                    }
//                }, backgroundHandler)
//            }
//
//            // Open the camera
//            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
//                throw RuntimeException("Time out waiting to lock camera opening")
//            }
//
//            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
//                return
//            }
//
//            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)
//        } catch (e: Exception) {
//            Log.e(TAG, "Error opening camera: ${e.message}")
//        }
//    }
//
//    private val stateCallback = object : CameraDevice.StateCallback() {
//        override fun onOpened(camera: CameraDevice) {
//            cameraOpenCloseLock.release()
//            cameraDevice = camera
//            createCaptureSession()
//        }
//
//        override fun onDisconnected(camera: CameraDevice) {
//            cameraOpenCloseLock.release()
//            camera.close()
//            cameraDevice = null
//        }
//
//        override fun onError(camera: CameraDevice, error: Int) {
//            cameraOpenCloseLock.release()
//            camera.close()
//            cameraDevice = null
//            Log.e(TAG, "Camera device error: $error")
//        }
//    }
//
//    private fun createCaptureSession() {
//        try {
//            val surface = imageReader?.surface
//            if (surface == null) {
//                Log.e(TAG, "ImageReader surface is null")
//                return
//            }
//
//            cameraDevice?.createCaptureSession(
//                listOf(surface),
//                object : CameraCaptureSession.StateCallback() {
//                    override fun onConfigured(session: CameraCaptureSession) {
//                        if (cameraDevice == null) return
//
//                        captureSession = session
//                        try {
//                            val captureBuilder = cameraDevice?.createCaptureRequest(
//                                CameraDevice.TEMPLATE_PREVIEW
//                            )?.apply {
//                                addTarget(surface)
//                            }
//
//                            captureBuilder?.let {
//                                session.setRepeatingRequest(it.build(), null, backgroundHandler)
//                            }
//                        } catch (e: CameraAccessException) {
//                            Log.e(TAG, "Error setting up capture request: ${e.message}")
//                        }
//                    }
//
//                    override fun onConfigureFailed(session: CameraCaptureSession) {
//                        Log.e(TAG, "Failed to configure camera session")
//                    }
//                },
//                backgroundHandler
//            )
//        } catch (e: CameraAccessException) {
//            Log.e(TAG, "Error creating camera capture session: ${e.message}")
//        }
//    }
//
//    fun startPeriodicCapture(captureInterval: Long = 5000, frameCount: Int = 5) {
//        if (isCapturing) return
//
//        isCapturing = true
//
//        scheduledCaptureThread = thread {
//            while (isCapturing) {
//                // Open camera
//                startBackgroundThread()
//                openCamera()
//
//                // Wait for frames to be captured
//                Thread.sleep(500) // Give camera time to initialize
//
//                // Capture frames
//                for (i in 0 until frameCount) {
//                    Thread.sleep(200) // Space out the frame captures
//                    // The actual frame capture happens in the ImageReader callback
//                }
//
//                // Close camera after capturing
//                closeCamera()
//                stopBackgroundThread()
//
//                // Wait for next capture interval
//                Thread.sleep(captureInterval - (frameCount * 200) - 500)
//            }
//        }
//    }
//
//    fun stopPeriodicCapture() {
//        isCapturing = false
//        scheduledCaptureThread?.join(1000)
//        closeCamera()
//    }
//
//    fun closeCamera() {
//        try {
//            cameraOpenCloseLock.acquire()
//            captureSession?.close()
//            captureSession = null
//            cameraDevice?.close()
//            cameraDevice = null
//            imageReader?.close()
//            imageReader = null
//        } catch (e: InterruptedException) {
//            Log.e(TAG, "Error closing camera: ${e.message}")
//        } finally {
//            cameraOpenCloseLock.release()
//        }
//    }
//
//    fun release() {
//        // 停止定时捕获线程
//        stopPeriodicCapture()
//        // 关闭摄像头
//        closeCamera()
//        // 停止后台线程
//        stopBackgroundThread()
//        Log.d(TAG, "All camera resources have been released.")
//    }
//}
