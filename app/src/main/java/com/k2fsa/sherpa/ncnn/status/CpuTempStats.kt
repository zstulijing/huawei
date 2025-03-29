package com.k2fsa.sherpa.ncnn.status

class CpuTempStats (
    val coreTemps: Map<Int, Float>, // 核心编号 → 温度(℃)
    val maxTemp: Float,             // 最高核心温度
    val avgTemp: Float              // 平均温度
)