package com.gestor.comprador.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Recebe o `deleteIntent` da notificação de pedido (o usuário deslizou/dispensou)
 * e interrompe a vibração persistente — ela só para quando o aviso é atendido.
 */
class PushDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        PushNotifier.stopVibration()
    }
}
