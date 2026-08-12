package com.gestor.comprador.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "session")

/**
 * Guarda a sessão extraída do site via WebView/JavaScript (token JWT + usuário).
 * O login e a gestão acontecem no próprio site — o app é apenas intermediário.
 */
class SessionManager(private val context: Context) {

    private object Keys {
        val TOKEN = stringPreferencesKey("token")
        val USER_ID = stringPreferencesKey("user_id")
        val USER_NAME = stringPreferencesKey("user_name")
    }

    /** Atualiza a sessão quando o site detecta login/logout. */
    suspend fun saveSession(token: String?, userId: String?, userName: String?) {
        context.dataStore.edit { prefs ->
            if (token.isNullOrBlank()) {
                prefs.remove(Keys.TOKEN)
                prefs.remove(Keys.USER_ID)
                prefs.remove(Keys.USER_NAME)
            } else {
                prefs[Keys.TOKEN] = token
                prefs[Keys.USER_ID] = userId ?: ""
                prefs[Keys.USER_NAME] = userName ?: ""
            }
        }
    }

    suspend fun read(): Session? {
        val prefs = context.dataStore.data.first()
        val token = prefs[Keys.TOKEN]?.takeIf { it.isNotBlank() } ?: return null
        return Session(
            token = token,
            userId = prefs[Keys.USER_ID] ?: "",
            userName = prefs[Keys.USER_NAME] ?: "",
        )
    }
}

data class Session(
    val token: String,
    val userId: String,
    val userName: String,
)
