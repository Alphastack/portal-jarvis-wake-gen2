package com.german.portaljarviswake
import android.content.*
import androidx.core.content.ContextCompat
class BootReceiver: BroadcastReceiver() { override fun onReceive(c:Context,i:Intent) { if(c.getSharedPreferences("settings",0).getBoolean("enabled",false) && c.getSharedPreferences("settings",0).getBoolean("boot",false)) ContextCompat.startForegroundService(c,Intent(c,WakeService::class.java)) } }
