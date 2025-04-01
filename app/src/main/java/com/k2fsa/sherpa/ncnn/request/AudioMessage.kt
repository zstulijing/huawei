package com.k2fsa.sherpa.ncnn.request

import kotlinx.serialization.Serializable

@Serializable
data class AudioMessage(
    val id: String,
    var msg_id: Int,
    val samples: FloatArray,
    val sample_rate: Int
)
