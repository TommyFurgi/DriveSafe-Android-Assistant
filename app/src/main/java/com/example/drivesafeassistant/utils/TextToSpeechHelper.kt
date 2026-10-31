package com.example.drivesafeassistant.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

class TextToSpeechHelper(
    context: Context,
    private val onReady: ((englishAvailable: Boolean) -> Unit)? = null,
    private val onSpeakingChanged: ((Boolean) -> Unit)? = null
) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wakeLock = (appContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DriveSafe:NotificationSpeech")
        .apply { setReferenceCounted(false) }

    private var tts: TextToSpeech = TextToSpeech(appContext, this)
    private val pending = ArrayDeque<PendingUtterance>()
    private var audioFocusRequest: AudioFocusRequest? = null
    private var speaking = false

    @Volatile
    var isReady = false
        private set

    @Volatile
    var isEnglishAvailable = false
        private set

    private val speechAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val markIdle = Runnable {
        if (!isReady || tts.isSpeaking) return@Runnable
        if (!speaking) return@Runnable
        speaking = false
        releaseWakeLock()
        abandonAudioFocus()
        onSpeakingChanged?.invoke(false)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TextToSpeech initialization failed.")
            return
        }

        tts.setAudioAttributes(speechAttributes)
        tts.setOnUtteranceProgressListener(utteranceListener)
        isEnglishAvailable = applyEnglishVoice()
        isReady = true
        flushPending()
        onReady?.invoke(isEnglishAvailable)
    }

    fun speak(text: String, queue: Boolean = false) {
        if (text.isBlank()) return
        if (!isReady) {
            synchronized(pending) {
                pending.add(PendingUtterance(text, queue))
            }
            return
        }
        speakInternal(text, queue)
    }

    private fun applyEnglishVoice(): Boolean {
        val languageResult = tts.setLanguage(Locale.US)
        val languageOk = languageResult != TextToSpeech.LANG_MISSING_DATA &&
            languageResult != TextToSpeech.LANG_NOT_SUPPORTED
        if (!languageOk) {
            Log.e(TAG, "English TTS data is not installed. result=$languageResult")
            return false
        }
        if (selectOfflineEnglishVoice()) {
            Log.i(TAG, "Using offline English voice: ${tts.voice?.name} (${tts.voice?.locale})")
        } else {
            Log.i(TAG, "Using English locale ${Locale.US}")
        }
        return true
    }

    private fun selectOfflineEnglishVoice(): Boolean {
        val voices = try {
            tts.voices
        } catch (error: Exception) {
            Log.w(TAG, "Unable to list TTS voices", error)
            null
        } ?: return false

        val best = voices
            .filter { voice ->
                voice.locale.language.equals("en", ignoreCase = true) &&
                    !voice.isNetworkConnectionRequired &&
                    !voice.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }
            .sortedWith(
                compareByDescending<Voice> { it.locale.country.equals("US", ignoreCase = true) }
                    .thenByDescending { it.quality }
                    .thenBy { it.latency }
            )
            .firstOrNull()
            ?: return false

        return tts.setVoice(best) == TextToSpeech.SUCCESS
    }

    private fun flushPending() {
        val items = synchronized(pending) {
            val copy = pending.toList()
            pending.clear()
            copy
        }
        items.forEachIndexed { index, item ->
            speakInternal(item.text, queue = index > 0 || item.queue)
        }
    }

    private fun speakInternal(text: String, queue: Boolean) {
        val focus = requestAudioFocus()
        if (focus != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "Audio focus not granted ($focus); speaking anyway")
        }
        acquireWakeLock()
        val mode = if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        val utteranceId = "drive_safe_${System.nanoTime()}"
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        val uttered = tts.speak(text, mode, params, utteranceId)
        if (uttered == TextToSpeech.ERROR) {
            Log.e(TAG, "TTS speak() returned ERROR for: $text")
            mainHandler.post(markIdle)
        }
    }

    private fun requestAudioFocus(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(speechAttributes)
                .setOnAudioFocusChangeListener { focusChange ->
                    Log.d(TAG, "Audio focus change: $focusChange")
                }
                .build()
                .also { audioFocusRequest = it }
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) {
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    fun shutdown() {
        mainHandler.removeCallbacks(markIdle)
        pending.clear()
        isReady = false
        speaking = false
        releaseWakeLock()
        abandonAudioFocus()
        tts.stop()
        tts.shutdown()
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            mainHandler.post {
                mainHandler.removeCallbacks(markIdle)
                acquireWakeLock()
                if (!speaking) {
                    speaking = true
                    onSpeakingChanged?.invoke(true)
                }
            }
        }

        override fun onDone(utteranceId: String?) {
            mainHandler.postDelayed(markIdle, IDLE_DELAY_MS)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            mainHandler.postDelayed(markIdle, IDLE_DELAY_MS)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            Log.e(TAG, "Utterance error $errorCode for $utteranceId")
            mainHandler.postDelayed(markIdle, IDLE_DELAY_MS)
        }
    }

    private data class PendingUtterance(val text: String, val queue: Boolean)

    companion object {
        private const val TAG = "TTS"
        private const val IDLE_DELAY_MS = 400L
        private const val WAKE_LOCK_TIMEOUT_MS = 60_000L
    }
}
