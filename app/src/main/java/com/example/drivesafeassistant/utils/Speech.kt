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
import android.util.Log
import java.util.Locale

/** English TTS shared by the screen and the background notification reader. */
object Speech : TextToSpeech.OnInitListener {
    private const val PREFS = "drive_safe_prefs"
    private const val KEY_DRIVE_MODE = "drive_mode_active"
    private const val TAG = "Speech"

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val handler = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<Pair<String, Boolean>>()
    private val goIdle = Runnable { finishSpeaking() }

    private lateinit var audio: AudioManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private var tts: TextToSpeech? = null
    private var focus: AudioFocusRequest? = null
    private var readyCallback: (() -> Unit)? = null
    private var speaking = false

    @Volatile var isReady = false
        private set

    @Volatile var isEnglishAvailable = false
        private set

    var onSpeaking: ((Boolean) -> Unit)? = null

    fun isDriveModeOn(context: Context) = prefs(context).getBoolean(KEY_DRIVE_MODE, false)

    fun setDriveMode(context: Context, active: Boolean) {
        prefs(context).edit().putBoolean(KEY_DRIVE_MODE, active).apply()
    }

    fun ensure(context: Context, onReady: (() -> Unit)? = null) {
        if (onReady != null) {
            if (isReady) onReady() else readyCallback = onReady
        }
        if (tts != null) return
        synchronized(this) {
            if (tts != null) return
            val app = context.applicationContext
            audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            wakeLock = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DriveSafe:NotificationSpeech")
                .apply { setReferenceCounted(false) }
            tts = TextToSpeech(app, this)
        }
    }

    override fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TextToSpeech initialization failed.")
            return
        }
        engine.setAudioAttributes(attrs)
        engine.setOnUtteranceProgressListener(progress)
        isEnglishAvailable = useEnglish(engine)
        isReady = true
        val queued = synchronized(pending) { pending.toList().also { pending.clear() } }
        queued.forEachIndexed { index, (text, queue) -> say(engine, text, index > 0 || queue) }
        readyCallback?.invoke()
        readyCallback = null
    }

    fun speak(text: String, queue: Boolean = false) {
        if (text.isBlank()) return
        val engine = tts
        if (!isReady || engine == null) {
            synchronized(pending) { pending.add(text to queue) }
            return
        }
        say(engine, text, queue)
    }

    private fun useEnglish(engine: TextToSpeech): Boolean {
        val result = engine.setLanguage(Locale.US)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(TAG, "English TTS data is not installed.")
            return false
        }
        val voice = runCatching { engine.voices }.getOrNull().orEmpty().firstOrNull {
            it.locale.language.equals("en", true) &&
                it.locale.country.equals("US", true) &&
                !it.isNetworkConnectionRequired &&
                !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }
        if (voice != null) engine.voice = voice
        return true
    }

    private fun say(engine: TextToSpeech, text: String, queue: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { }
                .build()
                .also { focus = it }
            audio.requestAudioFocus(request)
        }
        if (!wakeLock.isHeld) wakeLock.acquire(60_000L)
        val id = "drive_safe_${System.nanoTime()}"
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        val mode = if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        if (engine.speak(text, mode, params, id) == TextToSpeech.ERROR) {
            Log.e(TAG, "TTS speak() returned ERROR")
            handler.post(goIdle)
        }
    }

    private fun finishSpeaking() {
        val engine = tts
        if (!isReady || engine == null || engine.isSpeaking || !speaking) return
        speaking = false
        if (wakeLock.isHeld) wakeLock.release()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focus?.let { audio.abandonAudioFocusRequest(it) }
        }
        onSpeaking?.invoke(false)
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            handler.post {
                handler.removeCallbacks(goIdle)
                if (!speaking) {
                    speaking = true
                    onSpeaking?.invoke(true)
                }
            }
        }

        override fun onDone(utteranceId: String?) {
            handler.postDelayed(goIdle, 400)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            handler.postDelayed(goIdle, 400)
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
