package com.german.portaljarviswake

import android.app.*
import android.content.*
import android.media.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.german.portaljarviswake.core.*
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.Executors

class WakeService : Service() {
    private val main = Handler(Looper.getMainLooper()); private val io = Executors.newSingleThreadExecutor()
    private val machine = WakeStateMachine(); private var generation = 0L; private var initialized = false; private var reading = false; private var retries = 0
    private var recorder: AudioRecord? = null; private var recognizer: Recognizer? = null; private var model: Model? = null; private var partialLock: PowerManager.WakeLock? = null
    private lateinit var audio: AudioManager; private lateinit var overlay: LaunchOverlay; private lateinit var callback: AudioManager.AudioRecordingCallback
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { if (!initialized) initialize(); if (!prefs.getBoolean("enabled", false)) { event(WakeEvent.Stop); return START_STICKY }; if (!ModelInstaller(filesDir).installed()) download(); else event(WakeEvent.Start); return START_STICKY }
    private fun initialize() { initialized = true; createChannel(); startForeground(1, notification()); audio = getSystemService(AudioManager::class.java); overlay = LaunchOverlay(this); overlay.show(); callback = object : AudioManager.AudioRecordingCallback() { override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) { val assistant = configs.any { it.clientUid != applicationInfo.uid && it.clientAudioSessionId != ownSessionId }; if (assistant) event(WakeEvent.AssistantRecorderSeen) else if (machine.state == WakeState.ASSISTANT_ACTIVE) schedule(WakeEvent.AssistantAbsentFiveSeconds, 5_000) } }; audio.registerAudioRecordingCallback(mainExecutor, callback); registerReceiver(screenReceiver, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }) }
    private val screenReceiver = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { if (i.action == Intent.ACTION_SCREEN_OFF && !prefs.getBoolean("screenOff", false)) event(WakeEvent.ScreenOff); if (i.action == Intent.ACTION_SCREEN_ON) event(WakeEvent.ScreenOn) } }
    private var ownSessionId = AudioManager.AUDIO_SESSION_ID_GENERATE
    private fun event(event: WakeEvent) { if (event != WakeEvent.AssistantAbsentFiveSeconds) generation++; val effects = machine.on(event); effects.forEach { effect(it) }; publish() }
    private fun effect(effect: WakeEffect) = when (effect) { WakeEffect.StartListening -> listen(); WakeEffect.ReleaseMic -> releaseMic(); WakeEffect.LaunchAssistant -> { scheduleLaunch(); schedule(WakeEvent.AcquireTimeout, 3_000); schedule(WakeEvent.SafetyTimeout, 120_000) }; WakeEffect.ScheduleCooldown -> schedule(WakeEvent.CooldownFinished, 3_000); WakeEffect.ScheduleAbsentCheck -> schedule(WakeEvent.AssistantAbsentFiveSeconds, 5_000); else -> Unit }
    private fun schedule(event: WakeEvent, delay: Long) { val token = generation; main.postDelayed({ if (token == generation) this.event(event) }, delay) }
    private fun download() { publish("Model downloading"); io.execute { try { ModelInstaller(filesDir).install { publish("Model downloading $it%") }; main.post { event(WakeEvent.Start) } } catch (t: Throwable) { Log.e(TAG, "model", t); publish("Model failed; Retry") } } }
    private fun listen() { if (reading || !prefs.getBoolean("enabled", false)) return; try { val phrase = prefs.getString("phrase", "hey jarvis")!!; val size = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT); if (size <= 0) throw IllegalStateException("unsupported microphone format"); model = Model(ModelInstaller(filesDir).model.path); recognizer = Recognizer(model, 16000f, "[\"${Phrase.normalize(phrase)}\", \"[unk]\"]").also { it.setWords(true) }; recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size.coerceAtLeast(4096)); if (recorder!!.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("AudioRecord not initialized"); ownSessionId = recorder!!.audioSessionId; recorder!!.startRecording(); retries = 0; if (prefs.getBoolean("screenOff", false)) partialLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:listen").apply { acquire() }; reading = true; io.execute { readLoop(size.coerceAtLeast(4096), WakeMatcher(phrase, Sensitivity.valueOf(prefs.getString("sensitivity", "BALANCED")!!))) } } catch (t: Throwable) { Log.e(TAG, "mic", t); releaseMic(); event(WakeEvent.MicFailure); main.postDelayed({ event(WakeEvent.Start) }, Backoff.delay(retries++)) } }
    private fun readLoop(size: Int, matcher: WakeMatcher) { val buffer = ByteArray(size); while (reading) { val n = recorder?.read(buffer, 0, buffer.size) ?: break; if (n > 0 && recognizer?.acceptWaveForm(buffer, n) == true) { val words = parseWords(recognizer!!.result); if (matcher.matches(words) && DebounceHolder.accept()) main.post { event(WakeEvent.Phrase) } } }; reading = false }
    private fun parseWords(json: String): List<RecognizedWord> { val array = JSONObject(json).optJSONArray("result") ?: return emptyList(); return (0 until array.length()).map { i -> array.getJSONObject(i).let { RecognizedWord(it.optString("word"), if (it.has("conf")) it.optDouble("conf").toFloat() else null) } }
    private fun releaseMic() { generation++; reading = false; recorder?.runCatching { stop(); release() }; recorder = null; recognizer?.close(); recognizer = null; model?.close(); model = null; partialLock?.runCatching { release() }; partialLock = null }
    private fun scheduleLaunch() { val screenOff = !(getSystemService(PowerManager::class.java).isInteractive); if (screenOff) getSystemService(PowerManager::class.java).newWakeLock(PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "$packageName:wake").apply { acquire(2_000) }; main.postDelayed({ handoff(this) }, 350) }
    private fun publish(detail: String = machine.state.name) { prefs.edit().putString("status", machine.state.name).putString("statusDetail", detail).apply(); sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName)) }
    private fun createChannel() { getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("wake", "Jarvis wake listener", NotificationManager.IMPORTANCE_LOW)) }
    private fun notification() = NotificationCompat.Builder(this,"wake").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Jarvis wake listener").setOngoing(true).build()
    override fun onDestroy() { generation++; releaseMic(); if (initialized) { audio.unregisterAudioRecordingCallback(callback); unregisterReceiver(screenReceiver) }; overlay.hide(); io.shutdownNow(); super.onDestroy() }
    companion object { const val ACTION_STATUS = "com.german.portaljarviswake.STATUS"; private const val TAG = "PortalJarvisWake"; fun handoff(context: Context) { val packageName = "com.portal.assistant"; context.packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }; context.sendBroadcast(Intent("com.portal.wake.action.WAKE").setPackage(packageName).putExtra("com.portal.wake.extra.ID", "jarvis")) } }
}
private object DebounceHolder { private val debounce = Debounce(3000); fun accept() = debounce.accept(SystemClock.elapsedRealtime()) }
