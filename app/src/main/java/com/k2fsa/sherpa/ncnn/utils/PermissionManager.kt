package com.k2fsa.sherpa.ncnn.utils

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.ncnn.MainActivity

class PermissionManager(private val activity: Activity) {
    private val logger = Logger(this::class.java.simpleName)

    companion object {
        const val PERMISSION_REQUEST_CODE = 1001

        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )

        private val REQUIRED_PERMISSIONS_TIRAMISU = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            REQUIRED_PERMISSIONS
        }

        // Android 13 及以上版本的后台权限
        private val REQUIRED_PERMISSIONS_FOREGROUND_SERVICE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34
            arrayOf(
                Manifest.permission.FOREGROUND_SERVICE_CAMERA,
                Manifest.permission.FOREGROUND_SERVICE_MICROPHONE
            )
        } else {
            emptyArray()
        }

        // 获取所有需要的权限
        fun getAllRequiredPermissions(): Array<String> {
            val basePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                REQUIRED_PERMISSIONS_TIRAMISU
            } else {
                REQUIRED_PERMISSIONS
            }

            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                basePermissions + REQUIRED_PERMISSIONS_FOREGROUND_SERVICE
            } else {
                basePermissions
            }
        }
    }

    // 检查是否所有必要权限都已授予
    fun hasRequiredPermissions(): Boolean {
        return getAllRequiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    // 请求所有必要权限
    fun requestRequiredPermissions() {
        val permissionsNeeded = getAllRequiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                permissionsNeeded,
                PERMISSION_REQUEST_CODE
            )
            logger.debug("Requesting permissions: ${permissionsNeeded.joinToString()}")
        } else {
            logger.debug("All required permissions already granted")
        }
    }

    // 处理权限请求结果
    fun handlePermissionResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val deniedPermissions = permissions.filterIndexed { index, _ ->
                grantResults[index] != PackageManager.PERMISSION_GRANTED
            }

            if (deniedPermissions.isEmpty()) {
                logger.debug("All requested permissions granted")
                // 通知应用其他部分权限已授予
                notifyPermissionsGranted()
            } else {
                logger.warn("Some permissions denied: ${deniedPermissions.joinToString()}")
                // 处理权限被拒绝的情况
                handleDeniedPermissions(deniedPermissions)
            }
        }
    }
    // 通知应用其他部分权限已授予
    private fun notifyPermissionsGranted() {
        // 如果你使用EventBus或其他事件分发机制，可以在这里发送事件
        // 例如: EventBus.getDefault().post(PermissionsGrantedEvent())

        // 或者直接调用需要权限的组件初始化
//        (activity as? MainActivity)?.initializeComponents()
    }

    // 处理被拒绝的权限
    private fun handleDeniedPermissions(deniedPermissions: List<String>) {
        // 检查是否有必要显示权限说明对话框
        val shouldShowRationale = deniedPermissions.any { permission ->
            ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }

        if (shouldShowRationale) {
            // 显示权限说明对话框
            showPermissionRationaleDialog(deniedPermissions)
        } else {
            // 用户选择了"不再询问"，应该引导用户到系统设置中手动授予权限
            showSettingsDialog()
        }
    }

    // 显示权限说明对话框
    private fun showPermissionRationaleDialog(deniedPermissions: List<String>) {
        val message = when {
            deniedPermissions.contains(Manifest.permission.CAMERA) &&
                    deniedPermissions.contains(Manifest.permission.RECORD_AUDIO) ->
                "摄像头和麦克风权限是应用核心功能必需的，请允许这些权限。"
            deniedPermissions.contains(Manifest.permission.CAMERA) ->
                "摄像头权限是应用核心功能必需的，请允许此权限。"
            deniedPermissions.contains(Manifest.permission.RECORD_AUDIO) ->
                "麦克风权限是应用核心功能必需的，请允许此权限。"
            else -> "应用需要这些权限才能正常工作，请允许这些权限。"
        }

        AlertDialog.Builder(activity)
            .setTitle("需要权限")
            .setMessage(message)
            .setPositiveButton("重试") { _, _ ->
                requestRequiredPermissions()
            }
            .setNegativeButton("取消") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    // 显示引导用户到系统设置的对话框
    private fun showSettingsDialog() {
        AlertDialog.Builder(activity)
            .setTitle("需要权限")
            .setMessage("请在系统设置中手动授予应用所需权限")
            .setPositiveButton("去设置") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", activity.packageName, null)
                intent.data = uri
                activity.startActivity(intent)
            }
            .setNegativeButton("取消") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    // 检查特定权限是否已授予
    fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
    }

    // 获取所有必要权限的方法
    private fun getAllRequiredPermissions(): Array<String> {
        return Companion.getAllRequiredPermissions()
    }
}