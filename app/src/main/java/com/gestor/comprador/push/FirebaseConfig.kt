package com.gestor.comprador.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.gestor.comprador.BuildConfig

/**
 * Credenciais do projeto Firebase do app.
 *
 * Vêm de `local.properties` (ou variáveis de ambiente) e entram no build via
 * `BuildConfig` — assim o repositório não guarda nenhum segredo e não é preciso
 * o arquivo `google-services.json` (o FirebaseApp é inicializado à mão aqui).
 *
 * Sem as quatro chaves preenchidas, o app compila e o GPS funciona igual; o que
 * não acontece é o registro do token de notificação.
 */
object FirebaseConfig {

    val apiKey: String = BuildConfig.FIREBASE_API_KEY.trim()
    val appId: String = BuildConfig.FIREBASE_APP_ID.trim()
    val projectId: String = BuildConfig.FIREBASE_PROJECT_ID.trim()
    val senderId: String = BuildConfig.FIREBASE_SENDER_ID.trim()

    /** As quatro chaves são obrigatórias para o FCM funcionar. */
    val isConfigured: Boolean
        get() = apiKey.isNotEmpty() && appId.isNotEmpty() && projectId.isNotEmpty() && senderId.isNotEmpty()

    /**
     * Garante um FirebaseApp inicializado com as credenciais do build.
     * @return true se o FCM está pronto para uso neste aparelho.
     */
    fun ensureInitialized(context: Context): Boolean {
        if (!isConfigured) return false
        return try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setApiKey(apiKey)
                    .setApplicationId(appId)
                    .setProjectId(projectId)
                    .setGcmSenderId(senderId)
                    .build()
                FirebaseApp.initializeApp(context, options)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
