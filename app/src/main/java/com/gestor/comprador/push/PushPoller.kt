package com.gestor.comprador.push

import android.content.Context
import android.util.Log
import com.gestor.comprador.data.ApiClient
import com.gestor.comprador.data.ApiResult
import com.gestor.comprador.data.Session

/**
 * Busca os avisos de pedido atribuído no backend e mostra a notificação nativa.
 *
 * Sem Firebase: o transporte é o Foreground Service de GPS que já fica vivo em
 * segundo plano (com JWT do site e isento de otimização de bateria). O backend
 * entrega cada aviso uma única vez, então não há risco de notificação repetida.
 */
object PushPoller {

    private const val TAG = "PushPoller"

    /**
     * Consulta e exibe os avisos pendentes do comprador logado.
     * @return quantas notificações foram mostradas.
     */
    suspend fun poll(context: Context, session: Session): Int {
        if (session.token.isBlank() || session.userId.isBlank()) return 0

        val result = ApiClient().fetchPendingNotifications(session.token)
        val notifications = when (result) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> {
                Log.w(TAG, "Falha ao buscar avisos: ${result.message}")
                return 0
            }
        }

        val app = context.applicationContext
        var shown = 0
        for (notification in notifications) {
            PushNotifier.show(
                context = app,
                pushId = notification.id,
                title = notification.title,
                body = notification.body,
                path = notification.path,
                orderId = notification.orderId,
            )
            shown++
        }
        return shown
    }
}
