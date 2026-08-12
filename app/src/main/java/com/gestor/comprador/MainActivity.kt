package com.gestor.comprador

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gestor.comprador.data.ApiClient
import com.gestor.comprador.data.ApiResult
import com.gestor.comprador.data.AppConfig
import com.gestor.comprador.data.SessionManager
import com.gestor.comprador.databinding.ActivityMainBinding
import com.gestor.comprador.service.LocationTrackingService
import com.gestor.comprador.service.TrackingState
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sessionManager: SessionManager
    private val api = ApiClient()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val fine = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
            val coarse = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (fine || coarse) {
                startTrackingService()
            } else {
                Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)

        binding.btnLogin.setOnClickListener { doLogin() }
        binding.btnLogout.setOnClickListener { doLogout() }
        binding.btnToggleTracking.setOnClickListener { toggleTracking() }
        binding.btnToggleTrip.setOnClickListener { toggleTrip() }
    }

    override fun onResume() {
        super.onResume()
        refreshSessionUi()
        updateTrackingUi()
        updateTripUi()
    }

    // ------------------------------------------------------------------
    // Login
    // ------------------------------------------------------------------
    private fun doLogin() {
        val username = binding.etUsername.text?.toString()?.trim().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()

        if (username.isEmpty() || password.isEmpty()) {
            showLoginError("Preencha usuário e senha.")
            return
        }

        val deviceId = "android-" + UUID.randomUUID().toString().take(12)
        binding.btnLogin.isEnabled = false
        binding.btnLogin.text = "Entrando..."

        lifecycleScope.launch {
            val result = api.login(username, password, deviceId)
            binding.btnLogin.isEnabled = true
            binding.btnLogin.text = getString(R.string.login_btn)

            when (result) {
                is ApiResult.Success -> {
                    val d = result.data
                    sessionManager.saveLogin(d.token, d.id, d.name, d.role)
                    refreshSessionUi()
                    showLoginError(null)
                    Toast.makeText(this@MainActivity, "Bem-vindo, ${d.name}!", Toast.LENGTH_SHORT).show()
                }
                is ApiResult.Error -> showLoginError(result.message)
            }
        }
    }

    private fun doLogout() {
        stopTrackingService()
        lifecycleScope.launch {
            sessionManager.clear()
            refreshSessionUi()
        }
    }

    // ------------------------------------------------------------------
    // Rastreamento GPS
    // ------------------------------------------------------------------
    private fun toggleTracking() {
        if (TrackingState.running) {
            stopTrackingService()
        } else {
            requestPermissionsAndStart()
        }
    }

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            maybeAskBatteryOptimization()
            startTrackingService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun maybeAskBatteryOptimization() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val pkg = packageName
        if (Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(pkg)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$pkg"))
                startActivity(intent)
            } catch (e: Exception) {
                // ignora — app continua funcionando em foreground
            }
        }
    }

    private fun startTrackingService() {
        val intent = Intent(this, LocationTrackingService::class.java)
            .setAction(LocationTrackingService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Rastreamento iniciado", Toast.LENGTH_SHORT).show()
        updateTrackingUi()
    }

    private fun stopTrackingService() {
        val intent = Intent(this, LocationTrackingService::class.java)
            .setAction(LocationTrackingService.ACTION_STOP)
        startService(intent)
        Toast.makeText(this, "Rastreamento parado", Toast.LENGTH_SHORT).show()
        updateTrackingUi()
    }

    // ------------------------------------------------------------------
    // Viagem de compras
    // ------------------------------------------------------------------
    @SuppressLint("SetTextI18n")
    private fun toggleTrip() {
        lifecycleScope.launch {
            val session = sessionManager.read() ?: return@launch
            val finish = TrackingState.tripActive
            binding.btnToggleTrip.isEnabled = false
            val result = api.toggleTrip(session.token, finish)
            binding.btnToggleTrip.isEnabled = true

            when (result) {
                is ApiResult.Success -> {
                    TrackingState.tripActive = !finish
                    updateTripUi()
                    Toast.makeText(
                        this@MainActivity,
                        if (finish) "Viagem finalizada!" else "Viagem iniciada!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                is ApiResult.Error -> showGeneralError(result.message)
            }
        }
    }

    // ------------------------------------------------------------------
    // UI helpers
    // ------------------------------------------------------------------
    private fun refreshSessionUi() {
        lifecycleScope.launch {
            val session = sessionManager.read()
            val logged = session != null
            binding.loginContainer.visibility = if (logged) android.view.View.GONE else android.view.View.VISIBLE
            binding.dashboardContainer.visibility = if (logged) android.view.View.VISIBLE else android.view.View.GONE
            if (logged) {
                binding.tvUserInfo.text = "${session!!.userName} — ${session.role}"
                binding.tvSubtitle.text = "Conectado em ${AppConfig.BASE_URL}"
            } else {
                binding.tvSubtitle.text = "Login e rastreamento GPS do comprador"
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateTrackingUi() {
        val loc: Location? = TrackingState.lastLocation
        binding.tvLastLocation.text = if (loc != null) {
            String.format(Locale.US, "📍 %.6f, %.6f", loc.latitude, loc.longitude)
        } else {
            getString(R.string.no_location)
        }
        binding.tvLastUpdate.text = if (TrackingState.lastSendAt > 0) {
            val d = java.util.Date(TrackingState.lastSendAt)
            "Último envio: " + java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(d)
        } else {
            "Aguardando primeira posição..."
        }
        binding.tvTrackingStatus.text = if (TrackingState.running) {
            "Status: ● Rastreando (segundo plano ativo)"
        } else {
            "Status: ○ Rastreamento parado"
        }
        binding.btnToggleTracking.text =
            if (TrackingState.running) getString(R.string.tracking_stop) else getString(R.string.tracking_start)
        binding.btnToggleTracking.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (TrackingState.running) getColor(R.color.danger) else getColor(R.color.accent)
        )
    }

    @SuppressLint("SetTextI18n")
    private fun updateTripUi() {
        binding.tvTripStatus.text = if (TrackingState.tripActive) {
            "Status: ● Viagem em andamento"
        } else {
            "Status: ○ Nenhuma viagem ativa"
        }
        binding.btnToggleTrip.text =
            if (TrackingState.tripActive) getString(R.string.trip_finish) else getString(R.string.trip_start)
    }

    private fun showLoginError(msg: String?) {
        binding.tvLoginError.text = msg
        binding.tvLoginError.visibility = if (msg == null) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun showGeneralError(msg: String) {
        binding.tvGeneralError.text = msg
        binding.tvGeneralError.visibility = android.view.View.VISIBLE
    }
}
