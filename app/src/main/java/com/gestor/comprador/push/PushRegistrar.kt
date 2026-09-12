package com.gestor.comprador.push

import android.content.Context
import android.util.Log
import com.gestor.comprador.data.ApiClient
import com.gestor.comprador.data.ApiResult
import com.gestor.comprador.data.Session
import com.gestor.comprador.data.SessionManager
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Envia o token FCM do aparelho para o backend do gestor, autenticado com o JWT
 * do site (o mesmo que o WebView guarda no localStorage).
 *
 * O backend só aceita Comprador e guarda o token como dono do comprador logado.
 */
object PushRegistrar {

    private const val TAG = "PushRegistrar"

    /**
     * Sincroniza o token do aparelho com o comprador logado.
     * @param session sessão do site (lida do [SessionManager] se não informada).
     * @param tokenOverride token recém-recebido pelo `onNewToken`.
     */
    suspend fun sync(context: Context, session: Session? = null, tokenOverride: String? = null) {
        val app = context.applicationContext
        if (!FirebaseConfig.ensureInitialized(app)) return

        val current = session ?: SessionManager(app).read() ?: return
        if (current.token.isBlank() || current.userId.isBlank()) return

        val store = PushTokenStore(app)
        val fcmToken = tokenOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: resolveToken()
            // Sem rede/Firebase na hora, ainda dá para usar o último token conhecido.
            ?: store.pendingToken?.trim()?.takeIf { it.isNotEmpty() }
            ?: return

        store.pendingToken = fcmToken

        // Já registrado para este comprador: o poll de sessão roda a cada 3s.
        if (store.registeredToken == fcmToken && store.registeredUserId == current.userId) return

        when (val result = ApiClient().registerFcmToken(current.token, fcmToken)) {
            is ApiResult.Success -> store.markRegistered(fcmToken, current.userId)
            is ApiResult.Error -> Log.w(TAG, "Falha ao registrar token FCM: ${result.message}")
        }
    }

    /** Remove o token no logout para o celular parar de receber pedido do usuário que saiu. */
    suspend fun unregister(context: Context, session: Session) {
        if (session.token.isBlank()) return
        val app = context.applicationContext
        val store = PushTokenStore(app)
        val fcmToken = (store.registeredToken ?: store.pendingToken)?.trim().orEmpty()
        if (fcmToken.isEmpty()) return

        when (val result = ApiClient().unregisterFcmToken(session.token, fcmToken)) {
            is ApiResult.Success -> store.clearRegistration()
            is ApiResult.Error -> Log.w(TAG, "Falha ao remover token FCM: ${result.message}")
        }
    }

    /** Busca o token no Firebase (bloqueante, sempre fora da main thread). */
    private suspend fun resolveToken(): String? = withContext(Dispatchers.IO) {
        try {
            Tasks.await(FirebaseMessaging.getInstance().token, 20, TimeUnit.SECONDS)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível obter o token FCM: ${e.message ?: ""}")
            null
        }
    }
}
