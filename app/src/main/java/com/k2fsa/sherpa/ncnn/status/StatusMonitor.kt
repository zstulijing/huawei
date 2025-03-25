package com.k2fsa.sherpa.ncnn.status
import androidx.lifecycle.LiveData
import android.os.BatteryManager
import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.util.Log
import android.os.Process


class StatusMonitor(private val mainScope: CoroutineScope, private val context: Context) {
    private var powerMonitorJob: Job? = null
    private val batteryStatus = MutableLiveData<Int>()
    private val cpuUsage = MutableLiveData<Float>()

    private val memoryUsage = MutableLiveData<MemoryStats>()
    private var memoryMonitorJob: Job? = null

    /**
     * 启动功耗监测（建议在初始化完成后调用）
     */
    fun startPowerMonitoring() {
        stopPowerMonitoring() // 防止重复启动

        powerMonitorJob = mainScope.launch {
            while (true) {
                // 每5秒收集一次数据
                delay(5_000)
                collectBatteryStats()
                collectCpuUsage()
            }
        }
    }

    /**
     * 停止功耗监测
     */
    fun stopPowerMonitoring() {
        powerMonitorJob?.cancel()
        powerMonitorJob = null
    }

    // 启动内存监测
    fun startMemoryMonitoring() {
        stopMemoryMonitoring()

        memoryMonitorJob = mainScope.launch {
            while (true) {
                delay(5_000) // 每5秒收集一次数据
                collectMemoryStats()
            }
        }
    }

    // 停止内存监测
    fun stopMemoryMonitoring() {
        memoryMonitorJob?.cancel()
        memoryMonitorJob = null
    }

    /**
     * 获取电量状态LiveData（百分比）
     */
    fun getBatteryStats(): LiveData<Int> = batteryStatus
    /**
     * 获取CPU使用率LiveData（百分比）
     */
    fun getCpuUsage(): LiveData<Float> = cpuUsage
    // 获取内存状态LiveData
    fun getMemoryStats(): LiveData<MemoryStats> = memoryUsage

    private fun collectBatteryStats() {
        mainScope.launch(Dispatchers.IO) {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val currentLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            batteryStatus.postValue(currentLevel)

            // 估算瞬时电流（单位：微安）
            val currentNow = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            // Log.e("usage", "瞬时电流: ${currentNow}μA")
            Log.d("usage", "当前设备电量: ${currentLevel}%")
        }
    }


    private fun collectCpuUsage() {
        mainScope.launch(Dispatchers.Default) {
            try {
                // 第一次采样
                val firstTime = SystemClock.elapsedRealtime()
                val firstCpuTime = Process.getElapsedCpuTime()
                delay(3000)
                // 第二次采样
                val deltaTime = SystemClock.elapsedRealtime() - firstTime
                val deltaCpuTime = Process.getElapsedCpuTime() - firstCpuTime

                // 计算 CPU 使用率百分比
                val cpuUsage = (deltaCpuTime.toFloat() / deltaTime) * 100
                Log.d("usage", "CPU Usage: ${"%.1f".format(cpuUsage)}%")

            } catch (e: Exception) {
                Log.e("usage", "监控失败", e)
            }
        }
    }


    private fun collectMemoryStats() {
        mainScope.launch(Dispatchers.IO) {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(mi)

            // 获取应用内存使用情况
            val appMemory = activityManager.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))[0]

            // 获取堆内存信息（兼容新旧版本）
            val debugInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(debugInfo)


            /*val stats = MemoryStats(
                totalMemory = bytesToMb(mi.totalMem),
                freeMemory = bytesToMb(mi.availMem),
                appRamUsage = bytesToMb(appMemory.totalPrivateDirty * 1024L),
            )
            */

            val totalMemory = bytesToMb(mi.totalMem)
            val freeMemory = bytesToMb(mi.availMem)
            val appRamUsage = bytesToMb(appMemory.totalPrivateDirty * 1024L)

            //memoryUsage.postValue(stats)
            //Log.e("xxx", "内存统计: $stats")
            Log.d("usage",
                "内存状态 - 设备总内存: ${totalMemory}MB | 当前可用内存: ${freeMemory}MB | 应用占用内存: ${appRamUsage}MB")
        }
    }

    private fun bytesToMb(bytes: Long): Long {
        return bytes / (1024 * 1024)
    }
}