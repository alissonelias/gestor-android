package com.gestor.comprador.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "session")

/**
 * Persiste a sessão (token JWT + dados do usuário) e, opcionalmente, as
 * credenciais para login automático/preenchido ("Lembrar credenciais").
 * A URL do servidor é fixa (AppConfig.BASE_URL) — não é mais persistida.
 */
class SessionManager(private val context: Context) {

    private object Keys {
        val TOKEN = stringPreferencesKey("token")
        val USER_ID = stringPreferencesKey("user_id")
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_ROLE = stringPreferencesKey("user_role")
        val REMEMBER = booleanPreferencesKey("remember_credentials")
        val SAVED_USERNAME = stringPreferencesKey("saved_username")
        val SAVED_PASSWORD = stringPreferencesKey("saved_password")
    }

    suspend fun saveLogin(
        token: String,
        userId: String,
        userName: String,
        role: String
    ) {
        context.dataStore.edit { prefs ->
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
            token = token,
            userId = userId,
            userName = prefs[Keys.USER_NAME] ?: "",
            role = prefs[Keys.USER_ROLE] ?: "",
        )
    }

    /** Salva/limpa as credenciais preenchidas (checkbox "Lembrar credenciais"). */
    suspend fun saveRememberedCredentials(remember: Boolean, username: String, password: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.REMEMBER] = remember
            if (remember) {
                prefs[Keys.SAVED_USERNAME] = username
                prefs[Keys.SAVED_PASSWORD] = password
            } else {
                prefs.remove(Keys.SAVED_USERNAME)
                prefs.remove(Keys.SAVED_PASSWORD)
            }
        }
    }

    /** Credenciais salvas para pré-preencher a tela de login. */
    suspend fun rememberedCredentials(): RememberedCredentials {
        val prefs = context.dataStore.data.first()
        return RememberedCredentials(
            remember = prefs[Keys.REMEMBER] == true,
            username = prefs[Keys.SAVED_USERNAME] ?: "",
            password = prefs[Keys.SAVED_PASSWORD] ?: "",
        )
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}

data class Session(
    val token: String,
    val userId: String,
    val userName: String,
    val role: String,
)

data class RememberedCredentials(
    val remember: Boolean,
    val username: String,
    val password: String,
)
