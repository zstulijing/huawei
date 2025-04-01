package com.k2fsa.sherpa.ncnn.request

private fun getAveragePingTime(pingOutput: String): Double {
    try {
        // 分割输出为行
        val lines = pingOutput.lines()

        // 查找包含"time="的行并提取时间值
        val timeValues = lines
            .filter { it.contains("time=") } // 筛选包含"time="的行
            .mapNotNull {
                // 使用正则表达式提取时间值
                val match = Regex("time=(\\d+\\.\\d+) ms").find(it)
                match?.groupValues?.get(1)?.toDouble()
            }

        // 如果没有找到时间值，返回null
        if (timeValues.isEmpty()) return 0.0

        // 计算平均值
        return timeValues.average()
    } catch (e: Exception) {
        e.printStackTrace()
        return 0.0
    }
}

private fun getPingRTT(host: String): String {
    return try {
        val process = Runtime.getRuntime().exec("/system/bin/ping -c 4 $host")
        val reader = process.inputStream.bufferedReader()
        val output = reader.readText()
        reader.close()
        output
    } catch (e: Exception) {
        e.printStackTrace()
        "Ping failed"
    }
}

fun getAvgRTT(host: String): Double {
    return getAveragePingTime(getPingRTT(host))
}
