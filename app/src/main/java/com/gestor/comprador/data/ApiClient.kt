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

data class LoginData(
    val token: String,
    val id: String,
    val name: String,
    val username: String,
    val role: String,
)

/**
 * Cliente HTTP para o backend do Gestor.
 * Usa OkHttp + org.json (sem dependências pesadas).
 */
class ApiClient(private val timeoutSeconds: Long = 20) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    /** POST /api/login — autentica e retorna o token. */
    suspend fun login(baseUrl: String, username: String, password: String, deviceId: String): ApiResult<LoginData> =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("username", username)
                .put("password", password)
                .put("deviceId", deviceId)
                .put("deviceName", "Android Comprador")
                .toString()
                .toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/login")
                .post(body)
                .build()

            try {
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string() ?: ""
                    val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
                    if (resp.isSuccessful && json.has("token")) {
                        ApiResult.Success(
                            LoginData(
                                token = json.getString("token"),
                                id = json.optString("id", ""),
                                name = json.optString("name", ""),
                                username = json.optString("username", ""),
                                role = json.optString("role", ""),
                            )
                        )
                    } else {
                        ApiResult.Error(json.optString("error", "Falha no login (HTTP ${resp.code})."), resp.code)
                    }
                }
            } catch (e: Exception) {
                ApiResult.Error("Falha de conexão: ${e.message ?: "sem detalhes"}")
            }
        }

    /** Envia a posição atual do comprador — POST /api/buyer-tracking (upsert). */
    suspend fun sendPosition(
        baseUrl: String,
        token: String,
        userId: String,
        userName: String,
        lat: Double,
        lng: Double,
        status: String,
        locationName: String
    ): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
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
            .url("${baseUrl.trimEnd('/')}/api/buyer-tracking")
            .header("Authorization", "Bearer $token")
            .header("x-auth-token", token)
            .post(body)
            .build()

        try {
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
        baseUrl: String,
        token: String,
        lat: Double,
        lng: Double,
        status: String,
        locationName: String,
        eventType: String = "position"
    ): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("latitude", lat)
            .put("longitude", lng)
            .put("status", status)
            .put("locationName", locationName)
            .put("eventType", eventType)
            .toString()
            .toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/buyer-tracking-events")
            .header("Authorization", "Bearer $token")
            .header("x-auth-token", token)
            .post(body)
            .build()

        try {
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
        baseUrl: String,
        token: String,
        finish: Boolean
    ): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        val body = JSONObject()
        if (finish) body.put("finishedAt", java.time.Instant.now().toString())
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/buyer-trips")
            .header("Authorization", "Bearer $token")
            .header("x-auth-token", token)
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        try {
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
}
