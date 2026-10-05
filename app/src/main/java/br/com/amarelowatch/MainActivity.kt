package br.com.amarelowatch

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch
import br.com.amarelowatch.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) {
            Bridge.log(getString(R.string.msg_no_media_projection))
            Bridge.update { it.copy(starting = false) }
            return@registerForActivityResult
        }
        CaptureService.start(this, result.resultCode, result.data!!)
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private val stateObserver: (Bridge.State) -> Unit = { state -> render(state) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyFullscreen()

        setupQualityControls()
        binding.btnToggle.setOnClickListener { onToggleClicked() }
        binding.btnCopiar.setOnClickListener { copyUrl() }

        Bridge.log("Amarelo Watch pronto")
        refreshAddress()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    /**
     * Tela inteira de verdade: as barras do sistema saem e o conteúdo ocupa a
     * tela toda. Um arrasto na borda traz as barras de volta temporariamente,
     * e reaplicamos ao voltar do diálogo de captura, que sempre as restaura.
     */
    private fun applyFullscreen() {
        // Sem FLAG_LAYOUT_NO_LIMITS/IN_SCREEN o sistema ainda reserva a faixa da
        // barra de status e deixa um retângulo preto no topo da janela.
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            val edges = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(edges.left, edges.top, edges.right, edges.bottom)
            insets
        }

        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onStart() {
        super.onStart()
        Bridge.observe(stateObserver)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Bridge.logs.collect { lines ->
                    binding.txtLog.text = lines.joinToString("\n")
                }
            }
        }
    }

    override fun onStop() {
        Bridge.unobserve(stateObserver)
        super.onStop()
    }

    // ------------------------------------------------------------- actions

    private fun onToggleClicked() {
        val state = Bridge.current()
        if (state.streaming || state.starting) {
            Bridge.log("Parando a transmissão…")
            CaptureService.stop(this)
            return
        }

        askNotificationPermission()
        Bridge.log("Solicitando permissão de captura…")
        Bridge.update { it.copy(starting = true, error = null) }

        val manager = getSystemService(MediaProjectionManager::class.java)
        runCatching { manager.createScreenCaptureIntent() }
            .onSuccess { projectionLauncher.launch(it) }
            .onFailure {
                Bridge.update { it.copy(starting = false) }
                Bridge.log("Este aparelho não oferece captura de tela: ${it.message}")
                toast(getString(R.string.msg_no_media_projection))
            }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun copyUrl() {
        val url = Bridge.current().url ?: return
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Amarelo Watch", url))
        toast(getString(R.string.action_copied))
    }

    // -------------------------------------------------------------- widgets

    private fun setupQualityControls() {
        val config = Settings.load(this)

        val chips = mapOf(
            binding.chip480 to 480,
            binding.chip720 to 720,
            binding.chip1080 to 1080,
        )
        chips.entries.forEach { (chip: Chip, value: Int) ->
            chip.isChecked = value == config.shortEdge
        }
        binding.grupoResolucao.setOnCheckedStateChangeListener { group, ids ->
            val id = ids.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val chip = group.findViewById<Chip>(id) ?: return@setOnCheckedStateChangeListener
            val value = chips[chip] ?: return@setOnCheckedStateChangeListener
            persist(config.copy(shortEdge = value))
        }

        binding.sliderFps.value = config.fps.toFloat()
        binding.sliderJpeg.value = config.quality.toFloat()
        updateSliderLabels(config)

        binding.sliderFps.addOnChangeListener { _, value, _ ->
            val updated = Settings.load(this).copy(fps = value.toInt())
            updateSliderLabels(updated)
            persist(updated)
        }
        binding.sliderJpeg.addOnChangeListener { _, value, _ ->
            val updated = Settings.load(this).copy(quality = value.toInt())
            updateSliderLabels(updated)
            persist(updated)
        }
    }

    private fun updateSliderLabels(config: StreamConfig) {
        binding.rotuloFps.text = getString(R.string.label_fps) + " · ${config.fps}"
        binding.rotuloJpeg.text = getString(R.string.label_jpeg) + " · ${config.quality}%"
    }

    private fun persist(config: StreamConfig) {
        Settings.save(this, config)
        Bridge.log(
            "Ajuste: ${config.shortEdge}p · ${config.fps} fps · ${config.quality}% (aplica ao reconectar)"
        )
    }

    // --------------------------------------------------------------- render

    private fun render(state: Bridge.State) {
        val active = state.streaming || state.starting

        binding.txtToggle.setText(if (active) R.string.stop else R.string.start)
        binding.icToggle.setImageResource(
            if (active) R.drawable.ic_bolha_stop else R.drawable.ic_bolha_play
        )

        val (statusText, dotColor) = when {
            state.error != null -> getString(R.string.status_error) to Color.parseColor("#C62828")
            state.starting -> getString(R.string.status_starting) to Color.parseColor("#E0AE00")
            state.streaming -> getString(R.string.status_running) to Color.parseColor("#1B8E3C")
            else -> getString(R.string.status_idle) to Color.parseColor("#5C4A00")
        }
        binding.txtStatus.text = statusText
        binding.pontoStatus.backgroundTintList =
            android.content.res.ColorStateList.valueOf(dotColor)

        if (state.url != null && state.url != binding.txtUrl.text) {
            binding.txtUrl.text = state.url
        }
        binding.txtAvisoWifi.visibility =
            if (!Net.isOnWifi(this) && state.url == null) android.view.View.VISIBLE
            else android.view.View.GONE

        binding.valorFps.text = if (state.streaming) "${state.fps} fps" else "—"
        binding.valorBitrate.text = if (state.streaming) formatBitrate(state.kbps) else "—"
        binding.valorClientes.text = state.clients.toString()
        binding.valorResolucao.text = if (state.width > 0) "${state.width}×${state.height}" else "—"

        if (state.streaming) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun refreshAddress() {
        val port = Settings.load(this).port
        val url = Net.url(Net.localIp(this) ?: "IP-DO-CELULAR", port)
        binding.txtUrl.text = url
    }

    private fun formatBitrate(kbps: Int): String = when {
        kbps >= 1000 -> String.format("%.1f Mbps", kbps / 1000f)
        else -> "$kbps kbps"
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        refreshAddress()
    }
}
