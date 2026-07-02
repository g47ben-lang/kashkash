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
        setupDeviceButtons()
        setupStartStopButton()
        setupSeekBar()
        selectEffect(VoiceEffect.NORMAL)
        highlightMicButton(binding.btnMicMain)
        highlightOutputButton(binding.btnOutEarpiece)

        // Load AI models on background thread
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

        val header = TextView(this).apply {
            text = "🤖 קולות AI (מודלים אמיתיים)"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setPadding(0, 0, 0, 8)
        }
        container.addView(header)

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
            val msg = TextView(this).apply {
                text = "לא נמצאו מודלים.\n${ModelManager.installPathInstructions(this@MainActivity)}"
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            }
            container.addView(msg)
            return
        }

        // One button per model
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
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

        // "DSP" button to go back to regular effects
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
        binding.tvSelectedEffect.text = "🤖 AI: ${model.name}"
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

    // ── Effect cards ──────────────────────────────────────────────────────────

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
                if (it === active) R.color.purple_500 else R.color.preview_btn)
        }
    }

    private fun highlightOutputButton(active: Button) {
        outputButtons.forEach {
            it.backgroundTintList = ContextCompat.getColorStateList(this,
                if (it === active) R.color.teal_700 else R.color.preview_btn)
        }
    }

    // ── Effect selection ──────────────────────────────────────────────────────

    private fun selectEffect(effect: VoiceEffect) {
        selectedEffect = effect
        activeRvcModel = null
        voiceService?.audioProcessor?.clearRvcModel()
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
            VoiceEffect.RVC_AI     -> return
        }
        selected.setCardBackgroundColor(ContextCompat.getColor(this, R.color.card_selected))

        binding.tvSelectedEffect.text = "אפקט: ${when (effect) {
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
                "🤖 AI פעיל — ${activeRvcModel!!.name}" else "פעיל - מעבד קול..."
        } else {
            binding.btnStartStop.text = "התחל"
            binding.btnStartStop.backgroundTintList = ContextCompat.getColorStateList(this, R.color.start_green)
            binding.tvStatus.text = "לחץ התחל להפעלה"
        }
    }

    // ── SeekBar ───────────────────────────────────────────────────────────────

    private fun setupSeekBar() {
        binding.seekbarIntensity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val lvl = seekbarToIntensity(progress)
                voiceService?.audioProcessor?.intensity = lvl
                previewProcessor.intensity = lvl
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
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
