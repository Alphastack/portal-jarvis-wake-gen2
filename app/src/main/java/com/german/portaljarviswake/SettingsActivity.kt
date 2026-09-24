package com.german.portaljarviswake

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.german.portaljarviswake.core.Phrase

class SettingsActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }; private lateinit var status: TextView
    private val statusReceiver = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = updateStatus() }
    override fun onCreate(state: Bundle?) { super.onCreate(state); setContentView(R.layout.activity_settings)
        val enabled = findViewById<Switch>(R.id.enabled); val phrase = findViewById<EditText>(R.id.phrase); val off = findViewById<Switch>(R.id.screen_off); val boot = findViewById<Switch>(R.id.autostart); val sensitivity = findViewById<Spinner>(R.id.sensitivity); status = findViewById(R.id.status)
        enabled.isChecked = prefs.getBoolean("enabled", false); phrase.setText(prefs.getString("phrase", "hey jarvis")); off.isChecked = prefs.getBoolean("screenOff", false); boot.isChecked = prefs.getBoolean("boot", true)
        sensitivity.setSelection(listOf("STRICT", "BALANCED", "RELAXED").indexOf(prefs.getString("sensitivity", "BALANCED")))
        fun save() { val normalized = Phrase.normalize(phrase.text.toString()); if (!Phrase.isValid(normalized)) { phrase.error = "Use at least two words"; return }; prefs.edit().putBoolean("enabled", enabled.isChecked).putString("phrase", normalized).putBoolean("screenOff", off.isChecked).putBoolean("boot", boot.isChecked).putString("sensitivity", listOf("STRICT", "BALANCED", "RELAXED")[sensitivity.selectedItemPosition]).apply(); if (enabled.isChecked && hasMic()) startWake() else stopService(Intent(this, WakeService::class.java)); updateStatus() }
        enabled.setOnCheckedChangeListener { _, _ -> save() }; off.setOnCheckedChangeListener { _, _ -> save() }; boot.setOnCheckedChangeListener { _, _ -> save() }; phrase.setOnFocusChangeListener { _, focused -> if (!focused) save() }
        findViewById<Button>(R.id.retry).setOnClickListener { if (!ModelInstaller(filesDir).installed()) startWake() else Toast.makeText(this, "A valid model is already installed", Toast.LENGTH_SHORT).show() }
        findViewById<Button>(R.id.test).setOnClickListener {
            WakeService.testHandoff(this)
            Toast.makeText(this, "Test handoff requested", Toast.LENGTH_SHORT).show()
        }
        if (!hasMic()) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
        if (!Settings.canDrawOverlays(this)) Toast.makeText(this, "Overlay permission is optional but may improve Portal launch reliability", Toast.LENGTH_LONG).show()
    }
    override fun onResume() { super.onResume(); registerReceiver(statusReceiver, IntentFilter(WakeService.ACTION_STATUS)); updateStatus() }
    override fun onPause() { unregisterReceiver(statusReceiver); super.onPause() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) { super.onRequestPermissionsResult(requestCode, permissions, results); if (requestCode == 7 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED && prefs.getBoolean("enabled", false)) startWake(); updateStatus() }
    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun startWake() = ContextCompat.startForegroundService(
        this,
        Intent(this, WakeService::class.java).setAction(WakeService.ACTION_RELOAD),
    )
    private fun updateStatus() { val state = prefs.getString("status", if (prefs.getBoolean("enabled", false)) "Starting" else "Disabled"); status.text = "Status: $state\n${prefs.getString("statusDetail", "")}" }
}
