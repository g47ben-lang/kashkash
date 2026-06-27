package com.voicechanger.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.voicechanger.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var voiceService: VoiceChangerService? = null
    private var isBound = false
    private var isRunning = false
    private var selectedEffect = VoiceEffect.NORMAL

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            voiceService = (binder as VoiceChangerService.LocalBinder).getService()
            isBound = true
            voiceService?.audioProcessor?.currentEffect = selectedEffect
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            voiceService = null
            isBound = false
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 100
        // SeekBar: 0-18 maps to intensity 0.2-2.0 (step 0.1)
        private fun seekbarToIntensity(progress: Int): Float = 0.2f + progress * 0.1f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEffectCards()
        setupStartStopButton()
        setupSeekBar()
        selectEffect(VoiceEffect.NORMAL)
    }

    private fun setupEffectCards() {
        binding.cardNormal.setOnClickListener   { selectEffect(VoiceEffect.NORMAL) }
        binding.cardChipmunk.setOnClickListener { selectEffect(VoiceEffect.CHIPMUNK) }
        binding.cardDeep.setOnClickListener     { selectEffect(VoiceEffect.DEEP_VOICE) }
        binding.cardRobot.setOnClickListener    { selectEffect(VoiceEffect.ROBOT) }
        binding.cardEcho.setOnClickListener     { selectEffect(VoiceEffect.ECHO) }
    }

    private fun selectEffect(effect: VoiceEffect) {
        selectedEffect = effect
        voiceService?.audioProcessor?.currentEffect = effect

        val allCards = listOf(binding.cardNormal, binding.cardChipmunk, binding.cardDeep,
                              binding.cardRobot, binding.cardEcho)
        allCards.forEach { it.setCardBackgroundColor(ContextCompat.getColor(this, R.color.card_bg)) }

        val selected: CardView = when (effect) {
            VoiceEffect.NORMAL    -> binding.cardNormal
            VoiceEffect.CHIPMUNK  -> binding.cardChipmunk
            VoiceEffect.DEEP_VOICE -> binding.cardDeep
            VoiceEffect.ROBOT     -> binding.cardRobot
            VoiceEffect.ECHO      -> binding.cardEcho
        }
        selected.setCardBackgroundColor(ContextCompat.getColor(this, R.color.card_selected))

        binding.tvSelectedEffect.text = "אפקט: ${when (effect) {
            VoiceEffect.NORMAL    -> "רגיל"
            VoiceEffect.CHIPMUNK  -> "נמייה"
            VoiceEffect.DEEP_VOICE -> "קול עמוק"
            VoiceEffect.ROBOT     -> "רובוט"
            VoiceEffect.ECHO      -> "הד"
        }}"
    }

    private fun setupStartStopButton() {
        binding.btnStartStop.setOnClickListener {
            if (isRunning) stopVoiceChanger()
            else if (checkPermissions()) startVoiceChanger()
            else requestPermissions()
        }
    }

    private fun setupSeekBar() {
        binding.seekbarIntensity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                voiceService?.audioProcessor?.intensity = seekbarToIntensity(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun startVoiceChanger() {
        val intent = Intent(this, VoiceChangerService::class.java).apply {
            action = VoiceChangerService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(Intent(this, VoiceChangerService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        isRunning = true
        updateUI()
    }

    private fun stopVoiceChanger() {
        if (isBound) { unbindService(serviceConnection); isBound = false }
        startService(Intent(this, VoiceChangerService::class.java).apply { action = VoiceChangerService.ACTION_STOP })
        isRunning = false
        voiceService = null
        updateUI()
    }

    private fun updateUI() {
        if (isRunning) {
            binding.btnStartStop.text = "עצור"
            binding.btnStartStop.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.stop_red)
            binding.tvStatus.text = "פעיל - מעבד קול..."
        } else {
            binding.btnStartStop.text = "התחל"
            binding.btnStartStop.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.start_green)
            binding.tvStatus.text = "לחץ התחל להפעלה"
        }
    }

    private fun checkPermissions(): Boolean {
        val audio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        else PackageManager.PERMISSION_GRANTED
        return audio == PackageManager.PERMISSION_GRANTED && notif == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                startVoiceChanger()
            else
                Toast.makeText(this, "נדרשת הרשאת מיקרופון", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        if (isBound) { try { unbindService(serviceConnection) } catch (_: Exception) {} }
        super.onDestroy()
    }
}
