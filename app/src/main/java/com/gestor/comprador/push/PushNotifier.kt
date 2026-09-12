package com.gestor.comprador.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gestor.comprador.MainActivity
import com.gestor.comprador.R

/**
 * Notificação nativa (heads-up) do pedido de compra atribuído / peça nova.
 *
 * É o app que monta a notificação (não há FCM): o [PushPoller] pega os avisos no
 * backend e chama [show]. O canal é criado cedo — o MainActivity chama
 * [ensureChannel] no boot e o serviço de GPS chama de novo em background.
 *
 * Vibração: o comprador costuma estar dirigindo/na rua, então o aviso **vibra
 * repetidamente** até alguém atender (abrir a tela do pedido) ou dispensar a
 * notificação. A repetição é feita por bursts re-agendados (e não por um padrão
 * de repetição infinita do sistema): se o processo morrer, no máximo o burst
 * atual termina — nunca fica vibrando para sempre.
 */
object PushNotifier {

    /**
     * Canal novo: padrão de vibração não pode ser alterado em canal já criado no
     * aparelho, então o canal antigo (default) é removido ao subir.
     */
    const val CHANNEL_ID = "gestor_pedidos_v2"
    private const val LEGACY_CHANNEL_ID = "gestor_pedidos"
    private const val NOTIFICATION_ID_BASE = 2000

    /** Um burst: vibra forte, pausa e vibra de novo (~3s no total). */
    private val VIBRATION_BURST = longArrayOf(0, 900, 350, 900, 350, 800)

    /** Re-agenda o próximo burst logo depois do anterior terminar. */
    private const val VIBRATION_BURST_MS = 3_400L

    /** Teto de segurança: depois disso a vibração para sozinha (evita drenar bateria). */
    private const val VIBRATION_MAX_MS = 90_000L

    private val handler = Handler(Looper.getMainLooper())
    private var vibrating = false
    private var vibrationStartedAt = 0L

    private val vibrationTick = object : Runnable {
        override fun run() {
            if (!vibrating) return
            if (SystemClock.elapsedRealtime() - vibrationStartedAt >= VIBRATION_MAX_MS) {
                stopVibration()
                return
            }
            val vibrator = vibrator() ?: return
            try {
                vibrator.cancel()
                vibrator.vibrate(VibrationEffect.createWaveform(VIBRATION_BURST, -1))
            } catch (e: Exception) {
                // Aparelho sem vibrador ou política do SO bloqueou — segue só o som.
                return
            }
            handler.postDelayed(this, VIBRATION_BURST_MS)
        }
    }

    fun ensureChannel(context: Context) {
        appContext = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Pedidos de compra",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Avisa quando um pedido de compra é atribuído a você."
            enableVibration(true)
            vibrationPattern = VIBRATION_BURST
            enableLights(true)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Mostra a notificação; o toque abre o WebView na tela do pedido.
     *
     * @param pushId id da linha na fila — vira o id da notificação, para que
     *   vários avisos recebidos juntos apareçam separados (não se sobrescrevam).
     */
    fun show(
        context: Context,
        pushId: Long,
        title: String,
        body: String,
        path: String?,
        orderId: String?,
    ) {
        ensureChannel(context)
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (!path.isNullOrBlank()) putExtra(MainActivity.EXTRA_PUSH_PATH, path)
            if (!orderId.isNullOrBlank()) putExtra(MainActivity.EXTRA_PUSH_ORDER_ID, orderId)
        }
        val pending = PendingIntent.getActivity(
            context,
            notificationId(pushId, orderId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Deslizar/dispensar o aviso também para a vibração.
        val dismissIntent = Intent(context, PushDismissReceiver::class.java)
        val dismissPending = PendingIntent.getBroadcast(
            context,
            notificationId(pushId, orderId),
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pending)
            .setDeleteIntent(dismissPending)
            .setVibrate(VIBRATION_BURST)
            .build()

        try {
            manager.notify(notificationId(pushId, orderId), notification)
        } catch (e: SecurityException) {
            // Sem POST_NOTIFICATIONS (Android 13+) — nada a fazer aqui.
            return
        }

        startVibration()
    }

    /** Dispara a vibração repetida (ignora chamadas repetidas do mesmo lote). */
    private fun startVibration() {
        if (vibrating) return
        if (!channelAllowsVibration()) return // usuário silenciou o canal no sistema
        vibrating = true
        vibrationStartedAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(vibrationTick)
        handler.post(vibrationTick)
    }

    /** Respeita a configuração do canal (se o comprador desligou a vibração, nada). */
    private fun channelAllowsVibration(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val ctx = appContext ?: return true
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return true
        return manager.getNotificationChannel(CHANNEL_ID)?.shouldVibrate() ?: true
    }

    /** Para a vibração — atendida a notificação (tap) ou dispensada. */
    fun stopVibration() {
        if (!vibrating) return
        vibrating = false
        handler.removeCallbacks(vibrationTick)
        try {
            vibrator()?.cancel()
        } catch (e: Exception) {
            // ignora
        }
    }

    private fun notificationId(pushId: Long, orderId: String?): Int {
        val key = if (pushId > 0) pushId else (orderId ?: "").hashCode().toLong()
        return (NOTIFICATION_ID_BASE + (key % 100_000L)).toInt()
    }

    /**
     * `PushNotifier` é um object sem Context: guarda o applicationContext da
     * última chamada ([ensureChannel]/[show]) só para acionar o vibrador.
     */
    @Volatile
    private var appContext: Context? = null

    @Suppress("DEPRECATION") // VIBRATOR_SERVICE só é usado abaixo do Android 12 (S)
    private fun vibrator(): Vibrator? {
        val ctx = appContext ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
