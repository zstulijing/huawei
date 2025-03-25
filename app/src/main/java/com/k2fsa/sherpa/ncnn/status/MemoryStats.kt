package com.k2fsa.sherpa.ncnn.status

data class MemoryStats(
    val totalMemory: Long,      // 总内存（MB）
    val freeMemory: Long,       // 可用内存（MB）
    val appRamUsage: Long,      // 应用RAM使用量（MB）
)