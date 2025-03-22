//package com.k2fsa.sherpa.ncnn.translation
//
//import android.util.Log
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.withContext
//import java.util.concurrent.ConcurrentHashMap
//
//private const val TAG = "TranslationManager"
//
//class TranslationManager() {
//
//    private val translator = LlmTranslator()
//    private val translationCache = ConcurrentHashMap<String, String>()
//    private val coroutineScope = CoroutineScope(Dispatchers.Default)
//
//    suspend fun translate(text: String, targetLanguage: String = "English"): String {
//        val cacheKey = "$text:$targetLanguage"
//        // 如果缓存中已有结果，则直接返回
//        translationCache[cacheKey]?.let {
//            Log.d(TAG, "Using cached translation")
//            return it
//        }
//
//        return withContext(Dispatchers.Default) {
//            try {
//                val translatedText = translator.translate(text, null, targetLanguage)
//                // 缓存翻译结果
//                translationCache[cacheKey] = translatedText
//                translatedText
//            } catch (e: Exception) {
//                Log.e(TAG, "Error translating text: ${e.message}")
//                "Translation error: ${e.message}"
//            }
//        }
//    }
//
//
//    fun clearCache() {
//        translationCache.clear()
//    }
//}