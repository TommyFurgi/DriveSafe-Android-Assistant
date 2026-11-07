package com.example.drivesafeassistant.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/** English TTS shared by the UI and Drive Mode. */
object Speech : TextToSpeech.OnInitListener {
    private const val PREFS = "drive_safe_prefs"
    private const val KEY_DRIVE_MODE = "drive_mode_active"
    private const val KEY_DRIVE_MODE_SINCE = "drive_mode_since"
    private const val TAG = "Speech"

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val handler = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<Pair<String, Boolean>>()

    private lateinit var appContext: Context
    private lateinit var audio: AudioManager
    private var tts: TextToSpeech? = null
    private var focus: AudioFocusRequest? = null
    private var readyCallback: (() -> Unit)? = null

    @Volatile var isReady = false
        private set

    @Volatile var isEnglishAvailable = false
        private set

    fun isDriveModeOn(context: Context) =
        prefs(context).getBoolean(KEY_DRIVE_MODE, false)

    fun driveModeSince(context: Context) =
        prefs(context).getLong(KEY_DRIVE_MODE_SINCE, 0L)

    fun setDriveMode(context: Context, active: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_DRIVE_MODE, active)
            .putLong(KEY_DRIVE_MODE_SINCE, if (active) System.currentTimeMillis() else 0L)
            .apply()
        if (!active) stop()
    }

    fun ensure(context: Context, onReady: (() -> Unit)? = null) {
        if (onReady != null) {
            if (isReady) onReady() else readyCallback = onReady
        }
        if (tts != null) return
        synchronized(this) {
            if (tts != null) return
            appContext = context.applicationContext
            audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            isReady = false
            tts = TextToSpeech(appContext, this)
        }
    }

    fun speak(text: String, queue: Boolean = false) {
        if (text.isBlank()) return
        val engine = tts
        if (!isReady || engine == null) {
            synchronized(pending) { pending.add(text to queue) }
            if (::appContext.isInitialized) ensure(appContext)
            return
        }
        say(engine, text, queue)
    }

    fun stop() {
        synchronized(pending) { pending.clear() }
        runCatching { tts?.stop() }
        abandonFocus()
    }

    override fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TextToSpeech initialization failed")
            return
        }
        engine.setAudioAttributes(attrs)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                handler.post { abandonFocus() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handler.post { abandonFocus() }
            }
        })
        isEnglishAvailable = useEnglish(engine)
        isReady = true
        val queued = synchronized(pending) { pending.toList().also { pending.clear() } }
        queued.forEachIndexed { index, (text, queue) ->
            say(engine, text, queue = index > 0 || queue)
        }
        readyCallback?.invoke()
        readyCallback = null
    }

    private fun useEnglish(engine: TextToSpeech): Boolean {
        val result = engine.setLanguage(Locale.US)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(TAG, "English TTS data is not installed")
            return false
        }
        return true
    }

    private fun say(engine: TextToSpeech, text: String, queue: Boolean) {
        requestFocus()
        val mode = if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        val result = engine.speak(text, mode, params, "drive_safe_${System.nanoTime()}")
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "TTS speak() returned ERROR for: $text")
        }
    }

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { }
                .build()
                .also { focus = it }
            audio.requestAudioFocus(request)
        }
    }

    private fun abandonFocus() {
        if (::audio.isInitialized && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focus?.let { audio.abandonAudioFocusRequest(it) }
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
