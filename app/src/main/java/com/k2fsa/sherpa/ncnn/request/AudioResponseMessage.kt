package com.k2fsa.sherpa.ncnn.request

import kotlinx.serialization.Serializable

@Serializable
data class AudioResponseMessage(val id: String, val msg_id: Int, val content: String, val process_time: Double)

