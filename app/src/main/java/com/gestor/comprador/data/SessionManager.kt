package com.gestor.comprador.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "session")

/**
 * Persiste a sessão (token JWT + dados do usuário) e a URL do servidor.
 * O token expira em 24h no backend; o app guarda para reutilizar.
 */
class SessionManager(private val context: Context) {

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val TOKEN = stringPreferencesKey("token")
        val USER_ID = stringPreferencesKey("user_id")
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_ROLE = stringPreferencesKey("user_role")
    }

    val serverUrl: Flow<String?> = context.dataStore.data.map { it[Keys.SERVER_URL] }
    val token: Flow<String?> = context.dataStore.data.map { it[Keys.TOKEN] }

    suspend fun saveLogin(
        serverUrl: String,
        token: String,
        userId: String,
        userName: String,
        role: String
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SERVER_URL] = serverUrl.trim().trimEnd('/')
            prefs[Keys.TOKEN] = token
            prefs[Keys.USER_ID] = userId
            prefs[Keys.USER_NAME] = userName
            prefs[Keys.USER_ROLE] = role
        }
    }

    suspend fun read(): Session? {
        val prefs = context.dataStore.data.first()
        val token = prefs[Keys.TOKEN] ?: return null
        val userId = prefs[Keys.USER_ID] ?: return null
        return Session(
            serverUrl = prefs[Keys.SERVER_URL] ?: "",
            token = token,
            userId = userId,
            userName = prefs[Keys.USER_NAME] ?: "",
            role = prefs[Keys.USER_ROLE] ?: "",
        )
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}

data class Session(
    val serverUrl: String,
    val token: String,
    val userId: String,
    val userName: String,
    val role: String,
)
