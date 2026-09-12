package com.gestor.comprador.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Recebe os eventos do FCM.
 *
 * - `onNewToken`: guarda o token e tenta registrar no backend (se já houver
 *   comprador logado no WebView).
 * - `onMessageReceived`: mostra a notificação quando o app está em primeiro
 *   plano ou quando a mensagem é data-only. Em segundo plano, mensagens com
 *   bloco `notification` são exibidas pelo próprio sistema — o toque nelas cai
 *   no MainActivity com o `path` nos extras.
 */
class GestorMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        if (token.isBlank()) return
        PushTokenStore(this).pendingToken = token
        scope.launch { PushRegistrar.sync(this@GestorMessagingService, tokenOverride = token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val title = message.notification?.title ?: data["title"] ?: "Pedido de compra"
        val body = message.notification?.body ?: data["body"] ?: ""
        PushNotifier.show(
            context = this,
            title = title,
            body = body,
            path = data["path"],
            orderId = data["orderId"],
        )
    }
}
