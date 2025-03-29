package com.k2fsa.sherpa.ncnn.translation
import com.hankcs.hanlp.HanLP

fun isChinese(text: String): Boolean {
    return text.any { it.toString().matches(Regex("[\\u4e00-\\u9fa5]")) }
}

fun isEnglish(text: String): Boolean {
    return text.all { it.isLetter() && it in 'A'..'Z' || it in 'a'..'z' }
}

fun tokenizeEnglish(text: String): List<String> {
    return text.split("\\s+".toRegex()).filter { it.isNotBlank() }
}
fun tokenizeChinese(text: String): List<String> {
    return HanLP.segment(text).map { it.word }
}
