package `in`.aasmaan.puppetmaster

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

private val DarkBackground = Color(0xFF0B0E14)
private val DarkCard = Color(0xFF151A24)
private val DarkLine = Color(0xFF232A38)
private val DarkInk = Color(0xFFE6EDF6)
private val DarkDim = Color(0xFF8B97A8)
private val DarkAcc = Color(0xFF6EA8FE)
private val DarkOk = Color(0xFF3FB950)
private val DarkWarn = Color(0xFFD29922)
private val DarkBad = Color(0xFFF85149)

class MainActivity : ComponentActivity() {

    private lateinit var tokenStore: TokenStore
    private var webViewInstance: WebView? = null
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenStore = TokenStore(this)

        setContent {
            PuppetMasterApp()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PuppetMasterApp() {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }

        var currentPort by remember { mutableIntStateOf(tokenStore.port) }
        var hasToken by remember { mutableStateOf(tokenStore.hasToken()) }
        var connectionStatus by remember { mutableStateOf(ConnectionStatus.CONNECTING) }
        var pageProgress by remember { mutableIntStateOf(0) }
        var showConfigDialog by remember { mutableStateOf(false) }

        // Camera permission launcher for photo capture in web file chooser
        val cameraPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { _ -> }

        // File chooser launcher
        val fileChooserLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (fileUploadCallback == null) return@rememberLauncherForActivityResult
            val results: Array<Uri>? = if (result.resultCode == RESULT_OK && result.data != null) {
                val data = result.data
                val clipData = data?.clipData
                when {
                    clipData != null -> Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                    data?.data != null -> arrayOf(data.data!!)
                    else -> null
                }
            } else {
                null
            }
            fileUploadCallback?.onReceiveValue(results)
            fileUploadCallback = null
        }

        // Handle hardware back press
        BackHandler {
            if (webViewInstance?.canGoBack() == true && connectionStatus == ConnectionStatus.CONNECTED) {
                webViewInstance?.goBack()
            } else {
                finish()
            }
        }

        fun reloadEngine(path: String = "/") {
            connectionStatus = ConnectionStatus.CONNECTING
            pageProgress = 10
            val targetUrl = LoopbackGuard.buildLoopbackUrl(currentPort, path, tokenStore.token)
            val authHeaders = LoopbackGuard.getAuthHeaders(tokenStore.token)
            if (authHeaders.isNotEmpty()) {
                webViewInstance?.loadUrl(targetUrl, authHeaders)
            } else {
                webViewInstance?.loadUrl(targetUrl)
            }
        }

