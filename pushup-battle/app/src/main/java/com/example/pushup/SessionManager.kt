package com.example.pushup

import android.content.Context

/**
 * Giriş bilgisini cihazda saklar, uygulama kapanıp açılınca tekrar giriş yapmaya gerek
 * kalmasın diye. Gerçek doğrulama yine de her açılışta AuthClient.validateSession ile
 * sunucudan (Firestore) teyit edilir - başka cihazdan giriş yapıldıysa burada eski bilgi
 * kalsa bile açılışta otomatik olarak çıkış yaptırılır.
 */
class SessionManager(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("pushup_session", Context.MODE_PRIVATE)

    fun save(session: AuthSession) {
        prefs.edit()
            .putString(KEY_USERNAME, session.username)
            .putString(KEY_DISPLAY_NAME, session.displayName)
            .putString(KEY_TOKEN, session.sessionToken)
            .putString(KEY_PHOTO, session.photoBase64 ?: "")
            .apply()
    }

    fun load(): AuthSession? {
        val username = prefs.getString(KEY_USERNAME, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        val displayName = prefs.getString(KEY_DISPLAY_NAME, username) ?: username
        val photo = prefs.getString(KEY_PHOTO, null)?.ifBlank { null }
        return AuthSession(username, displayName, token, photo)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_USERNAME = "username"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_TOKEN = "session_token"
        const val KEY_PHOTO = "photo_b64"
    }
}
