package `in`.aasmaan.puppetmaster

enum class ConnectionStatus {
    CONNECTING,
    CONNECTED,
    OFFLINE
}

data class PuppetMasterUiState(
    val port: Int = TokenStore.DEFAULT_PORT,
    val hasToken: Boolean = false,
    val maskedToken: String = "",
    val connectionStatus: ConnectionStatus = ConnectionStatus.CONNECTING,
    val progress: Int = 0,
    val currentPath: String = "/",
    val statusMessage: String = "",
    val showConfigDialog: Boolean = false,
    val termuxStatusMessage: String? = null
)
