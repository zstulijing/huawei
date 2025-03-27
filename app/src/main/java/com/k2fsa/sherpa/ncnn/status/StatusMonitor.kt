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
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class StatusMonitor(private val mainScope: CoroutineScope, private val context: Context) {

    @Volatile // 确保多线程可见性
    private var currentVoltageMilli: Int = -1 // 存储原始电压（毫伏）
    private val voltageLock = Any() // 同步锁对象


    private var powerMonitorJob: Job? = null
    private val batteryStatus = MutableLiveData<Int>()
    private val cpuUsage = MutableLiveData<Float>()

    private val memoryUsage = MutableLiveData<MemoryStats>()
    private var memoryMonitorJob: Job? = null

    private var batteryVoltage = 0f // 当前电压（伏特）
    private var lastVoltageUpdateTime = 0L // 最后更新时间戳
    private val voltageListeners = mutableListOf<(Float) -> Unit>() // 监听器列表
    private var batteryReceiverRegistered = false // 接收器状态标记

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val voltageMilli = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            synchronized(voltageLock) {
                currentVoltageMilli = voltageMilli
            }
        }
    }


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


    fun registerBatteryReceiver(context: Context) {
        if (!batteryReceiverRegistered) {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            context.registerReceiver(batteryReceiver, filter)
            batteryReceiverRegistered = true
            Log.d("BatteryMonitor", "电池广播接收器已注册")
        }
    }


    private fun collectBatteryStats() {
        mainScope.launch(Dispatchers.IO) {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val currentLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            batteryStatus.postValue(currentLevel)


            // 读取共享电压值（线程安全）
            val voltageMilli = synchronized(voltageLock) {
                currentVoltageMilli.takeIf { it != -1 } ?: -1
            }
            // 估算瞬时电流（单位：微安）
            val currentNow = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            // 单位转换：(V * A) = (mV/1000) * (μA/1,000,000) = W
            val voltage = voltageMilli / 1000.0 // 转换为伏特
            val current = currentNow / 1_000_000.0 // 转换为安培
            val power = voltage * current // 单位：瓦特

            Log.d("usage", "当前设备电量: ${currentLevel}%")
            Log.d("usage",
                "能耗统计 - 当前电流: ${current}A | 当前电压: ${voltage}V | 当前功率: ${power}W")
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

            val totalMemory = bytesToMb(mi.totalMem)
            val freeMemory = bytesToMb(mi.availMem)
            val appRamUsage = bytesToMb(appMemory.totalPrivateDirty * 1024L)

            Log.d("usage",
                "内存状态 - 设备总内存: ${totalMemory}MB | 当前可用内存: ${freeMemory}MB | 应用占用内存: ${appRamUsage}MB")
        }
    }

    /**
     * 添加电压更新监听器
     * @param listener 接收电压值的回调函数（参数单位为伏特）
     */
    fun addVoltageListener(listener: (Float) -> Unit) {
        if (!voltageListeners.contains(listener)) {
            voltageListeners.add(listener)
            Log.d("BatteryMonitor", "新增电压监听器，当前总数: ${voltageListeners.size}")
        }
    }

    /**
     * 移除电压监听器
     */
    fun removeVoltageListener(listener: (Float) -> Unit) {
        if (voltageListeners.remove(listener)) {
            Log.d("BatteryMonitor", "移除电压监听器，剩余数量: ${voltageListeners.size}")
        }
    }

    /**
     * 获取最后一次记录的电压值
     */
    fun getLatestVoltage(): Float = batteryVoltage
    fun unregisterBatteryReceiver() {
        if (batteryReceiverRegistered) {
            try {
                (context as? AppCompatActivity)?.unregisterReceiver(batteryReceiver)
                batteryReceiverRegistered = false
                Log.d("BatteryMonitor", "电池广播接收器已注销")
            } catch (e: IllegalArgumentException) {
                Log.w("BatteryMonitor", "接收器未注册", e)
            }
        }
    }

    private fun bytesToMb(bytes: Long): Long {
        return bytes / (1024 * 1024)
    }
}