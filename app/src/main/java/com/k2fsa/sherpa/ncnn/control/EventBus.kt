package com.k2fsa.sherpa.ncnn.control
import com.k2fsa.sherpa.ncnn.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class EventBus {
    private val logger = Logger(this::class.java.simpleName)
    private val listeners = mutableMapOf<Event, MutableList<(Any) -> Unit>>()
    private val scope = CoroutineScope(Dispatchers.Main)

    enum class Event {
        SPEECH_RESULT,
        TRANSLATION_RESULT,
        GESTURE_DETECTED,
        GENDER_DETECTED,
        ERROR,
        TEXT_TO_SOUND
    }

    fun subscribe(event: Event, listener: (Any) -> Unit) {
        if (!listeners.containsKey(event)) {
            listeners[event] = mutableListOf()
        }
        listeners[event]?.add(listener)
    }

    fun unsubscribe(event: Event, listener: (Any) -> Unit) {
        listeners[event]?.remove(listener)
    }

    fun publish(event: Event, data: Any) {
        scope.launch {
            listeners[event]?.forEach { listener ->
                try {
                    listener(data)
                } catch (e: Exception) {
                    logger.error("Error in event listener for $event: ${e.message}")
                    publish(Event.ERROR, "Error processing $event: ${e.message}")
                }
            }
        }
    }

    fun clear() {
        listeners.clear()
    }
}