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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.voicechanger.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var voiceService: VoiceChangerService? = null
    private var isBound = false
    private var isRunning = false
    private var selectedEffect = VoiceEffect.NORMAL

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as VoiceChangerService.LocalBinder
            voiceService = localBinder.getService()
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEffectCards()
        setupStartStopButton()
        setupSlider()
        selectEffect(VoiceEffect.NORMAL)
    }

    private fun setupEffectCards() {
        binding.cardNormal.setOnClickListener { selectEffect(VoiceEffect.NORMAL) }
        binding.cardChipmunk.setOnClickListener { selectEffect(VoiceEffect.CHIPMUNK) }
        binding.cardDeep.setOnClickListener { selectEffect(VoiceEffect.DEEP_VOICE) }
        binding.cardRobot.setOnClickListener { selectEffect(VoiceEffect.ROBOT) }
        binding.cardEcho.setOnClickListener { selectEffect(VoiceEffect.ECHO) }
    }

    private fun selectEffect(effect: VoiceEffect) {
        selectedEffect = effect
        voiceService?.audioProcessor?.currentEffect = effect

        // Reset all cards to default stroke
        val cards = listOf(
            binding.cardNormal,
            binding.cardChipmunk,
            binding.cardDeep,
            binding.cardRobot,
            binding.cardEcho
        )
        cards.forEach { card ->
            card.strokeWidth = 0
            card.strokeColor = ContextCompat.getColor(this, R.color.card_stroke_default)
        }

        // Highlight selected card
        val selectedCard: MaterialCardView = when (effect) {
            VoiceEffect.NORMAL -> binding.cardNormal
            VoiceEffect.CHIPMUNK -> binding.cardChipmunk
            VoiceEffect.DEEP_VOICE -> binding.cardDeep
            VoiceEffect.ROBOT -> binding.cardRobot
            VoiceEffect.ECHO -> binding.cardEcho
        }
        selectedCard.strokeWidth = 6
        selectedCard.strokeColor = ContextCompat.getColor(this, R.color.purple_500)

        val effectName = when (effect) {
            VoiceEffect.NORMAL -> "רגיל"
            VoiceEffect.CHIPMUNK -> "נמייה"
            VoiceEffect.DEEP_VOICE -> "עמוק"
            VoiceEffect.ROBOT -> "רובוט"
            VoiceEffect.ECHO -> "הד"
        }
        binding.tvSelectedEffect.text = "אפקט: $effectName"
    }

    private fun setupStartStopButton() {
        binding.btnStartStop.setOnClickListener {
            if (isRunning) {
                stopVoiceChanger()
            } else {
                if (checkPermissions()) {
                    startVoiceChanger()
                } else {
                    requestPermissions()
                }
            }
        }
    }

    private fun setupSlider() {
        binding.sliderIntensity.addOnChangeListener { _, value, _ ->
            voiceService?.audioProcessor?.intensity = value
        }
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
        bindService(
            Intent(this, VoiceChangerService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
        isRunning = true
        updateUI()
    }

    private fun stopVoiceChanger() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        val intent = Intent(this, VoiceChangerService::class.java).apply {
            action = VoiceChangerService.ACTION_STOP
        }
        startService(intent)
        isRunning = false
        voiceService = null
        updateUI()
    }

    private fun updateUI() {
        if (isRunning) {
            binding.btnStartStop.text = "⏹ עצור"
            binding.btnStartStop.setBackgroundColor(ContextCompat.getColor(this, R.color.stop_red))
            binding.tvStatus.text = "🎙 פעיל — מעבד קול..."
        } else {
            binding.btnStartStop.text = "▶ התחל"
            binding.btnStartStop.setBackgroundColor(ContextCompat.getColor(this, R.color.start_green))
            binding.tvStatus.text = "לחץ התחל להפעלה"
        }
    }

    private fun checkPermissions(): Boolean {
        val audioPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        val notifPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            PackageManager.PERMISSION_GRANTED
        }
        return audioPermission == PackageManager.PERMISSION_GRANTED &&
               notifPermission == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startVoiceChanger()
            } else {
                Toast.makeText(this, "נדרשת הרשאת מיקרופון", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }
}
