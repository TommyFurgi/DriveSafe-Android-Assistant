package com.example.drivesafeassistant.utils

import android.content.Context
import android.util.Log

/**
 * Process-wide TTS used by Drive Mode (foreground service + notification listener).
 * Keeps a single engine so speech works while the UI is in the background.
 */
object SpeechManager {
    private const val TAG = "SpeechManager"

    @Volatile
    private var helper: TextToSpeechHelper? = null

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    var isEnglishAvailable: Boolean = false
        private set

    private var onReadyListener: (() -> Unit)? = null

    @Volatile
    private var speakingListener: ((Boolean) -> Unit)? = null

    fun setSpeakingListener(listener: ((Boolean) -> Unit)?) {
        speakingListener = listener
    }

    fun ensureInitialized(context: Context, onReady: (() -> Unit)? = null) {
        if (onReady != null) {
            if (isReady) {
                onReady()
            } else {
                onReadyListener = onReady
            }
        }
        if (helper != null) return
        synchronized(this) {
            if (helper != null) return
            Log.d(TAG, "Initializing shared TTS")
            helper = TextToSpeechHelper(
                context.applicationContext,
                onReady = { englishAvailable ->
                    isEnglishAvailable = englishAvailable
                    isReady = true
                    Log.d(TAG, "Shared TTS ready, english=$englishAvailable")
                    onReadyListener?.invoke()
                    onReadyListener = null
                },
                onSpeakingChanged = { speaking ->
                    speakingListener?.invoke(speaking)
                }
            )
        }
    }

    fun speak(text: String, queue: Boolean = false) {
        val tts = helper
        if (tts == null) {
            Log.w(TAG, "speak() called before init: $text")
            return
        }
        tts.speak(text, queue)
    }

    fun shutdown() {
        synchronized(this) {
            helper?.shutdown()
            helper = null
            isReady = false
            isEnglishAvailable = false
            onReadyListener = null
            speakingListener = null
            Log.d(TAG, "Shared TTS shut down")
        }
    }
}
