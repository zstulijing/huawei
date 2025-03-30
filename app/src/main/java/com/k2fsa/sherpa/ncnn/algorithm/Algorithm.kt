package com.k2fsa.sherpa.ncnn.algorithm

/**
 * 卸载算法输出
 * [0]: 语音转文本
 * [1]: 中英互译
 * [2]: 文本转语音
 * [3]: 图像识别男女
 * [4]: 图像识别手势
 */

class Algorithm {

    /**
     * 返回每个模块的固定延迟数据，包括本地延迟、边云延迟、上传延迟和下载延迟。
     */
    private fun getModuleDelays(): List<List<Double>> {
        val modules = listOf(
            "语音转文本",
            "中英互译",
            "文本转语音",
            "图像识别男女",
            "图像识别开始、结束手势"
        )

        // 固定延迟数据（单位：秒）
        val localDelays = listOf(0.15, 0.22, 0.18, 0.29, 0.25) // 本地处理延迟
        val cloudDelays = listOf(1.0, 0.12, 0.09, 0.14, 0.11) // 边云处理延迟
        val uploadDelays = listOf(0.02, 0.01, 0.03, 0.05, 0.04) // 上传延迟
        val downloadDelays = listOf(0.01, 0.01, 0.02, 0.03, 0.02) // 下载延迟

        // 打印延迟数据表
        println("模块延迟数据表：")
        println("%-20s %-10s %-10s %-10s %-10s".format("模块", "本地延迟", "边云延迟", "上传延迟", "下载延迟"))
        println("-".repeat(60))

        for (i in modules.indices) {
            println("%-20s %-10.2f %-10.2f %-10.2f %-10.2f".format(
                modules[i], localDelays[i], cloudDelays[i], uploadDelays[i], downloadDelays[i]
            ))
        }

        return listOf(localDelays, cloudDelays, uploadDelays, downloadDelays)
    }

    /**
     * 根据电量和延迟决定每个模块的运行位置。
     * @param {Int} - battery_level 当前电量（百分比）
     * @param {List<Float>} - local_delays 列表，每个模块在本地运行的处理延迟
     * @param {List<Float>} - cloud_delays: 列表，每个模块在边云运行的处理延迟
     * @param {List<Float>} - upload_delays: 列表，每个模块的上传延迟
     * @param {List<Float>} - download_delays: 列表，每个模块的下载延迟
     * @return {List<Int>} - deployment: 列表，1 表示本地运行，0 表示边云运行
     */
    private fun decideDeployment(
        batteryLevel: Int,
        localDelays: List<Double>,
        cloudDelays: List<Double>,
        uploadDelays: List<Double>,
        downloadDelays: List<Double>
    ): List<Int> {
        val numModules = localDelays.size
        val deployment = mutableListOf<Int>()

        if (batteryLevel > 30) {
            // 电量 > 30%，选择延迟最低的部署位置
            for (i in 0 until numModules) {
                val localTotalDelay = localDelays[i] // 本地运行总延迟
                val cloudTotalDelay = uploadDelays[i] + cloudDelays[i] + downloadDelays[i] // 边云运行总延迟

                // 选择延迟较低的
                if (localTotalDelay < cloudTotalDelay) {
                    deployment.add(1) // 本地运行
                } else {
                    deployment.add(0) // 边云运行
                }
            }
        } else {
            // 电量 ≤ 30%，所有模块在边云运行
            deployment.addAll(List(numModules) { 0 })
        }
        return deployment
    }

    fun outputVector(): IntArray {
        // 获取固定延迟数据
        val (localDelays, cloudDelays, uploadDelays, downloadDelays) = getModuleDelays()

        // 测试不同电量场景：40% 和 20%
//        val batteryLevel = 20
        val batteryLevel = 40
        println("\n当前电量: ${batteryLevel}%")
        val deployment = decideDeployment(batteryLevel, localDelays, cloudDelays, uploadDelays, downloadDelays).toMutableList()

        // 语音转文字一直在本地
        deployment[0] = 1

        // 输出结果
        val modules = listOf("语音转文本", "中英互译", "文本转语音", "图像识别男女", "图像识别开始、结束手势")
        println("部署决策：")
        for (i in modules.indices) {
            val location = if (deployment[i] == 1) "本地" else "边云"
            println("${modules[i]}: $location (1=本地, 0=边云: ${deployment[i]})")
        }
        return deployment.toIntArray()
    }

    fun setPathOne(): IntArray {
        return intArrayOf(0, 0, 1, 0, 0)
    }
    fun setPathTwo(): IntArray {
        return intArrayOf(0, 1, 1, 0, 0)
    }
    fun setPathThree(): IntArray {
        return intArrayOf(1, 1, 1, 0, 0)
    }
    fun setPathFour(): IntArray {
        return intArrayOf(0, 0, 0, 0, 0)
    }
    fun setPathFive(): IntArray {
        return intArrayOf(1, 1, 1, 0, 0)
    }
}