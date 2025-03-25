package com.k2fsa.sherpa.ncnn.request
import kotlinx.serialization.Serializable

@Serializable
data class RegisterMessage(val id: String, val type: String, val des: String)