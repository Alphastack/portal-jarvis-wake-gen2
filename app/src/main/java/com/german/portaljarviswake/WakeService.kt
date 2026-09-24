package com.german.portaljarviswake

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.german.portaljarviswake.core.*
import org.json.JSONObject
import org.vosk.*
import java.io.File
import java.util.concurrent.Executors

class WakeService: Service() {
    private val tag="PortalJarvisWake"; private val handler=Handler(Looper.getMainLooper()); private val worker=Executors.newSingleThreadExecutor()
    private var recorder:AudioRecord?=null; private var recognizer:Recognizer?=null; private var state=HandoffState.COOLDOWN; private var absentAt=0L; private var retries=0; private var wakeLock:PowerManager.WakeLock?=null
    private lateinit var audio:AudioManager; private lateinit var overlay:LaunchOverlay
    override fun onBind(i:Intent?)=null
    override fun onStartCommand(i:Intent?,f:Int,id:Int):Int { createNotification(); startForeground(1, notification()); audio=getSystemService(AudioManager::class.java); overlay=LaunchOverlay(this); overlay.show(); if(i?.getBooleanExtra("download",false)==true || !ModelInstaller(filesDir).installed()) worker.execute { downloadThenListen() } else listen(); registerRecordingWatch(); return START_STICKY }
    private fun createNotification(){ (getSystemService(NotificationManager::class.java)).createNotificationChannel(NotificationChannel("wake","Jarvis wake listener",NotificationManager.IMPORTANCE_LOW)) }
    private fun notification()=NotificationCompat.Builder(this,"wake").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Jarvis wake listening").setContentText("On-device speech recognition").setOngoing(true).build()
    private fun downloadThenListen(){ try { ModelInstaller(filesDir).install { Log.i(tag,"model download $it%") }; handler.post { listen() } } catch(t:Throwable){ Log.e(tag,"model download failed",t) } }
    private fun listen(){ if(!getSharedPreferences("settings",0).getBoolean("enabled",false)) return; if(recorder!=null)return; try { val phrase=getSharedPreferences("settings",0).getString("phrase","hey jarvis")!!; val model=Model(ModelInstaller(filesDir).model.absolutePath); recognizer=Recognizer(model,16000f,"[\\\"$phrase\\\"]"); val size=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096); recorder=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size); recorder!!.startRecording(); state=HandoffState.LISTENING; if(getSharedPreferences("settings",0).getBoolean("screenOff",false)) wakeLock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"PortalJarvisWake:listen").apply{acquire()}; worker.execute { readLoop(size, WakeMatcher(phrase)) }; Log.i(tag,"LISTENING") } catch(t:Throwable) { Log.e(tag,"audio/model start failed",t); scheduleRetry() } }
    private fun readLoop(size:Int, matcher:WakeMatcher){ val buf=ByteArray(size); while(state==HandoffState.LISTENING){ val n=recorder?.read(buf,0,buf.size)?:break; if(n>0 && recognizer!!.acceptWaveForm(buf,n)){ val json=JSONObject(recognizer!!.result); val text=json.optString("text"); val words=json.optJSONArray("result"); val c=(0 until (words?.length()?:0)).map{words!!.getJSONObject(it).optDouble("conf",0.0).toFloat()}; if(matcher.matches(text,c) && debounce.accept(SystemClock.elapsedRealtime())) handler.post { beginHandoff() } } } }
    private val debounce=Debounce(3000)
    private fun beginHandoff(){ if(state!=HandoffState.LISTENING)return; state=HandoffState.YIELDING; releaseMic(); handler.postDelayed({ handoff(this) },350); handler.postDelayed({ if(state==HandoffState.YIELDING){state=HandoffState.ASSISTANT_ACTIVE; handler.postDelayed({state=HandoffState.COOLDOWN; listen()},120_000)} },400) }
    private fun releaseMic(){ recorder?.runCatching{stop();release()};recorder=null; recognizer?.close();recognizer=null; wakeLock?.runCatching{release()};wakeLock=null }
    private fun registerRecordingWatch(){ if(Build.VERSION.SDK_INT>=29) audio.registerAudioRecordingCallback(mainExecutor,object:AudioManager.AudioRecordingCallback(){override fun onRecordingConfigChanged(c:MutableList<AudioRecordingConfiguration>){ val other=c.any{it.clientUid!=applicationInfo.uid}; if(other){ absentAt=0; if(state==HandoffState.LISTENING){releaseMic();state=HandoffState.COOLDOWN} } else if(state==HandoffState.COOLDOWN){if(absentAt==0L)absentAt=SystemClock.elapsedRealtime(); handler.postDelayed({if(absentAt>0 && SystemClock.elapsedRealtime()-absentAt>=5000)listen()},5000)} }}) }
    private fun scheduleRetry(){ handler.postDelayed({listen()},Backoff.delay(retries++)) }
    override fun onDestroy(){ releaseMic(); overlay.hide(); super.onDestroy() }
    companion object { fun handoff(c:Context){ val jarvis="com.portal.assistant"; c.packageManager.getLaunchIntentForPackage(jarvis)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { c.startActivity(it) } }; c.sendBroadcast(Intent("com.portal.wake.action.WAKE").setPackage(jarvis).putExtra("com.portal.wake.extra.ID","jarvis")); Log.i("PortalJarvisWake","WAKE sent to $jarvis") } }
}
