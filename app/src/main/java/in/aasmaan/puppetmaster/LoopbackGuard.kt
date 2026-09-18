package `in`.aasmaan.puppetmaster

import android.net.Uri

/**
 * Enforces strict loopback-only constraints.
 * Refuses all external domains and constructs authenticated loopback endpoints.
 */
object LoopbackGuard {

    private val ALLOWED_HOSTS = setOf(
        "127.0.0.1",
        "localhost",
        "::1",
        "[::1]"
    )

    /**
     * Verifies that the given URI belongs strictly to a loopback origin.
     */
    fun isLoopback(uri: Uri?): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        return host in ALLOWED_HOSTS
    }

    /**
     * Checks if a URL string points to loopback.
     */
    fun isLoopback(urlString: String?): Boolean {
        if (urlString.isNullOrBlank()) return false
        return try {
            val uri = Uri.parse(urlString)
            isLoopback(uri)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Constructs a safe loopback URL with optional ?t= query parameter.
     */
    fun buildLoopbackUrl(port: Int, path: String = "/", token: String = ""): String {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        val base = "http://127.0.0.1:$port$cleanPath"
        if (token.isBlank()) return base
        val separator = if (base.contains("?")) "&" else "?"
        return "$base${separator}t=${Uri.encode(token)}"
    }

    /**
     * Returns the Authorization header map for requests to loopback.
     */
    fun getAuthHeaders(token: String): Map<String, String> {
        return if (token.isNotBlank()) {
            mapOf("Authorization" to "Bearer $token")
        } else {
            emptyMap()
        }
    }
}
