package com.gestor.comprador

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gestor.comprador.data.AppConfig
import com.gestor.comprador.data.Session
import com.gestor.comprador.data.SessionManager
import com.gestor.comprador.databinding.ActivityMainBinding
import com.gestor.comprador.push.FirebaseConfig
import com.gestor.comprador.push.PushNotifier
import com.gestor.comprador.push.PushRegistrar
import com.gestor.comprador.service.LocationTrackingService
import com.gestor.comprador.service.TrackingState
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * O app é um WebView do site do comprador (dashboard completa). O usuário faz
 * login no próprio site; o app cuida apenas do GPS em segundo plano.
 *
 * Um bridge JavaScript lê o token e o usuário do localStorage do site
 * (assiscare_token / assiscare_user) e alimenta o SessionManager, que o
 * LocationTrackingService usa para persistir a posição.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** Extras usados pela notificação criada pelo próprio app (data-only). */
        const val EXTRA_PUSH_PATH = "com.gestor.comprador.extra.PUSH_PATH"
        const val EXTRA_PUSH_ORDER_ID = "com.gestor.comprador.extra.PUSH_ORDER_ID"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var sessionManager: SessionManager
    private val uiHandler = Handler(Looper.getMainLooper())

    /** Poll de sessão do site (o login pode ocorrer a qualquer momento). */
    private var sessionPollRunnable: Runnable? = null
    private var lastToken: String? = null
    /** token + userId: também dispara quando o comprador troca no mesmo aparelho. */
    private var lastSessionKey: String? = null
    private var currentSession: Session? = null

    /** Tela do pedido vinda de uma notificação (ex.: /?tab=compras&orderId=...). */
    private var pendingPushPath: String? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val fine = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
            val coarse = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (fine || coarse) {
                maybeAskBatteryOptimization()
                startTrackingService()
                binding.permissionOverlay.visibility = android.view.View.GONE
            } else {
                Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)

        // FCM: canal criado já no boot para o push do pedido aparecer como
        // heads-up; credenciais ausentes apenas desligam o push (GPS segue igual).
        PushNotifier.ensureChannel(this)
        if (!FirebaseConfig.isConfigured) {
            Log.w("MainActivity", "FCM não configurado — o app segue sem notificações.")
        }
        pendingPushPath = extractPushPath(intent)

        setupWebView()
        binding.btnGrantPermission.setOnClickListener { requestPermissionsAndStart() }

        // Mostra o overlay de permissão só na primeira execução (sem permissão ainda).
        if (hasLocationPermission()) {
            binding.permissionOverlay.visibility = android.view.View.GONE
            maybeAskBatteryOptimization()
            startTrackingService()
        } else {
            binding.permissionOverlay.visibility = android.view.View.VISIBLE
            binding.tvGpsStatus.text = getString(R.string.tracking_off)
        }
    }

    override fun onResume() {
        super.onResume()
        updateGpsStatusUi()
    }

    /**
     * Toque na notificação com o app já aberto: navega a WebView para o pedido.
     * (Em background/cold start o caminho chega pelo `onCreate`.)
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val path = extractPushPath(intent)
        if (path != null) {
            pendingPushPath = path
            if (::binding.isInitialized) {
                binding.webView.loadUrl(buildWebUrl(path))
            }
        }
    }

    override fun onDestroy() {
        sessionPollRunnable?.let { uiHandler.removeCallbacks(it) }
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Deep link do push
    // ------------------------------------------------------------------

    /**
     * Lê o caminho da tela a partir do intent. Cobre os dois formatos:
     * - notificação do próprio app (extra [EXTRA_PUSH_PATH]);
     * - notificação exibida pelo sistema, que copia as chaves do `data` do FCM
     *   para os extras do intent (chave "path").
     */
    private fun extractPushPath(intent: Intent?): String? {
        if (intent == null) return null
        val raw = intent.getStringExtra(EXTRA_PUSH_PATH) ?: intent.getStringExtra("path")
        return sanitizePushPath(raw)
    }

    /** Aceita só caminho relativo do próprio site (nunca URL absoluta/outro host). */
    private fun sanitizePushPath(raw: String?): String? {
        val path = raw?.trim().orEmpty()
        if (path.isEmpty()) return null
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("://")) return null
        return path
    }

    private fun buildWebUrl(path: String?): String = AppConfig.BASE_URL + (path ?: "/")

    // ------------------------------------------------------------------
    // WebView
    // ------------------------------------------------------------------
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
        }
        // Mantém a sessão do site (cookies + localStorage) entre aberturas.
        webView.settings.domStorageEnabled = true

        webView.addJavascriptInterface(SiteBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Mantém tudo dentro da WebView; abre links externos no browser.
                val url = request.url.toString()
                if (url.startsWith(AppConfig.BASE_URL)) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) { /* ignora */ }
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // Câmera/mic do site (usados pelo comprador no checkout/assinatura).
                request.grant(request.resources)
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback
            ) {
                // Concede automaticamente a geolocalização ao site: o app já
                // solicitou a permissão de localização ao SO na primeira execução.
                // Sem isso, o navigator.geolocation do site fica bloqueado.
                callback.invoke(origin, true, false)
            }
        }

        // Carrega o site do comprador (ou direto a tela do pedido, se veio de push).
        webView.loadUrl(buildWebUrl(pendingPushPath))
        startSessionPolling()
    }

    /**
     * Injeta periodicamente um JS que lê o token/usuário do localStorage do site
     * e repassa à bridge — cobre login/logout a qualquer momento.
     */
    private fun startSessionPolling() {
        sessionPollRunnable?.let { uiHandler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                val webView = binding.webView
                val js = """
                    (function() {
                        var t = localStorage.getItem('assiscare_token');
                        var u = null;
                        try { u = JSON.parse(localStorage.getItem('assiscare_user') || 'null'); } catch(e) {}
                        AndroidBridge.onSession(t || '', u && u.id ? u.id : '', u && u.name ? u.name : '');
                    })();
                """.trimIndent()
                webView.evaluateJavascript(js, null)
                uiHandler.postDelayed(this, 3000L)
            }
        }
        sessionPollRunnable = runnable
        uiHandler.post(runnable)
    }

    /** Bridge recebe a sessão do site (chamada pelo JavaScript injetado). */
    inner class SiteBridge {
        @JavascriptInterface
        fun onSession(token: String, userId: String, userName: String) {
            // token + userId: cobre login, logout e troca de comprador no aparelho.
            val sessionKey = "$token|$userId"
            if (sessionKey == lastSessionKey) return
            val previousSession = currentSession
            lastSessionKey = sessionKey
            lastToken = token
            currentSession = if (token.isBlank()) null else Session(token, userId, userName)

            lifecycleScope.launch {
                sessionManager.saveSession(
                    token = token,
                    userId = userId,
                    userName = userName,
                )
                updateGpsStatusUi()

                if (token.isBlank()) {
                    // Logout: o aparelho não deve mais receber pedido deste comprador.
                    previousSession?.let { PushRegistrar.unregister(this@MainActivity, it) }
                } else {
                    // Logado: registra/atualiza o token FCM do aparelho no backend.
                    currentSession?.let { PushRegistrar.sync(this@MainActivity, session = it) }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Permissões e GPS
    // ------------------------------------------------------------------
    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

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
            binding.permissionOverlay.visibility = android.view.View.GONE
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
        updateGpsStatusUi()
    }

    private fun stopTrackingService() {
        val intent = Intent(this, LocationTrackingService::class.java)
            .setAction(LocationTrackingService.ACTION_STOP)
        startService(intent)
        updateGpsStatusUi()
    }

    @SuppressLint("SetTextI18n")
    private fun updateGpsStatusUi() {
        val logged = !lastToken.isNullOrBlank()
        binding.tvGpsStatus.text = when {
            !TrackingState.running -> getString(R.string.tracking_off)
            !logged -> "Rastreando… aguardando login no site"
            else -> getString(R.string.tracking_on)
        }
    }
}
