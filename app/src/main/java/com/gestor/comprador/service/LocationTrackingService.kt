package com.gestor.comprador.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.gestor.comprador.MainActivity
import com.gestor.comprador.R
import com.gestor.comprador.data.ApiClient
import com.gestor.comprador.data.ApiResult
import com.gestor.comprador.data.Session
import com.gestor.comprador.data.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground Service que mantém o GPS ativo em segundo plano e envia a posição
 * para o backend periodicamente.
 *
 * - Obtém localização via LocationManager (GPS + rede).
 * - Envia para /api/buyer-tracking (posição atual) a cada INTERVAL_MS.
 * - Envia um evento para /api/buyer-tracking-events em cada atualização
 *   significativa de posição.
 */
class LocationTrackingService : Service(), LocationListener {

    companion object {
        const val ACTION_START = "com.gestor.comprador.action.START"
        const val ACTION_STOP = "com.gestor.comprador.action.STOP"
        private const val CHANNEL_ID = "gestor_location"
        private const val NOTIFICATION_ID = 1001

        /** Intervalo de envio ao backend (ms). */
        const val SEND_INTERVAL_MS = 30_000L
        /** Distância mínima (metros) para registrar um novo evento. */
        const val MIN_EVENT_DISTANCE_M = 50f
        /** Precisão mínima aceita (metros). */
        private const val MIN_ACCURACY_M = 150f
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sendJob: Job? = null
    private var locationManager: LocationManager? = null
    private var session: Session? = null
    private var lastEventLocation: Location? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startTracking()
        }
        return START_STICKY
    }

    private fun startTracking() {
        startForegroundCompat()

        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = lm
        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                5000L,
                5f,
                this
            )
        } catch (e: SecurityException) {
            // sem permissão — a UI já pediu antes de iniciar
        }
        try {
            lm.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                5000L,
                5f,
                this
            )
        } catch (e: SecurityException) {
            // ignora
        }
        TrackingState.running = true

        sendJob?.cancel()
        sendJob = scope.launch {
            // Lê a sessão (DataStore) dentro da coroutine — função suspend.
            session = SessionManager(this@LocationTrackingService).read()
            while (isActive) {
                sendCurrentPosition()
                delay(SEND_INTERVAL_MS)
            }
        }
    }

    private fun startForegroundCompat() {
        val channelId = createChannel()
        val notification = buildNotification(channelId, "Rastreamento ativo")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel(): String {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Rastreamento de Localização",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mantém o GPS ativo para persistir a localização do comprador."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
        return CHANNEL_ID
    }

    private fun buildNotification(channelId: String, text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Gestor Comprador")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(CHANNEL_ID, text))
    }

    /** Envia a posição mais recente para o backend. */
    private suspend fun sendCurrentPosition() {
        val s = session ?: return
        val loc = TrackingState.lastLocation ?: return

        val result = ApiClient().sendPosition(
            baseUrl = s.serverUrl,
            token = s.token,
            userId = s.userId,
            userName = s.userName,
            lat = loc.latitude,
            lng = loc.longitude,
            status = if (TrackingState.tripActive) "Em Viagem" else "Disponível",
            locationName = "",
        )
        TrackingState.lastSendAt = System.currentTimeMillis()

        if (result is ApiResult.Error) {
            updateNotification("Falha ao enviar posição — tentando novamente...")
        } else {
            updateNotification("Rastreamento ativo — ${loc.latitude}, ${loc.longitude}")
        }
    }

    /** Registra um evento quando houve movimento significativo. */
    private suspend fun sendEventIfMoved(loc: Location) {
        val s = session ?: return
        val prev = lastEventLocation
        if (prev != null) {
            val dist = prev.distanceTo(loc)
            if (dist < MIN_EVENT_DISTANCE_M) return
        }
        lastEventLocation = loc
        ApiClient().sendEvent(
            baseUrl = s.serverUrl,
            token = s.token,
            lat = loc.latitude,
            lng = loc.longitude,
            status = if (TrackingState.tripActive) "Em Viagem" else "Disponível",
            locationName = "",
        )
    }

    override fun onLocationChanged(location: Location) {
        if (location.accuracy <= MIN_ACCURACY_M || location.accuracy == 0f) {
            TrackingState.lastLocation = location
            // Registra evento no histórico (dispara e esquece).
            scope.launch { sendEventIfMoved(location) }
        }
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun stopTracking() {
        sendJob?.cancel()
        sendJob = null
        locationManager?.removeUpdates(this)
        locationManager = null
        TrackingState.running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopTracking()
        super.onDestroy()
    }
}
