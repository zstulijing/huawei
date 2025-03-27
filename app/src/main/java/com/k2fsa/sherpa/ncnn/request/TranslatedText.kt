package com.k2fsa.sherpa.ncnn.request

class TranslatedText(var text: String, var id: Int, var language: String) {
    fun clear() {
        this.text = ""
        this.id = 0
        this.language = ""
    }
}