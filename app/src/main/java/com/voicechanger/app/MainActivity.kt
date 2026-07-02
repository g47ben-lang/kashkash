package com.voicechanger.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.voicechanger.app.databinding.ActivityMainBinding
import com.voicechanger.app.rvc.ModelManager
import com.voicechanger.app.rvc.RvcModel

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var voiceService: VoiceChangerService? = null
    private var isBound = false
    private var isRunning = false
    private var selectedEffect = VoiceEffect.NORMAL
    private var activeRvcModel: RvcModel? = null

    private val previewProcessor: AudioProcessor by lazy { AudioProcessor(this) }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            voiceService = (binder as VoiceChangerService.LocalBinder).getService()
            isBound = true
            applyCurrentSettingsToProcessor(voiceService!!.audioProcessor)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            voiceService = null
            isBound = false
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 100
        private const val DEVELOPER_EMAIL    = "g47ben@gmail.com"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupAiCards()
        setupQuickPresets()
        setupDeviceButtons()
        setupStartStopButton()
        setupManualSliders()
        setupAbout()

        highlightMicButton(binding.btnMicMain)
        highlightOutputButton(binding.btnOutEarpiece)
        updateSelectedLabel()

        Thread { refreshRvcModels() }.apply { isDaemon = true; start() }
    }

    // ── AI model list ─────────────────────────────────────────────────────────

    private fun refreshRvcModels() {
        val models = ModelManager.listModels(this)
        val ready  = ModelManager.isReady(this)
        runOnUiThread { buildRvcSection(models, ready) }
    }

    private fun buildRvcSection(models: List<RvcModel>, hubertReady: Boolean) {
        val container = binding.rvcModelContainer
        container.removeAllViews()

        if (!hubertReady && models.isEmpty()) {
            container.visibility = View.GONE
            return
        }

        container.visibility = View.VISIBLE

        if (!hubertReady) {
            val msg = TextView(this).apply {
                text = "⚠️ חסר hubert.onnx\n${ModelManager.installPathInstructions(this@MainActivity)}"
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.stop_red))
            }
            container.addView(msg)
            return
        }

        if (models.isEmpty()) {
            container.visibility = View.GONE
            return
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        container.addView(row)

        for (model in models) {
            val btn = Button(this).apply {
                text = model.name
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.white))
                backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.preview_btn)
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginEnd = 6
                layoutParams = lp
                setOnClickListener { activateRvcModel(model) }
            }
            row.addView(btn)
        }

        val dspBtn = Button(this).apply {
            text = "DSP"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.white))
            backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.preview_btn)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { deactivateRvc() }
        }
        row.addView(dspBtn)
    }

    private fun activateRvcModel(model: RvcModel) {
        activeRvcModel = model
        selectedEffect = VoiceEffect.RVC_AI
        updateSelectedLabel()
        Toast.makeText(this, "טוען מודל ${model.name}…", Toast.LENGTH_SHORT).show()

        val hubertPath = ModelManager.hubertPath(this)
        val svc = voiceService
        if (svc != null) {
            Thread { svc.audioProcessor.loadRvcModel(hubertPath, model) }.apply { isDaemon = true; start() }
        } else {
            Thread { previewProcessor.loadRvcModel(hubertPath, model) }.apply { isDaemon = true; start() }
        }
    }

    private fun deactivateRvc() {
        activeRvcModel = null
        voiceService?.audioProcessor?.clearRvcModel()
        previewProcessor.clearRvcModel()
        selectEffect(VoiceEffect.NORMAL)
    }

    // ── AI Cards ──────────────────────────────────────────────────────────────

    private fun setupAiCards() {
        binding.cardBibi.setOnClickListener {
            val models = ModelManager.listModels(this)
            val bibi = models.firstOrNull { it.name.contains("bibi", ignoreCase = true)
                    || it.name.contains("ביבי", ignoreCase = true)
                    || it.name.contains("netanyahu", ignoreCase = true) }
            if (bibi != null) activateRvcModel(bibi)
            else selectEffect(VoiceEffect.BIBI)
            highlightAiCard(binding.cardBibi)
        }
        binding.cardTrump.setOnClickListener {
            val models = ModelManager.listModels(this)
            val trump = models.firstOrNull { it.name.contains("trump", ignoreCase = true)
                    || it.name.contains("טראמפ", ignoreCase = true) }
            if (trump != null) activateRvcModel(trump)
            else selectEffect(VoiceEffect.TRUMP)
            highlightAiCard(binding.cardTrump)
        }
        binding.previewBibi.setOnClickListener { previewProcessor.preview(VoiceEffect.BIBI) }
        binding.previewTrump.setOnClickListener { previewProcessor.preview(VoiceEffect.TRUMP) }
    }

    private fun highlightAiCard(active: CardView) {
        binding.cardBibi.setCardBackgroundColor(ContextCompat.getColor(this,
            if (active === binding.cardBibi) R.color.bibi_primary else R.color.bibi_dark))
        binding.cardTrump.setCardBackgroundColor(ContextCompat.getColor(this,
            if (active === binding.cardTrump) R.color.trump_primary else R.color.trump_dark))
    }

    // ── Quick DSP presets ─────────────────────────────────────────────────────

    private fun setupQuickPresets() {
        binding.cardNormal.setOnClickListener    { selectEffect(VoiceEffect.NORMAL) }
        binding.cardChild.setOnClickListener     { selectEffect(VoiceEffect.CHILD) }
        binding.cardOldMan.setOnClickListener    { selectEffect(VoiceEffect.OLD_MAN) }
        binding.cardTelephone.setOnClickListener { selectEffect(VoiceEffect.TELEPHONE) }

        // Hidden cards — keep wired for binding safety but no-op
        binding.cardChipmunk.setOnClickListener  { selectEffect(VoiceEffect.CHIPMUNK) }
        binding.cardDeep.setOnClickListener      { selectEffect(VoiceEffect.DEEP_VOICE) }
        binding.cardRobot.setOnClickListener     { selectEffect(VoiceEffect.ROBOT) }
        binding.cardEcho.setOnClickListener      { selectEffect(VoiceEffect.ECHO) }
    }

    private val quickPresetCards get() = listOf(
        binding.cardNormal, binding.cardChild, binding.cardOldMan, binding.cardTelephone)

    private fun selectEffect(effect: VoiceEffect) {
        selectedEffect = effect
        activeRvcModel = null
        voiceService?.audioProcessor?.clearRvcModel()
        voiceService?.audioProcessor?.currentEffect = effect
        previewProcessor.currentEffect = effect

        // Reset AI card highlights
        binding.cardBibi.setCardBackgroundColor(ContextCompat.getColor(this, R.color.bibi_dark))
        binding.cardTrump.setCardBackgroundColor(ContextCompat.getColor(this, R.color.trump_dark))

        // Highlight active quick preset
        val presetMap = mapOf(
            VoiceEffect.NORMAL    to binding.cardNormal,
            VoiceEffect.CHILD     to binding.cardChild,
            VoiceEffect.OLD_MAN   to binding.cardOldMan,
            VoiceEffect.TELEPHONE to binding.cardTelephone
        )
        quickPresetCards.forEach { it.setCardBackgroundColor(ContextCompat.getColor(this, R.color.card_bg)) }
        presetMap[effect]?.setCardBackgroundColor(ContextCompat.getColor(this, R.color.card_selected))

        updateSelectedLabel()
    }

    private fun updateSelectedLabel() {
        binding.tvSelectedEffect.text = when {
            activeRvcModel != null -> "מצב: 🤖 AI — ${activeRvcModel!!.name}"
            else -> "מצב: ${when (selectedEffect) {
                VoiceEffect.NORMAL     -> "רגיל"
                VoiceEffect.CHIPMUNK   -> "נמייה"
                VoiceEffect.DEEP_VOICE -> "קול עמוק"
                VoiceEffect.ROBOT      -> "רובוט"
                VoiceEffect.ECHO       -> "הד"
                VoiceEffect.CHILD      -> "ילד"
                VoiceEffect.BIBI       -> "ביבי (DSP)"
                VoiceEffect.TRUMP      -> "טראמפ (DSP)"
                VoiceEffect.OLD_MAN    -> "זקן"
                VoiceEffect.TELEPHONE  -> "טלפון"
                VoiceEffect.RVC_AI     -> "AI"
            }}"
        }
    }

    // ── Manual sliders ────────────────────────────────────────────────────────

    private fun setupManualSliders() {
        binding.seekbarPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val semitones = progress - 12  // -12 .. +12
                binding.tvPitchValue.text = if (semitones == 0) "0" else "%+d".format(semitones)
                val shift = semitones.toFloat()
                voiceService?.audioProcessor?.manualPitchSemitones = shift
                previewProcessor.manualPitchSemitones = shift
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.seekbarEcho.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvEchoValue.text = "$progress%"
                val mix = progress / 100f
                voiceService?.audioProcessor?.manualEchoMix = mix
                previewProcessor.manualEchoMix = mix
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.seekbarRobot.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvRobotValue.text = "$progress%"
                val mix = progress / 100f
                voiceService?.audioProcessor?.manualRobotMix = mix
                previewProcessor.manualRobotMix = mix
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun applyCurrentSettingsToProcessor(proc: AudioProcessor) {
        proc.currentEffect = selectedEffect
        proc.manualPitchSemitones = (binding.seekbarPitch.progress - 12).toFloat()
        proc.manualEchoMix        = binding.seekbarEcho.progress  / 100f
        proc.manualRobotMix       = binding.seekbarRobot.progress / 100f
    }

    // ── Device buttons ────────────────────────────────────────────────────────

    private fun setupDeviceButtons() {
        binding.btnMicMain.setOnClickListener {
            setMicSource(MicSource.PHONE); highlightMicButton(binding.btnMicMain)
        }
        binding.btnMicComm.setOnClickListener {
            setMicSource(MicSource.HEADSET); highlightMicButton(binding.btnMicComm)
        }
        binding.btnOutEarpiece.setOnClickListener {
            setOutputMode(OutputMode.EARPIECE); highlightOutputButton(binding.btnOutEarpiece)
        }
        binding.btnOutSpeaker.setOnClickListener {
            setOutputMode(OutputMode.SPEAKER); highlightOutputButton(binding.btnOutSpeaker)
        }
        binding.btnOutBluetooth.setOnClickListener {
            setOutputMode(OutputMode.BLUETOOTH); highlightOutputButton(binding.btnOutBluetooth)
        }
    }

    private fun setMicSource(src: MicSource) {
        previewProcessor.micSource = src
        val svc = voiceService?.audioProcessor ?: return
        if (isRunning) {
            stopVoiceChanger(); svc.micSource = src
            if (checkPermissions()) startVoiceChanger()
        } else { svc.micSource = src }
    }

    private fun setOutputMode(mode: OutputMode) {
        previewProcessor.outputMode = mode
        voiceService?.audioProcessor?.let { it.outputMode = mode; if (isRunning) it.applyOutputRouting() }
    }

    private val micButtons    get() = listOf(binding.btnMicMain, binding.btnMicComm)
    private val outputButtons get() = listOf(binding.btnOutEarpiece, binding.btnOutSpeaker, binding.btnOutBluetooth)

    private fun highlightMicButton(active: Button) {
        micButtons.forEach {
            it.backgroundTintList = ContextCompat.getColorStateList(this,
                if (it === active) R.color.btn_device_active else R.color.btn_device)
        }
    }

    private fun highlightOutputButton(active: Button) {
        outputButtons.forEach {
            it.backgroundTintList = ContextCompat.getColorStateList(this,
                if (it === active) R.color.btn_device_active else R.color.btn_device)
        }
    }

    // ── Start / stop ──────────────────────────────────────────────────────────

    private fun setupStartStopButton() {
        binding.btnStartStop.setOnClickListener {
            if (isRunning) stopVoiceChanger()
            else if (checkPermissions()) startVoiceChanger()
            else requestPermissions()
        }
    }

    private fun startVoiceChanger() {
        val intent = Intent(this, VoiceChangerService::class.java).apply {
            action = VoiceChangerService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        bindService(Intent(this, VoiceChangerService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        isRunning = true
        updateUI()
    }

    private fun stopVoiceChanger() {
        if (isBound) { unbindService(serviceConnection); isBound = false }
        startService(Intent(this, VoiceChangerService::class.java).apply {
            action = VoiceChangerService.ACTION_STOP
        })
        isRunning = false; voiceService = null
        updateUI()
    }

    private fun updateUI() {
        if (isRunning) {
            binding.btnStartStop.text = "עצור"
            binding.btnStartStop.backgroundTintList = ContextCompat.getColorStateList(this, R.color.stop_red)
            binding.tvStatus.text = if (activeRvcModel != null)
                "🤖 AI פעיל — ${activeRvcModel!!.name}" else "פעיל — מעבד קול..."
        } else {
            binding.btnStartStop.text = "התחל"
            binding.btnStartStop.backgroundTintList = ContextCompat.getColorStateList(this, R.color.start_green)
            binding.tvStatus.text = "לחץ התחל להפעלה"
        }
    }

    // ── About ─────────────────────────────────────────────────────────────────

    private fun setupAbout() {
        val emailClick = View.OnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$DEVELOPER_EMAIL"))
            intent.putExtra(Intent.EXTRA_SUBJECT, "Voice AI App")
            startActivity(Intent.createChooser(intent, "שלח מייל"))
        }
        binding.tvDeveloper.setOnClickListener(emailClick)
        binding.tvEmail.setOnClickListener(emailClick)
    }

    // ── Permissions ───────────────────────────────────────────────────────────

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
        previewProcessor.release()
        super.onDestroy()
    }
}
