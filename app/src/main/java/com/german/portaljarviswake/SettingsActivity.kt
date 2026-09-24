package com.german.portaljarviswake

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.german.portaljarviswake.core.Phrase

class SettingsActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    override fun onCreate(s: Bundle?) { super.onCreate(s); setContentView(R.layout.activity_settings)
        val enabled=findViewById<Switch>(R.id.enabled); val phrase=findViewById<EditText>(R.id.phrase); val off=findViewById<Switch>(R.id.screen_off); val boot=findViewById<Switch>(R.id.autostart); val status=findViewById<TextView>(R.id.status)
        enabled.isChecked=prefs.getBoolean("enabled", false); phrase.setText(prefs.getString("phrase", "hey jarvis")); off.isChecked=prefs.getBoolean("screenOff", false); boot.isChecked=prefs.getBoolean("boot", false)
        fun save() { val p=Phrase.normalize(phrase.text.toString()); if (!Phrase.isValid(p)) { phrase.error="Use at least two words"; return }; prefs.edit().putBoolean("enabled",enabled.isChecked).putString("phrase",p).putBoolean("screenOff",off.isChecked).putBoolean("boot",boot.isChecked).apply(); if(enabled.isChecked) startWake() else stopService(Intent(this, WakeService::class.java)); status.text="Status: ${if(enabled.isChecked) "starting" else "disabled"}" }
        enabled.setOnCheckedChangeListener { _,_->save() }; off.setOnCheckedChangeListener { _,_->save() }; boot.setOnCheckedChangeListener { _,_->save() }; phrase.setOnFocusChangeListener { _, has -> if(!has) save() }
        findViewById<Button>(R.id.retry).setOnClickListener { startWake(forceDownload=true) }; findViewById<Button>(R.id.test).setOnClickListener { WakeService.handoff(this); status.text="Status: test handoff sent" }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
        if (!Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }
    private fun startWake(forceDownload:Boolean=false) { ContextCompat.startForegroundService(this, Intent(this,WakeService::class.java).putExtra("download",forceDownload)) }
}
