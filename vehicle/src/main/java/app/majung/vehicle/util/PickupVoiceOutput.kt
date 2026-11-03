package app.majung.vehicle

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Locale

internal interface PickupVoiceOutput {
    val ready: Boolean
    val message: String
    fun speak(text: String)
    fun stop()
    fun close()
}

/** Pleos on-device synthesis. No cloud credentials, delayed focus, or replay after recovery. */
internal class PleosPickupVoiceOutput(context: Context, private val changed: () -> Unit) : PickupVoiceOutput {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener({ change -> if (change < 0) {
            stop()
            message = "통화 등 다른 소리를 우선해 음성 안내를 멈췄어요"
            changed()
        } }, handler).build()
    private var engine: TextToSpeech? = null
    private var closed = false
    private var speaking = false
    private var stopping = false
    private var pending: Pair<String, Long>? = null
    override var ready = false
        private set
    override var message = "음성 안내 준비 중"
        private set
    private val idle = "음성 안내 켜짐 · 준비 완료·도착 시 안내"
    private val initializationTimeout = Runnable { if (!ready) fail() }
    private val playbackTimeout = Runnable { fail() }
    private val stopTimeout = Runnable { fail() }
    private val listener = object : EventListener {
        override fun onStart() { handler.post {
            if (!closed && ready && speaking && !stopping) { message = "음성 안내 중"; changed() }
        } }
        override fun onReady() { }
        override fun onDone() { handler.post { finish() } }
        override fun onStop() { handler.post { finish() } }
        override fun onError(errorType: ErrorType) { handler.post { fail() } }
        override fun onUpdatedRms(rms: Double) { }
    }

    init {
        try {
            engine = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
            handler.postDelayed(initializationTimeout, 10000)
            engine!!.initialize(object : OnInitializeListener {
                override fun onSuccess() { handler.post {
                    if (closed || engine == null) return@post
                    try {
                        engine!!.addEventListener(listener)
                        engine!!.setLocale(Locale.KOREA)
                        engine!!.setAudioAttributes(attributes)
                        ready = true
                        message = idle
                        handler.removeCallbacks(initializationTimeout)
                        changed()
                    } catch (_: RuntimeException) { fail() }
                } }
                override fun onFailure(errorType: ErrorType) { handler.post { fail() } }
            })
        } catch (_: RuntimeException) { fail() }
    }

    override fun speak(text: String) {
        if (!ready || closed) return
        // The SDK has no utterance IDs. Serialize playback and discard notices older than five seconds.
        if (speaking || stopping) { pending = text to (SystemClock.elapsedRealtime() + 5000); return }
        try {
            if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                message = "지금은 음성을 재생할 수 없어요. 화면에서 상태를 확인해주세요"
                changed()
                return
            }
            speaking = true
            handler.postDelayed(playbackTimeout, 10000)
            engine!!.speak(text)
        } catch (_: RuntimeException) { fail() }
    }

    private fun finish() {
        if (closed || !ready || !speaking && !stopping) return
        speaking = false
        stopping = false
        handler.removeCallbacks(playbackTimeout)
        handler.removeCallbacks(stopTimeout)
        runCatching { audio.abandonAudioFocusRequest(focus) }
        message = idle
        changed()
        val next = pending
        pending = null
        if (next != null && SystemClock.elapsedRealtime() < next.second) speak(next.first)
    }

    override fun stop() {
        pending = null
        handler.removeCallbacks(playbackTimeout)
        if (speaking && !stopping) {
            speaking = false
            stopping = true
            handler.postDelayed(stopTimeout, 2000)
            try { engine?.stop() } catch (_: RuntimeException) { fail(); return }
        }
        runCatching { audio.abandonAudioFocusRequest(focus) }
    }

    private fun fail() {
        if (closed || engine == null && !ready && message != "음성 안내 준비 중") return
        ready = false
        release()
        message = "음성 안내를 사용할 수 없어요. 화면에서 상태를 확인해주세요"
        changed()
    }

    private fun release() {
        handler.removeCallbacks(initializationTimeout)
        handler.removeCallbacks(playbackTimeout)
        handler.removeCallbacks(stopTimeout)
        pending = null
        speaking = false
        stopping = false
        runCatching { audio.abandonAudioFocusRequest(focus) }
        val previous = engine
        engine = null
        runCatching { previous?.removeEventListener(listener) }
        runCatching { previous?.stop() }
        runCatching { previous?.release() }
    }

    override fun close() {
        closed = true
        ready = false
        release()
    }
}
