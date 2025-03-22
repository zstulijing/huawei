package com.k2fsa.sherpa.ncnn.video

data class Frame(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Frame
        return data.contentEquals(other.data) &&
                width == other.width &&
                height == other.height &&
                timestamp == other.timestamp
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + timestamp.hashCode()
        return result
    }
}