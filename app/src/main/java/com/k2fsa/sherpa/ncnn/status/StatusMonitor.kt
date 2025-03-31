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
import com.k2fsa.sherpa.ncnn.control.EventBus
import java.io.BufferedReader
import java.io.FileReader
import kotlinx.coroutines.isActive
import java.io.File
import java.io.FileNotFoundException

class StatusMonitor(private val mainScope: CoroutineScope, private val context: Context, private val event: EventBus) {

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

    private val _cpuTempStats = MutableLiveData<CpuTempStats>()
    val cpuTempStats: LiveData<CpuTempStats> get() = _cpuTempStats
    private var tempMonitorJob: Job? = null

    private val temperatureLiveData = MutableLiveData<Float>()
    private var temperatureMonitorJob: Job? = null


    // 常见温度传感器路径（按优先级排序）
    private val thermalSensorPaths = listOf(
        "/sys/devices/virtual/thermal/thermal_zone%d/temp",
//        "/sys/class/thermal/thermal_zone%d/temp",
    )


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

    fun checkAndLogSensor(path: String) {
        val tag = "usage"

        // 记录原始路径检测行为
        Log.v(tag, "开始检测传感器路径: $path")

        val result = try {
            val typeFile = File(path.replace("temp", "type"))
            val typeContent = typeFile.readText()

            // 记录读取到的原始内容
            Log.d(tag, "读取传感器类型文件内容: $typeContent")

            typeContent.contains("cpu_thermal")
        } catch (e: FileNotFoundException) {
            Log.e(tag, "文件不存在: ${e.message}")
            false
        } catch (e: SecurityException) {
            Log.w(tag, "权限拒绝: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(tag, "检测异常: ${e.stackTraceToString()}")
            false
        }

        // 输出最终判断结果
        if (result) {
            Log.i(tag, "有效CPU温度传感器路径: $path")
        } else {
            Log.w(tag, "非CPU传感器路径: $path")
        }
    }

    private fun collectCpuTemperature() {
        mainScope.launch(Dispatchers.IO) {
            try {
                val coreTemps = detectCoreTemperatures()
                if (coreTemps.isNotEmpty()) {
                    val maxTemp = coreTemps.values.maxOrNull() ?: 0f
                    val avgTemp = coreTemps.values.average().toFloat()

                    _cpuTempStats.postValue(
                        CpuTempStats(
                            coreTemps = coreTemps,
                            maxTemp = maxTemp,
                            avgTemp = avgTemp
                        )
                    )
//                    checkAndLogSensor("/sys/class/thermal/thermal_zone%d/temp")
                    checkAndLogSensor("/sys/devices/virtual/thermal/thermal_zone%d/temp")

                    logTemperatureStats(coreTemps, maxTemp, avgTemp)

                }
            } catch (e: Exception) {
                Log.e("usage", "温度采集失败: ${e.message}")
            }
        }
    }

    private fun detectCoreTemperatures(): Map<Int, Float> {
        val temps = mutableMapOf<Int, Float>()

        // 尝试所有可能的传感器路径
        thermalSensorPaths.forEachIndexed { index, pathPattern ->
            var coreId = 0
            while (true) {
                val path = pathPattern.format(coreId)
                val temp = tryReadTemperature(path)
                if (temp != null) {
                    temps[index * 10 + coreId] = temp // 生成唯一核心ID
                    coreId++
                } else {
                    break
                }
            }
            if (temps.isNotEmpty()) return temps
        }

        return temps
    }

    private fun tryReadTemperature(path: String): Float? {
        return try {
            BufferedReader(FileReader(path)).use { reader ->
                val tempMilliC = reader.readLine().trim().toFloatOrNull()
                tempMilliC?.div(1000) // 转换为摄氏度
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun logTemperatureStats(
        coreTemps: Map<Int, Float>,
        maxTemp: Float,
        avgTemp: Float
    ) {
        val logMsg = buildString {
            append("=== CPU温度统计 ===\n")
            coreTemps.forEach { (core, temp) ->
                append("核心$core: ${"%.1f℃".format(temp)}\n")
            }
            append("最高温度: ${"%.1f℃".format(maxTemp)}\n")
            append("平均温度: ${"%.1f℃".format(avgTemp)}\n")
            append("采样时间: ${System.currentTimeMillis()}")
        }
//        Log.d("usage", logMsg)
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
            Log.d("usage", "电池广播接收器已注册")
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

            event.publish(EventBus.Event.USAGE, "能耗统计 - 当前电流: ${current}A | 当前电压: ${voltage}V | 当前功率: ${power}W")

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
            Log.d("usage", "新增电压监听器，当前总数: ${voltageListeners.size}")
        }
    }

    /**
     * 移除电压监听器
     */
    fun removeVoltageListener(listener: (Float) -> Unit) {
        if (voltageListeners.remove(listener)) {
            Log.d("usage", "移除电压监听器，剩余数量: ${voltageListeners.size}")
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
                Log.d("usage", "电池广播接收器已注销")
            } catch (e: IllegalArgumentException) {
                Log.w("usage", "接收器未注册", e)
            }
        }
    }

    private fun bytesToMb(bytes: Long): Long {
        return bytes / (1024 * 1024)
    }

    // 新增温度监控启动方法
    fun startTemperatureMonitoring() {
        stopTemperatureMonitoring() // 防止重复启动
        temperatureMonitorJob = mainScope.launch {
            while (isActive) {
                delay(5_000) // 每5秒采集一次
                collectTemperatureStats()
            }
        }
    }

    // 停止温度监控
    fun stopTemperatureMonitoring() {
        temperatureMonitorJob?.cancel()
        temperatureMonitorJob = null
    }

    // 获取温度LiveData
    fun getTemperatureStats(): LiveData<Float> = temperatureLiveData

    // 温度采集实现
    private fun collectTemperatureStats() {
        mainScope.launch(Dispatchers.IO) {
            try {
                // 读取温度节点文件
                val tempFile = File("/sys/devices/virtual/thermal/thermal_zone0/temp")
                if (tempFile.exists()) {
                    val temperature = tempFile.readText().trim().toIntOrNull()
                    temperature?.let {
                        // 转换为摄氏度（假设值单位为毫摄氏度）
                        val celsius = it / 1000f
                        temperatureLiveData.postValue(celsius)
                        Log.d("usage", "CPU实时温度: ${"%.1f".format(celsius)}°C")
                    }
                }
            } catch (e: Exception) {
                Log.e("usage", "温度读取失败", e)
            }
        }
    }
}