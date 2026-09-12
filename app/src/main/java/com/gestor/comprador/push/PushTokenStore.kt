package com.gestor.comprador.push

import android.content.Context

/**
 * Guarda o token FCM atual do aparelho e para qual comprador ele já foi
 * registrado no backend — evita POST repetido a cada poll de sessão (o site é
 * consultado a cada 3s pelo WebView).
 */
class PushTokenStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("fcm_push", Context.MODE_PRIVATE)

    /** Token recebido pelo onNewToken e ainda pendente de registro. */
    var pendingToken: String?
        get() = prefs.getString(KEY_PENDING, null)
        set(value) = prefs.edit().putString(KEY_PENDING, value).apply()

    var registeredToken: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var registeredUserId: String?
        get() = prefs.getString(KEY_USER, null)
        set(value) = prefs.edit().putString(KEY_USER, value).apply()

    fun markRegistered(token: String, userId: String) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USER, userId)
            .putString(KEY_PENDING, token)
            .apply()
    }

    fun clearRegistration() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER)
            .apply()
    }

    private companion object {
        const val KEY_PENDING = "pending_token"
        const val KEY_TOKEN = "registered_token"
        const val KEY_USER = "registered_user_id"
    }
}
