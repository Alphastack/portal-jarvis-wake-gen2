package com.german.portaljarviswake

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.german.portaljarviswake.core.Backoff
import com.german.portaljarviswake.core.Debounce
import com.german.portaljarviswake.core.Phrase
import com.german.portaljarviswake.core.RecognizedWord
import com.german.portaljarviswake.core.Sensitivity
import com.german.portaljarviswake.core.WakeEffect
import com.german.portaljarviswake.core.WakeEvent
import com.german.portaljarviswake.core.WakeMatcher
import com.german.portaljarviswake.core.WakeState
import com.german.portaljarviswake.core.WakeStateMachine
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent Portal listener and microphone handoff coordinator.
 *
 * Audio capture is confined to [captureExecutor]. The main thread owns state transitions and
 * timers. Stopping an AudioRecord unblocks its read; Vosk resources are then closed by the capture
 * thread that was using them, preventing close/read races and stale loops from disabling a new one.
 */
class WakeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private val downloadExecutor = Executors.newSingleThreadExecutor()
    private val machine = WakeStateMachine()
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val debounce = Debounce(TRIGGER_DEBOUNCE_MS)

    private lateinit var audioManager: AudioManager
    private lateinit var overlay: LaunchOverlay
    private lateinit var recordingCallback: AudioManager.AudioRecordingCallback

    private var initialized = false
    private var destroyed = false
    private var downloading = false
    private var captureQueuedOrRunning = false
    private var pendingCaptureRestart = false
    private var retryAttempt = 0
    private var screenInteractive = true

    @Volatile private var captureStop = AtomicBoolean(true)
    @Volatile private var activeRecorder: AudioRecord? = null
    @Volatile private var ownSessionId = NO_SESSION

    private var partialWakeLock: PowerManager.WakeLock? = null

    private val acquireTimeout = Runnable {
        if (machine.state == WakeState.YIELDING && !hasForeignRecorder()) {
            log("Jarvis did not acquire the microphone before timeout")
            handle(WakeEvent.AcquireTimeout)
        }
    }
    private val safetyTimeout = Runnable {
        if (machine.state == WakeState.YIELDING || machine.state == WakeState.ASSISTANT_ACTIVE) {
            log("Handoff safety timeout; rearming listener")
            handle(WakeEvent.SafetyTimeout)
        }
    }
    private val assistantAbsent = Runnable {
        if (machine.state == WakeState.ASSISTANT_ACTIVE && !hasForeignRecorder()) {
            handle(WakeEvent.AssistantAbsentFiveSeconds)
        }
    }
    private val cooldownFinished = Runnable {
        if (machine.state == WakeState.COOLDOWN) handle(WakeEvent.CooldownFinished)
    }
    private val retryCapture = Runnable {
        if (machine.state == WakeState.MIC_UNAVAILABLE && shouldListenNow()) {
            handle(WakeEvent.Start)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenInteractive = false
                    if (!prefs.getBoolean(KEY_SCREEN_OFF, false)) handle(WakeEvent.ScreenOff)
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenInteractive = true
                    handle(WakeEvent.ScreenOn)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!initialized) initializeOnce()

        if (!prefs.getBoolean(KEY_ENABLED, false)) {
            handle(WakeEvent.Stop)
            publish(WakeState.IDLE, "Disabled")
            return START_STICKY
        }

        if (intent?.action == ACTION_RELOAD) {
            handle(WakeEvent.Stop)
        }

        ensureModelAndStart()
        return START_STICKY
    }

    private fun initializeOnce() {
        initialized = true
        audioManager = getSystemService(AudioManager::class.java)
        screenInteractive = getSystemService(PowerManager::class.java).isInteractive
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Starting"))

        overlay = LaunchOverlay(this)
        runCatching { overlay.show() }
            .onFailure { Log.w(TAG, "Portal launch overlay unavailable", it) }

        recordingCallback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                onRecordingConfigurationsChanged(configs)
            }
        }
        audioManager.registerAudioRecordingCallback(recordingCallback, main)
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
        )
    }

    private fun ensureModelAndStart() {
        if (ModelInstaller(filesDir).installed()) {
            if (shouldListenNow()) handle(WakeEvent.Start) else handle(WakeEvent.ScreenOff)
            return
        }
        if (downloading) return

        downloading = true
        publish(WakeState.MODEL_MISSING, "Downloading speech model")
        downloadExecutor.execute {
            try {
                ModelInstaller(filesDir).install { percent ->
                    main.post { publish(WakeState.MODEL_MISSING, "Downloading speech model: $percent%") }
                }
                main.post {
                    downloading = false
                    if (shouldListenNow()) handle(WakeEvent.Start) else handle(WakeEvent.ScreenOff)
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Speech model download failed", error)
                main.post {
                    downloading = false
                    publish(WakeState.MODEL_MISSING, "Model download failed: ${error.message ?: "unknown error"}")
                }
            }
        }
    }

    private fun handle(event: WakeEvent) {
        if (destroyed) return
        val effects = machine.on(event)
        effects.forEach(::applyEffect)
        if (effects.isNotEmpty()) publish(machine.state, statusText(machine.state))
    }

    private fun applyEffect(effect: WakeEffect) {
        when (effect) {
            WakeEffect.StartListening -> requestCaptureStart()
            WakeEffect.ReleaseMic -> stopCapture()
            WakeEffect.LaunchAssistant -> beginJarvisHandoff()
            WakeEffect.AssistantActive -> {
                main.removeCallbacks(acquireTimeout)
                main.removeCallbacks(assistantAbsent)
            }
            WakeEffect.Cooldown -> cancelHandoffTimers()
            WakeEffect.ScheduleAcquireTimeout -> main.postDelayed(acquireTimeout, ACQUIRE_TIMEOUT_MS)
            WakeEffect.ScheduleSafetyTimeout -> main.postDelayed(safetyTimeout, SAFETY_TIMEOUT_MS)
            WakeEffect.ScheduleCooldown -> main.postDelayed(cooldownFinished, COOLDOWN_MS)
            WakeEffect.ScheduleAbsentCheck -> Unit // Absence is scheduled only from a real empty callback.
            WakeEffect.Publish -> Unit
        }
    }

    private fun requestCaptureStart() {
        if (!shouldListenNow() || destroyed) return
        if (captureQueuedOrRunning) {
            pendingCaptureRestart = true
            return
        }

        captureQueuedOrRunning = true
        pendingCaptureRestart = false
        val stopFlag = AtomicBoolean(false)
        captureStop = stopFlag
        val phrase = Phrase.normalize(prefs.getString(KEY_PHRASE, DEFAULT_PHRASE) ?: DEFAULT_PHRASE)
        val sensitivity = runCatching {
            Sensitivity.valueOf(prefs.getString(KEY_SENSITIVITY, Sensitivity.BALANCED.name)!!)
        }.getOrDefault(Sensitivity.BALANCED)

        captureExecutor.execute {
            runCaptureSession(stopFlag, phrase, sensitivity)
            main.post {
                captureQueuedOrRunning = false
                activeRecorder = null
                ownSessionId = NO_SESSION
                releasePartialWakeLock()
                if (!destroyed && machine.state == WakeState.LISTENING &&
                    (pendingCaptureRestart || shouldListenNow())) {
                    pendingCaptureRestart = false
                    requestCaptureStart()
                }
            }
        }
    }

    private fun runCaptureSession(
        stopFlag: AtomicBoolean,
        phrase: String,
        sensitivity: Sensitivity,
    ) {
        var localModel: Model? = null
        var localRecognizer: Recognizer? = null
        var localRecorder: AudioRecord? = null
        try {
            val minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minimum > 0) { "16 kHz mono microphone is unavailable" }
            val bufferSize = minimum.coerceAtLeast(MIN_BUFFER_BYTES)

            localModel = Model(ModelInstaller(filesDir).model.absolutePath)
            val grammar = JSONObject.quote(phrase)
            localRecognizer = Recognizer(localModel, SAMPLE_RATE.toFloat(), "[$grammar, \"[unk]\"]")
            localRecognizer.setWords(true)

            localRecorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
            check(localRecorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }

            activeRecorder = localRecorder
            ownSessionId = localRecorder.audioSessionId
            localRecorder.startRecording()
            check(localRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Microphone did not enter recording state"
            }
            retryAttempt = 0
            main.post {
                acquirePartialWakeLockIfNeeded()
                publish(WakeState.LISTENING, "Listening for “$phrase”")
            }

            val matcher = WakeMatcher(phrase, sensitivity)
            val buffer = ByteArray(bufferSize)
            while (!stopFlag.get() && !Thread.currentThread().isInterrupted) {
                val count = localRecorder.read(buffer, 0, buffer.size)
                if (count <= 0) {
                    if (!stopFlag.get()) throw IllegalStateException("Microphone read failed: $count")
                    break
                }
                if (localRecognizer.acceptWaveForm(buffer, count)) {
                    val words = parseFinalWords(localRecognizer.result)
                    if (matcher.matches(words) && debounce.accept(SystemClock.elapsedRealtime())) {
                        main.post { handle(WakeEvent.Phrase) }
                    }
                }
            }
        } catch (error: Throwable) {
            if (!stopFlag.get() && !destroyed) {
                Log.e(TAG, "Listener failed", error)
                main.post {
                    if (machine.state == WakeState.LISTENING) {
                        handle(WakeEvent.MicFailure)
                        scheduleCaptureRetry(error.message)
                    }
                }
            }
        } finally {
            runCatching {
                if (localRecorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) localRecorder.stop()
            }
            runCatching { localRecorder?.release() }
            runCatching { localRecognizer?.close() }
            runCatching { localModel?.close() }
        }
    }

    private fun parseFinalWords(json: String): List<RecognizedWord> {
        val result = runCatching { JSONObject(json).optJSONArray("result") }.getOrNull()
            ?: return emptyList()
        return (0 until result.length()).mapNotNull { index ->
            val item = result.optJSONObject(index) ?: return@mapNotNull null
            val confidence = if (item.has("conf")) item.optDouble("conf").toFloat() else null
            RecognizedWord(item.optString("word"), confidence)
        }
    }

    private fun stopCapture() {
        pendingCaptureRestart = false
        captureStop.set(true)
        activeRecorder?.let { recorder ->
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
        activeRecorder = null
        ownSessionId = NO_SESSION
        releasePartialWakeLock()
    }

    private fun scheduleCaptureRetry(reason: String?) {
        main.removeCallbacks(retryCapture)
        val delay = Backoff.delay(retryAttempt++)
        publish(WakeState.MIC_UNAVAILABLE, "Microphone unavailable${reason?.let { ": $it" } ?: ""}; retrying")
        main.postDelayed(retryCapture, delay)
    }

    private fun beginJarvisHandoff() {
        wakeDisplayAfterConfirmedPhrase()
        val launched = launchJarvisActivity()
        log(if (launched) "Jarvis brought to foreground" else "Jarvis launch activity unavailable")
        // Android 10 must see Jarvis resumed before its wake receiver starts microphone capture.
        main.postDelayed({
            if (machine.state == WakeState.YIELDING || machine.state == WakeState.ASSISTANT_ACTIVE) {
                sendJarvisWakeBroadcast()
            }
        }, FOREGROUND_SETTLE_MS)
    }

    private fun launchJarvisActivity(): Boolean {
        val launch = packageManager.getLaunchIntentForPackage(JARVIS_PACKAGE) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        return runCatching { startActivity(launch) }
            .onFailure { Log.e(TAG, "Could not foreground Jarvis", it) }
            .isSuccess
    }

    private fun sendJarvisWakeBroadcast() {
        val intent = Intent(WAKE_ACTION)
            .setPackage(JARVIS_PACKAGE)
            .putExtra(WAKE_ID_EXTRA, WAKE_ID)
        sendBroadcast(intent)
        log("Wake handoff broadcast sent")
    }

    private fun onRecordingConfigurationsChanged(configs: List<AudioRecordingConfiguration>) {
        val foreign = configs.any { config ->
            val session = config.clientAudioSessionId
            session != NO_SESSION && session != ownSessionId
        }
        if (foreign) {
            main.removeCallbacks(assistantAbsent)
            handle(WakeEvent.AssistantRecorderSeen)
        } else if (machine.state == WakeState.ASSISTANT_ACTIVE) {
            main.removeCallbacks(assistantAbsent)
            main.postDelayed(assistantAbsent, ASSISTANT_ABSENT_DEBOUNCE_MS)
        }
    }

    private fun hasForeignRecorder(): Boolean = runCatching {
        audioManager.activeRecordingConfigurations.any { config ->
            val session = config.clientAudioSessionId
            session != NO_SESSION && session != ownSessionId
        }
    }.getOrDefault(false)

    private fun cancelHandoffTimers() {
        main.removeCallbacks(acquireTimeout)
        main.removeCallbacks(safetyTimeout)
        main.removeCallbacks(assistantAbsent)
    }

    private fun shouldListenNow(): Boolean =
        prefs.getBoolean(KEY_ENABLED, false) &&
            (screenInteractive || prefs.getBoolean(KEY_SCREEN_OFF, false))

    private fun acquirePartialWakeLockIfNeeded() {
        if (!prefs.getBoolean(KEY_SCREEN_OFF, false) || partialWakeLock?.isHeld == true) return
        partialWakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:listener")
            .apply { acquire() }
    }

    private fun releasePartialWakeLock() {
        partialWakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        partialWakeLock = null
    }

    @Suppress("DEPRECATION")
    private fun wakeDisplayAfterConfirmedPhrase() {
        if (screenInteractive) return
        val flags = PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.SCREEN_BRIGHT_WAKE_LOCK
        getSystemService(PowerManager::class.java)
            .newWakeLock(flags, "$packageName:confirmed-wake")
            .apply { acquire(DISPLAY_WAKE_LOCK_MS) }
    }

    private fun publish(state: WakeState, detail: String) {
        prefs.edit()
            .putString(KEY_STATUS, state.name)
            .putString(KEY_STATUS_DETAIL, detail)
            .apply()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(detail))
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
        log("status=$state detail=$detail")
    }

    private fun statusText(state: WakeState): String = when (state) {
        WakeState.IDLE -> "Disabled"
        WakeState.MODEL_MISSING -> "Speech model missing"
        WakeState.SCREEN_PAUSED -> "Paused while display is off"
        WakeState.LISTENING -> "Starting microphone listener"
        WakeState.YIELDING -> "Microphone released; foregrounding Jarvis"
        WakeState.ASSISTANT_ACTIVE -> "Jarvis active; microphone released"
        WakeState.COOLDOWN -> "Cooldown before listening resumes"
        WakeState.MIC_UNAVAILABLE -> "Microphone unavailable"
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Jarvis wake listener", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Portal Jarvis Wake")
        .setContentText(text)
        .setOngoing(true)
        .build()

    private fun log(message: String) = Log.i(TAG, message)

    override fun onDestroy() {
        destroyed = true
        cancelHandoffTimers()
        main.removeCallbacks(cooldownFinished)
        main.removeCallbacks(retryCapture)
        stopCapture()
        if (initialized) {
            runCatching { audioManager.unregisterAudioRecordingCallback(recordingCallback) }
            runCatching { unregisterReceiver(screenReceiver) }
            runCatching { overlay.hide() }
        }
        captureExecutor.shutdownNow()
        downloadExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STATUS = "com.german.portaljarviswake.STATUS"
        const val ACTION_RELOAD = "com.german.portaljarviswake.RELOAD"

        private const val TAG = "PortalJarvisWake"
        private const val PREFS = "settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SCREEN_OFF = "screenOff"
        private const val KEY_PHRASE = "phrase"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_STATUS = "status"
        private const val KEY_STATUS_DETAIL = "statusDetail"
        private const val DEFAULT_PHRASE = "hey jarvis"

        private const val CHANNEL_ID = "wake"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_RATE = 16_000
        private const val MIN_BUFFER_BYTES = 4_096
        private const val NO_SESSION = -1
        private const val TRIGGER_DEBOUNCE_MS = 3_000L
        private const val FOREGROUND_SETTLE_MS = 350L
        private const val ACQUIRE_TIMEOUT_MS = 10_000L
        private const val ASSISTANT_ABSENT_DEBOUNCE_MS = 5_000L
        private const val COOLDOWN_MS = 3_000L
        private const val SAFETY_TIMEOUT_MS = 120_000L
        private const val DISPLAY_WAKE_LOCK_MS = 2_000L

        private const val JARVIS_PACKAGE = "com.portal.assistant"
        private const val WAKE_ACTION = "com.portal.wake.action.WAKE"
        private const val WAKE_ID_EXTRA = "com.portal.wake.extra.ID"
        private const val WAKE_ID = "jarvis"

        /** Diagnostic button path. The running service uses the sequenced handoff above. */
        fun testHandoff(context: Context) {
            val launch = context.packageManager.getLaunchIntentForPackage(JARVIS_PACKAGE)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            if (launch != null) runCatching { context.startActivity(launch) }
            Handler(Looper.getMainLooper()).postDelayed({
                context.sendBroadcast(
                    Intent(WAKE_ACTION)
                        .setPackage(JARVIS_PACKAGE)
                        .putExtra(WAKE_ID_EXTRA, WAKE_ID),
                )
            }, FOREGROUND_SETTLE_MS)
        }
    }
}
