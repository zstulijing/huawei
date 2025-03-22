//package com.k2fsa.sherpa.ncnn.video
//
//import android.graphics.Bitmap
//import android.graphics.BitmapFactory
//import android.graphics.ImageFormat
//import android.graphics.Rect
//import android.graphics.YuvImage
//import java.io.ByteArrayOutputStream
//import java.util.concurrent.ArrayBlockingQueue
//
//private const val TAG = "VideoFrameProcessor"
//
///**
// * Processes video frames for detection tasks.
// * Maintains a queue of recent frames and converts them to the format needed by detectors.
// */
//class VideoFrameProcessor(private val queueSize: Int = 5) {
//    // Frame buffer to hold captured frames
//    private val frameQueue = ArrayBlockingQueue<Frame>(queueSize)
//    /**
//     * Adds a new frame to the queue, removing oldest frame if necessary
//     */
//    fun addFrame(data: ByteArray, width: Int, height: Int) {
//        if (frameQueue.remainingCapacity() == 0) {
//            frameQueue.poll()
//        }
//        frameQueue.offer(Frame(data, width, height))
//    }
//
//    /**
//     * Returns the most recent frames as bitmaps
//     */
//    fun getLatestFrameBitmaps(count: Int): List<Bitmap> {
//        val frames = synchronized(frameQueue) {
//            val frameCount = minOf(count, frameQueue.size)
//            val frameArray = frameQueue.toArray()
//            (frameArray.size - frameCount until frameArray.size).map {
//                frameArray[it] as Frame
//            }
//        }
//
//        // Convert frames to bitmaps directly
//        return frames.map { frame ->
//            yuv420ToBitmap(frame.data, frame.width, frame.height)
//        }
//    }
//
//    /**
//     * Gets frames processed for gesture detection
//     */
//    fun getFramesForGestureDetection(count: Int): List<Bitmap> {
//        return getLatestFrameBitmaps(count)
//    }
//
//    /**
//     * Gets frames processed for gender detection
//     */
//    fun getFramesForGenderDetection(count: Int): List<Bitmap> {
//        return getLatestFrameBitmaps(count)
//    }
//
//    /**
//     * Converts YUV420 format to Bitmap
//     */
//    private fun yuv420ToBitmap(data: ByteArray, width: Int, height: Int): Bitmap {
//        val yuvImage = YuvImage(data, ImageFormat.NV21, width, height, null)
//        val out = ByteArrayOutputStream()
//        yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, out)
//        val imageBytes = out.toByteArray()
//        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
//    }
//
//    /**
//     * Clears all frames from the queue
//     */
//    fun clearQueue() {
//        frameQueue.clear()
//    }
//
//    /**
//     * Releases all resources
//     */
//    fun shutdown() {
//        clearQueue()
//    }
//}

package com.k2fsa.sherpa.ncnn.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Environment
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue

private const val TAG = "VideoFrameProcessor"

/**
 * Processes video frames for detection tasks with debug capabilities
 */
class VideoFrameProcessor(
    private val context: Context,
    private val queueSize: Int = 5,
    private var debugSaveImages: Boolean = false
) {
    // Frame buffer to hold captured frames
    private val frameQueue = ArrayBlockingQueue<Frame>(queueSize)

    /**
     * Enable/disable debug image saving feature
     */
    fun setDebugSaveImages(enabled: Boolean) {
        debugSaveImages = enabled
        if (enabled) {
            Log.d(TAG, "Debug image saving enabled")
        }
    }

    /**
     * Adds a new frame to the queue, removing oldest frame if necessary
     */
    fun addFrame(data: ByteArray, width: Int, height: Int) {
        if (frameQueue.remainingCapacity() == 0) {
            frameQueue.poll()
        }
        frameQueue.offer(Frame(data, width, height))
    }

    /**
     * Returns the most recent frames as bitmaps
     */
    fun getLatestFrameBitmaps(count: Int): List<Bitmap> {
        val frames = synchronized(frameQueue) {
            val frameCount = minOf(count, frameQueue.size)
            val frameArray = frameQueue.toArray()
            (frameArray.size - frameCount until frameArray.size).map {
                frameArray[it] as Frame
            }
        }

        return frames.map { frame ->
            yuv420ToBitmap(frame.data, frame.width, frame.height).apply {
                if (debugSaveImages) {
                    saveDebugImage(this)  // Save image if debug mode enabled
                }
            }
        }
    }

    // Existing detection methods remain the same
    fun getFramesForGestureDetection(count: Int) = getLatestFrameBitmaps(count)
    fun getFramesForGenderDetection(count: Int) = getLatestFrameBitmaps(count)

    /**
     * Converts YUV420 format to Bitmap
     */
    private fun yuv420ToBitmap(data: ByteArray, width: Int, height: Int): Bitmap {
        val yuvImage = YuvImage(data, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream().apply {
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, this)
        }
        return BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
    }

    /**
     * Saves bitmap to external storage for debugging
     */
    private fun saveDebugImage(bitmap: Bitmap) {
        try {
            val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            val filename = "frame_${System.currentTimeMillis()}.jpg"
            val imageFile = File(storageDir, filename)

            FileOutputStream(imageFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                fos.flush()
                Log.d(TAG, "Debug image saved to: ${imageFile.absolutePath}")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error saving debug image", e)
        }
    }

    fun clearQueue() = frameQueue.clear()
    fun shutdown() = clearQueue()
}

