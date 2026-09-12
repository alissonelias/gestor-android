package com.gestor.comprador.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gestor.comprador.MainActivity
import com.gestor.comprador.R

/**
 * Notificação nativa (heads-up) do pedido de compra atribuído.
 *
 * É o app que monta a notificação (não há FCM): o [PushPoller] pega os avisos no
 * backend e chama [show]. O canal é criado cedo — o MainActivity chama
 * [ensureChannel] no boot e o serviço de GPS chama de novo em background.
 */
object PushNotifier {

    const val CHANNEL_ID = "gestor_pedidos"
    private const val NOTIFICATION_ID = 2001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Pedidos de compra",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Avisa quando um pedido de compra é atribuído a você."
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    /** Mostra a notificação; o toque abre o WebView na tela do pedido. */
    fun show(context: Context, title: String, body: String, path: String?, orderId: String?) {
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
            (orderId ?: CHANNEL_ID).hashCode(),
            intent,
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
            .build()

        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Sem POST_NOTIFICATIONS (Android 13+) — nada a fazer aqui.
        }
    }
}
