package `in`.aasmaan.puppetmaster

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage for loopback connection configuration.
 * Stores the engine port and pairing token (encrypted via Android Keystore).
 * Tokens are never logged to Logcat and never shown in full.
 */
class TokenStore(context: Context) {

    private val sharedPreferences: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        sharedPreferences = EncryptedSharedPreferences.create(
            context,
            PREFS_FILENAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var port: Int
        get() = sharedPreferences.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) {
            val validPort = if (value in 1..65535) value else DEFAULT_PORT
            sharedPreferences.edit().putInt(KEY_PORT, validPort).apply()
        }

    var token: String
        get() = sharedPreferences.getString(KEY_TOKEN, "") ?: ""
        set(value) = sharedPreferences.edit().putString(KEY_TOKEN, value.trim()).apply()

    fun clearToken() {
        sharedPreferences.edit().remove(KEY_TOKEN).apply()
    }

    fun hasToken(): Boolean = token.isNotEmpty()

    /**
     * Safe masked representation for UI display only (never reveals entire secret).
     */
    fun getMaskedToken(): String {
        val tok = token
        if (tok.isEmpty()) return ""
        if (tok.length <= 6) return "••••••"
        return tok.take(3) + "••••••••" + tok.takeLast(3)
    }

    companion object {
        private const val PREFS_FILENAME = "puppetmaster_secure_prefs"
        private const val KEY_PORT = "engine_port"
        private const val KEY_TOKEN = "pairing_token"
        const val DEFAULT_PORT = 8765
    }
}
