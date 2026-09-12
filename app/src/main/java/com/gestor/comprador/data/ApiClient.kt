package com.gestor.comprador.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Resultado de uma chamada de API: sucesso com dados JSON ou erro com mensagem. */
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String, val httpCode: Int = 0) : ApiResult<Nothing>()
}

/**
 * Cliente HTTP para o backend do Gestor (apenas endpoints de rastreamento).
 * O login acontece no site (WebView) — o app é intermediário.
 * Toda chamada é blindada: nunca lança exceção para a UI.
 */
class ApiClient(private val timeoutSeconds: Long = 30) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Envia a posição atual do comprador — POST /api/buyer-tracking (upsert). */
    suspend fun sendPosition(
        token: String,
        userId: String,
        userName: String,
        lat: Double,
        lng: Double,
        status: String,
        locationName: String
    ): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject()
                .put("userId", userId)
                .put("userName", userName)
                .put("latitude", lat)
                .put("longitude", lng)
                .put("currentStatus", status)
                .put("currentLocationName", locationName)
                .put("lastUpdated", java.time.Instant.now().toString())
                .toString()
                .toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("${AppConfig.BASE_URL}/api/buyer-tracking")
                .header("Authorization", "Bearer $token")
                .header("x-auth-token", token)
                .post(body)
                .build()

            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) ApiResult.Success(JSONObject())
                else ApiResult.Error("Falha ao enviar posição (HTTP ${resp.code}).", resp.code)
            }
        } catch (e: Exception) {
            ApiResult.Error("Falha de conexão ao enviar posição: ${e.message ?: ""}")
        }
    }

    /** Registra um evento de posição no histórico — POST /api/buyer-tracking-events. */
    suspend fun sendEvent(
        token: String,
        lat: Double,
        lng: Double,
        status: String,
        locationName: String,
        eventType: String = "position"
    ): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject()
                .put("latitude", lat)
                .put("longitude", lng)
                .put("status", status)
                .put("locationName", locationName)
                .put("eventType", eventType)
                .toString()
                .toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("${AppConfig.BASE_URL}/api/buyer-tracking-events")
                .header("Authorization", "Bearer $token")
                .header("x-auth-token", token)
                .post(body)
                .build()

            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) ApiResult.Success(JSONObject())
                else ApiResult.Error("Falha ao registrar evento (HTTP ${resp.code}).", resp.code)
            }
        } catch (e: Exception) {
            ApiResult.Error("Falha de conexão ao registrar evento: ${e.message ?: ""}")
        }
    }

    /** Inicia ou finaliza uma viagem — POST /api/buyer-trips. */
    suspend fun toggleTrip(
        token: String,
        finish: Boolean
    ): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject()
            if (finish) body.put("finishedAt", java.time.Instant.now().toString())
            val request = Request.Builder()
                .url("${AppConfig.BASE_URL}/api/buyer-trips")
                .header("Authorization", "Bearer $token")
                .header("x-auth-token", token)
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
                if (resp.isSuccessful) ApiResult.Success(json)
                else ApiResult.Error(json.optString("error", "Falha ao alternar viagem (HTTP ${resp.code})."), resp.code)
            }
        } catch (e: Exception) {
            ApiResult.Error("Falha de conexão ao alternar viagem: ${e.message ?: ""}")
        }
    }

    /**
     * Registra o token FCM do aparelho no backend — POST /api/buyer-push/register.
     * Autenticado com o JWT do site (só Comprador é aceito).
     */
    suspend fun registerFcmToken(
        authToken: String,
        fcmToken: String,
        platform: String = "android"
    ): ApiResult<JSONObject> = postAuthorizedJson(
        path = "/api/buyer-push/register",
        authToken = authToken,
        body = JSONObject()
            .put("token", fcmToken)
            .put("platform", platform),
        errorLabel = "registrar o token de notificação",
    )

    /**
     * Remove o token FCM do aparelho (logout) — POST /api/buyer-push/unregister.
     * Sem isso o celular continuaria recebendo pedido do comprador deslogado.
     */
    suspend fun unregisterFcmToken(
        authToken: String,
        fcmToken: String
    ): ApiResult<JSONObject> = postAuthorizedJson(
        path = "/api/buyer-push/unregister",
        authToken = authToken,
        body = JSONObject().put("token", fcmToken),
        errorLabel = "remover o token de notificação",
    )

    private suspend fun postAuthorizedJson(
        path: String,
        authToken: String,
        body: JSONObject,
        errorLabel: String,
    ): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${AppConfig.BASE_URL}$path")
                .header("Authorization", "Bearer $authToken")
                .header("x-auth-token", authToken)
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
                if (resp.isSuccessful) ApiResult.Success(json)
                else ApiResult.Error(json.optString("error", "Falha ao $errorLabel (HTTP ${resp.code})."), resp.code)
            }
        } catch (e: Exception) {
            ApiResult.Error("Falha de conexão ao $errorLabel: ${e.message ?: ""}")
        }
    }
}