        fun handleStartTermux() {
            if (!TermuxBridge.isTermuxInstalled(context)) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Termux not installed. Opening F-Droid...")
                }
                TermuxBridge.openFdroidTermux(context)
                return
            }

            val started = TermuxBridge.startAiServeInTermux(context)
            if (started) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Command \"ai serve\" dispatched to Termux. Reconnecting in 3s...")
                }
                connectionStatus = ConnectionStatus.CONNECTING
                mainHandler.postDelayed({
                    reloadEngine()
                }, 3000)
            } else {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Could not send command to Termux. Please launch Termux manually.")
                }
            }
        }

        MaterialTheme(
            colorScheme = darkColorScheme(
                background = DarkBackground,
                surface = DarkCard,
                onBackground = DarkInk,
                onSurface = DarkInk,
                primary = DarkAcc,
                onPrimary = DarkBackground
            )
        ) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = DarkBackground,
                            titleContentColor = DarkInk,
                            actionIconContentColor = DarkInk
                        ),
                        title = {
                            Column {
                                Text(
                                    text = "✦ Puppet Master",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DarkInk
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    val (statusColor, statusText) = when (connectionStatus) {
                                        ConnectionStatus.CONNECTED -> DarkOk to "127.0.0.1:$currentPort (Active)"
                                        ConnectionStatus.CONNECTING -> DarkWarn to "127.0.0.1:$currentPort (Connecting…)"
                                        ConnectionStatus.OFFLINE -> DarkBad to "127.0.0.1:$currentPort (Offline)"
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .background(statusColor, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = statusText,
                                        fontSize = 12.sp,
                                        color = DarkDim
                                    )
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = { reloadEngine() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh Engine")
                            }
                            IconButton(onClick = { reloadEngine("/board") }) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = "Open Board")
                            }
                            IconButton(onClick = { handleStartTermux() }) {
                                Icon(Icons.Default.Terminal, contentDescription = "Start Termux")
                            }
                            IconButton(onClick = { showConfigDialog = true }) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                        }
                    )
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .background(DarkBackground)
                ) {
                    // Page Loading Progress Bar
                    if (connectionStatus == ConnectionStatus.CONNECTING && pageProgress in 1..99) {
                        LinearProgressIndicator(
                            progress = { pageProgress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp),
                            color = DarkAcc,
                            trackColor = DarkLine,
                        )
                    }

                    // WebView Container
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewInstance = this
                                setBackgroundColor(0xFF0B0E14.toInt())

                                @SuppressLint("SetJavaScriptEnabled")
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    allowFileAccess = false
                                    allowContentAccess = true
                                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                    cacheMode = WebSettings.LOAD_DEFAULT
                                    loadWithOverviewMode = true
                                    useWideViewPort = true
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val uri = request?.url ?: return false
                                        if (LoopbackGuard.isLoopback(uri)) {
                                            return false // Loopback navigation allowed
                                        }

                                        // Non-loopback URL refused in WebView: open in system browser
                                        try {
                                            val extIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(extIntent)
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(
                                                    "Opening external link in system browser: $uri"
                                                )
                                            }
                                        } catch (e: Exception) {
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar("Cannot open link: ${e.message}")
                                            }
                                        }
                                        return true
                                    }

                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        super.onPageStarted(view, url, favicon)
                                        connectionStatus = ConnectionStatus.CONNECTING
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        connectionStatus = ConnectionStatus.CONNECTED
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?
                                    ) {
                                        super.onReceivedError(view, request, error)
                                        if (request?.isForMainFrame == true) {
                                            connectionStatus = ConnectionStatus.OFFLINE
                                        }
                                    }

                                    override fun onReceivedHttpError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        errorResponse: WebResourceResponse?
                                    ) {
                                        super.onReceivedHttpError(view, request, errorResponse)
                                        if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 200) >= 400) {
                                            if (errorResponse?.statusCode == 401) {
                                                coroutineScope.launch {
                                                    snackbarHostState.showSnackbar("Unauthorized pairing token on 127.0.0.1")
                                                }
                                            }
                                        }
                                    }
                                }

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        super.onProgressChanged(view, newProgress)
                                        pageProgress = newProgress
                                    }

                                    override fun onShowFileChooser(
                                        webView: WebView?,
                                        filePathCallback: ValueCallback<Array<Uri>>?,
                                        fileChooserParams: FileChooserParams?
                                    ): Boolean {
                                        fileUploadCallback?.onReceiveValue(null)
                                        fileUploadCallback = filePathCallback

                                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                                            != PackageManager.PERMISSION_GRANTED
                                        ) {
                                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                        }

                                        return try {
                                            val intent = fileChooserParams?.createIntent()
                                                ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                                    type = "*/*"
                                                    addCategory(Intent.CATEGORY_OPENABLE)
                                                }
                                            fileChooserLauncher.launch(intent)
                                            true
                                        } catch (e: Exception) {
                                            fileUploadCallback?.onReceiveValue(null)
                                            fileUploadCallback = null
                                            false
                                        }
                                    }
                                }

                                reloadEngine()
                            }
                        }
                    )

                    // Offline Error Screen with Exact Next Steps
                    if (connectionStatus == ConnectionStatus.OFFLINE) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = DarkBackground
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Engine Not Reachable",
                                    tint = DarkWarn,
                                    modifier = Modifier.size(56.dp)
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = "Engine Not Reachable",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DarkInk
                                )

                                Text(
                                    text = "http://127.0.0.1:$currentPort",
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = DarkDim,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                                )

                                Card(
                                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, DarkLine, RoundedCornerShape(14.dp))
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = "EXACT NEXT STEPS",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = DarkAcc,
                                            letterSpacing = 1.sp
                                        )

                                        Spacer(modifier = Modifier.height(10.dp))

                                        Text(
                                            text = "1. Running locally on this phone (Termux):",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.5.sp,
                                            color = DarkInk
                                        )
                                        Text(
                                            text = "Tap \"Start via Termux\" below, or open Termux and run \"ai serve\".",
                                            fontSize = 13.sp,
                                            color = DarkDim,
                                            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                                        )

                                        Text(
                                            text = "2. Paired to your computer over USB:",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.5.sp,
                                            color = DarkInk
                                        )
                                        Text(
                                            text = "Run: adb reverse tcp:$currentPort tcp:$currentPort\nEnsure \"ai serve\" is running on your computer.",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            color = DarkDim,
                                            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                                        )

                                        Text(
                                            text = "3. Custom port or pairing token:",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.5.sp,
                                            color = DarkInk
                                        )
                                        Text(
                                            text = "Tap \"Configure Connection\" to update the loopback port or supply your token.",
                                            fontSize = 13.sp,
                                            color = DarkDim,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(24.dp))

                                Button(
                                    onClick = { reloadEngine() },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = DarkAcc,
                                        contentColor = DarkBackground
                                    )
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Retry Connection", fontWeight = FontWeight.Bold)
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                OutlinedButton(
                                    onClick = { handleStartTermux() },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DarkInk),
                                    border = ButtonDefaults.outlinedButtonBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DarkLine))
                                ) {
                                    Icon(Icons.Default.Terminal, contentDescription = null, tint = DarkInk)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Start via Termux")
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                TextButton(
                                    onClick = { showConfigDialog = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Settings, contentDescription = null, tint = DarkDim)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Configure Connection", color = DarkDim)
                                }
                            }
                        }
                    }

                    // Configuration Dialog
                    if (showConfigDialog) {
                        var tempPort by remember { mutableStateOf(currentPort.toString()) }
                        var tempToken by remember { mutableStateOf(tokenStore.token) }
                        var showTokenPassword by remember { mutableStateOf(false) }

                        AlertDialog(
                            containerColor = DarkCard,
                            shape = RoundedCornerShape(16.dp),
                            onDismissRequest = { showConfigDialog = false },
                            title = {
                                Text(
                                    "Engine Configuration",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DarkInk
                                )
                            },
                            text = {
                                Column {
                                    Text(
                                        "Configure loopback port and secure pairing token. Protected via EncryptedSharedPreferences.",
                                        fontSize = 13.sp,
                                        color = DarkDim
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    OutlinedTextField(
                                        value = tempPort,
                                        onValueChange = { tempPort = it.filter { ch -> ch.isDigit() } },
                                        label = { Text("Port (Default: 8765)") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = DarkAcc,
                                            unfocusedBorderColor = DarkLine,
                                            focusedLabelColor = DarkAcc,
                                            unfocusedLabelColor = DarkDim,
                                            focusedTextColor = DarkInk,
                                            unfocusedTextColor = DarkInk
                                        )
                                    )

                                    Spacer(modifier = Modifier.height(12.dp))

                                    OutlinedTextField(
                                        value = tempToken,
                                        onValueChange = { tempToken = it },
                                        label = { Text("Pairing Token (AI_SERVE_TOKEN)") },
                                        singleLine = true,
                                        visualTransformation = if (showTokenPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                        trailingIcon = {
                                            IconButton(onClick = { showTokenPassword = !showTokenPassword }) {
                                                Icon(
                                                    imageVector = if (showTokenPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                                    contentDescription = "Toggle token visibility",
                                                    tint = DarkDim
                                                )
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = DarkAcc,
                                            unfocusedBorderColor = DarkLine,
                                            focusedLabelColor = DarkAcc,
                                            unfocusedLabelColor = DarkDim,
                                            focusedTextColor = DarkInk,
                                            unfocusedTextColor = DarkInk
                                        )
                                    )

                                    if (hasToken) {
                                        Text(
                                            text = "Masked Token: ${tokenStore.getMaskedToken()}",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = DarkOk,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        val portInt = tempPort.toIntOrNull() ?: TokenStore.DEFAULT_PORT
                                        tokenStore.port = portInt
                                        tokenStore.token = tempToken
                                        currentPort = tokenStore.port
                                        hasToken = tokenStore.hasToken()
                                        showConfigDialog = false
                                        reloadEngine()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = DarkAcc,
                                        contentColor = DarkBackground
                                    )
                                ) {
                                    Text("Save & Connect", fontWeight = FontWeight.Bold)
                                }
                            },
                            dismissButton = {
                                Row {
                                    if (hasToken) {
                                        TextButton(
                                            onClick = {
                                                tokenStore.clearToken()
                                                tempToken = ""
                                                hasToken = false
                                                showConfigDialog = false
                                                reloadEngine()
                                            }
                                        ) {
                                            Text("Clear Token", color = DarkBad)
                                        }
                                    }
                                    TextButton(onClick = { showConfigDialog = false }) {
                                        Text("Cancel", color = DarkDim)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        webViewInstance?.destroy()
        super.onDestroy()
    }
}
