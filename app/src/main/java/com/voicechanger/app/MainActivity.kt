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

    // Standalone AudioProcessor used only for previews (no service needed)
    private val previewProcessor: AudioProcessor by lazy { AudioProcessor(this) }

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
        private fun seekbarToIntensity(progress: Int): Float = 0.2f + progress * 0.1f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEffectCards()
        setupPreviewButtons()
        setupStartStopButton()
        setupSeekBar()
        selectEffect(VoiceEffect.NORMAL)
    }

    private fun setupEffectCards() {
        binding.cardNormal.setOnClickListener    { selectEffect(VoiceEffect.NORMAL) }
        binding.cardChipmunk.setOnClickListener  { selectEffect(VoiceEffect.CHIPMUNK) }
        binding.cardDeep.setOnClickListener      { selectEffect(VoiceEffect.DEEP_VOICE) }
        binding.cardRobot.setOnClickListener     { selectEffect(VoiceEffect.ROBOT) }
        binding.cardEcho.setOnClickListener      { selectEffect(VoiceEffect.ECHO) }
        binding.cardChild.setOnClickListener     { selectEffect(VoiceEffect.CHILD) }
        binding.cardBibi.setOnClickListener      { selectEffect(VoiceEffect.BIBI) }
        binding.cardTrump.setOnClickListener     { selectEffect(VoiceEffect.TRUMP) }
        binding.cardOldMan.setOnClickListener    { selectEffect(VoiceEffect.OLD_MAN) }
        binding.cardTelephone.setOnClickListener { selectEffect(VoiceEffect.TELEPHONE) }
    }

    private fun setupPreviewButtons() {
        binding.previewNormal.setOnClickListener    { previewProcessor.preview(VoiceEffect.NORMAL) }
        binding.previewChipmunk.setOnClickListener  { previewProcessor.preview(VoiceEffect.CHIPMUNK) }
        binding.previewDeep.setOnClickListener      { previewProcessor.preview(VoiceEffect.DEEP_VOICE) }
        binding.previewRobot.setOnClickListener     { previewProcessor.preview(VoiceEffect.ROBOT) }
        binding.previewEcho.setOnClickListener      { previewProcessor.preview(VoiceEffect.ECHO) }
        binding.previewChild.setOnClickListener     { previewProcessor.preview(VoiceEffect.CHILD) }
        binding.previewBibi.setOnClickListener      { previewProcessor.preview(VoiceEffect.BIBI) }
        binding.previewTrump.setOnClickListener     { previewProcessor.preview(VoiceEffect.TRUMP) }
        binding.previewOldMan.setOnClickListener    { previewProcessor.preview(VoiceEffect.OLD_MAN) }
        binding.previewTelephone.setOnClickListener { previewProcessor.preview(VoiceEffect.TELEPHONE) }
    }

    private fun selectEffect(effect: VoiceEffect) {
        selectedEffect = effect
        voiceService?.audioProcessor?.currentEffect = effect

        val allCards = listOf(
            binding.cardNormal, binding.cardChipmunk, binding.cardDeep,
            binding.cardRobot, binding.cardEcho, binding.cardChild,
            binding.cardBibi, binding.cardTrump, binding.cardOldMan, binding.cardTelephone
        )
        allCards.forEach { it.setCardBackgroundColor(ContextCompat.getColor(this, R.color.card_bg)) }

        val selected: CardView = when (effect) {
            VoiceEffect.NORMAL     -> binding.cardNormal
            VoiceEffect.CHIPMUNK   -> binding.cardChipmunk
            VoiceEffect.DEEP_VOICE -> binding.cardDeep
            VoiceEffect.ROBOT      -> binding.cardRobot
            VoiceEffect.ECHO       -> binding.cardEcho
            VoiceEffect.CHILD      -> binding.cardChild
            VoiceEffect.BIBI       -> binding.cardBibi
            VoiceEffect.TRUMP      -> binding.cardTrump
            VoiceEffect.OLD_MAN    -> binding.cardOldMan
            VoiceEffect.TELEPHONE  -> binding.cardTelephone
        }
        selected.setCardBackgroundColor(ContextCompat.getColor(this, R.color.card_selected))

        binding.tvSelectedEffect.text = "אפקט: ${when (effect) {
            VoiceEffect.NORMAL     -> "רגיל"
            VoiceEffect.CHIPMUNK   -> "נמייה"
            VoiceEffect.DEEP_VOICE -> "קול עמוק"
            VoiceEffect.ROBOT      -> "רובוט"
            VoiceEffect.ECHO       -> "הד"
            VoiceEffect.CHILD      -> "ילד"
            VoiceEffect.BIBI       -> "ביבי"
            VoiceEffect.TRUMP      -> "טראמפ"
            VoiceEffect.OLD_MAN    -> "זקן"
            VoiceEffect.TELEPHONE  -> "טלפון"
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
                val intensity = seekbarToIntensity(progress)
                voiceService?.audioProcessor?.intensity = intensity
                previewProcessor.intensity = intensity
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
