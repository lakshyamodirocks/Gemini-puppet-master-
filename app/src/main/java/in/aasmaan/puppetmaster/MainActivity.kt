package `in`.aasmaan.puppetmaster

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tokenStore: TokenStore
    private var webView: WebView? = null
    private var progressBar: ProgressBar? = null
    private var offlineView: View? = null
    private var statusDot: View? = null
    private var statusSubtitle: TextView? = null

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var hasStartedService = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        doStartEngineService()
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (fileUploadCallback == null) return@registerForActivityResult
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenStore = TokenStore(this)

        setContentView(buildContentView())

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView
                if (wv != null && wv.canGoBack() && offlineView?.visibility != View.VISIBLE) {
                    wv.goBack()
                } else {
                    finish()
                }
            }
        })

        reloadEngine()
    }

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0E14"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Top Header Bar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10))
            setBackgroundColor(Color.parseColor("#0B0E14"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        val titleText = TextView(this).apply {
            text = "✦ Puppet Master"
            textSize = 17f
            setTextColor(Color.parseColor("#E6EDF6"))
            paint.isFakeBoldText = true
        }

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(2), 0, 0)
        }

        statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(7), dpToPx(7))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#D29922"))
            }
        }

        statusSubtitle = TextView(this).apply {
            text = "127.0.0.1:${tokenStore.port} (Connecting…)"
            textSize = 12f
            setTextColor(Color.parseColor("#8B97A8"))
            setPadding(dpToPx(6), 0, 0, 0)
        }

        statusRow.addView(statusDot)
        statusRow.addView(statusSubtitle)

        titleContainer.addView(titleText)
        titleContainer.addView(statusRow)
        header.addView(titleContainer)

        // Action Icons: Refresh, Board, Termux, Settings
        val btnRefresh = createActionButton(R.drawable.ic_refresh, "Refresh") {
            reloadEngine()
        }
        val btnBoard = createActionButton(R.drawable.ic_terminal, "Board") {
            reloadEngine("/board")
        }
        val btnTermux = createActionButton(R.drawable.ic_terminal, "Start Termux") {
            handleStartTermux()
        }
        val btnSettings = createActionButton(R.drawable.ic_settings, "Settings") {
            showConfigDialog()
        }

        header.addView(btnRefresh)
        header.addView(btnBoard)
        header.addView(btnTermux)
        header.addView(btnSettings)
        root.addView(header)

        // 2. Loading Progress Bar
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(3)
            )
            isIndeterminate = false
            max = 100
            progress = 0
            visibility = View.GONE
        }
        root.addView(progressBar)

        // 3. Web & Offline Container
        val frameContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }

        // WebView
        val wv = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0E14"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        setupWebView(wv)
        this.webView = wv
        frameContainer.addView(wv)

        // Offline Error View
        val offline = buildOfflineView()
        offline.visibility = View.GONE
        this.offlineView = offline
        frameContainer.addView(offline)

        root.addView(frameContainer)
        return root
    }

    private fun createActionButton(iconRes: Int, contentDesc: String, onClick: () -> Unit): ImageButton {
        return ImageButton(this).apply {
            setImageResource(iconRes)
            contentDescription = contentDesc
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.parseColor("#E6EDF6"))
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(40))
            setOnClickListener { onClick() }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView) {
        wv.settings.apply {
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

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                if (LoopbackGuard.isLoopback(uri)) {
                    return false
                }
                // External link refused: launch in external browser
                try {
                    val extIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(extIntent)
                    Toast.makeText(this@MainActivity, "Opening external link in browser", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar?.visibility = View.VISIBLE
                updateStatus(ConnectionStatus.CONNECTING)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar?.visibility = View.GONE

                if (offlineView?.visibility != View.VISIBLE) {
                    updateStatus(ConnectionStatus.CONNECTED)
                    // Requirement 4 & 7: Start EngineService when panel loads successfully
                    checkAndStartEngineService()
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    showOffline()
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 200) >= 400) {
                    if (errorResponse?.statusCode == 401) {
                        Toast.makeText(this@MainActivity, "Unauthorized pairing token on 127.0.0.1", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar?.progress = newProgress
                if (newProgress >= 100) {
                    progressBar?.visibility = View.GONE
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
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
    }

    private fun checkAndStartEngineService() {
        if (hasStartedService) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        doStartEngineService()
    }

    private fun doStartEngineService() {
        try {
            val intent = Intent(this, EngineService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            hasStartedService = true
        } catch (_: Exception) {}
    }

    private fun buildOfflineView(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0E14"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpToPx(24), dpToPx(32), dpToPx(24), dpToPx(32))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val warnIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_warning)
            setColorFilter(Color.parseColor("#D29922"))
            layoutParams = LinearLayout.LayoutParams(dpToPx(56), dpToPx(56))
        }
        container.addView(warnIcon)

        val title = TextView(this).apply {
            text = "Engine Not Reachable"
            textSize = 20f
            paint.isFakeBoldText = true
            setTextColor(Color.parseColor("#E6EDF6"))
            setPadding(0, dpToPx(16), 0, dpToPx(4))
        }
        container.addView(title)

        val hostText = TextView(this).apply {
            text = "http://127.0.0.1:${tokenStore.port}"
            textSize = 14f
            setTextColor(Color.parseColor("#8B97A8"))
            setPadding(0, 0, 0, dpToPx(20))
        }
        container.addView(hostText)

        // Card instructions
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#151A24"))
                setStroke(dpToPx(1), Color.parseColor("#232A38"))
                cornerRadius = dpToPx(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val stepHeader = TextView(this).apply {
            text = "EXACT NEXT STEPS"
            textSize = 11f
            paint.isFakeBoldText = true
            setTextColor(Color.parseColor("#6EA8FE"))
            letterSpacing = 0.08f
            setPadding(0, 0, 0, dpToPx(10))
        }
        card.addView(stepHeader)

        val step1Title = TextView(this).apply {
            text = "1. Running locally on this phone (Termux):"
            textSize = 13.5f
            paint.isFakeBoldText = true
            setTextColor(Color.parseColor("#E6EDF6"))
        }
        val step1Body = TextView(this).apply {
            text = "Tap \"Start via Termux\" below, or open Termux and run \"ai serve\"."
            textSize = 13f
            setTextColor(Color.parseColor("#8B97A8"))
            setPadding(0, dpToPx(2), 0, dpToPx(10))
        }
        card.addView(step1Title)
        card.addView(step1Body)

        val step2Title = TextView(this).apply {
            text = "2. Paired to your computer over USB:"
            textSize = 13.5f
            paint.isFakeBoldText = true
            setTextColor(Color.parseColor("#E6EDF6"))
        }
        val step2Body = TextView(this).apply {
            text = "Run: adb reverse tcp:${tokenStore.port} tcp:${tokenStore.port}\nEnsure \"ai serve\" is running on your computer."
            textSize = 12f
            setTextColor(Color.parseColor("#8B97A8"))
            setPadding(0, dpToPx(2), 0, dpToPx(10))
        }
        card.addView(step2Title)
        card.addView(step2Body)

        val step3Title = TextView(this).apply {
            text = "3. Custom port or pairing token:"
            textSize = 13.5f
            paint.isFakeBoldText = true
            setTextColor(Color.parseColor("#E6EDF6"))
        }
        val step3Body = TextView(this).apply {
            text = "Tap \"Configure Connection\" to update loopback port or token."
            textSize = 13f
            setTextColor(Color.parseColor("#8B97A8"))
            setPadding(0, dpToPx(2), 0, 0)
        }
        card.addView(step3Title)
        card.addView(step3Body)
        container.addView(card)

        // Action Buttons
        val btnRetry = Button(this).apply {
            text = "Retry Connection"
            setTextColor(Color.parseColor("#0B0E14"))
            paint.isFakeBoldText = true
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#6EA8FE"))
                cornerRadius = dpToPx(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(48)
            ).apply { setMargins(0, dpToPx(20), 0, dpToPx(10)) }
            setOnClickListener { reloadEngine() }
        }
        container.addView(btnRetry)

        val btnTermux = Button(this).apply {
            text = "Start via Termux"
            setTextColor(Color.parseColor("#E6EDF6"))
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dpToPx(1), Color.parseColor("#232A38"))
                cornerRadius = dpToPx(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(48)
            ).apply { setMargins(0, 0, 0, dpToPx(8)) }
            setOnClickListener { handleStartTermux() }
        }
        container.addView(btnTermux)

        val btnConfig = Button(this).apply {
            text = "Configure Connection"
            setTextColor(Color.parseColor("#8B97A8"))
            background = null
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(40)
            )
            setOnClickListener { showConfigDialog() }
        }
        container.addView(btnConfig)

        scroll.addView(container)
        return scroll
    }

    private fun reloadEngine(path: String = "/") {
        offlineView?.visibility = View.GONE
        webView?.visibility = View.VISIBLE
        updateStatus(ConnectionStatus.CONNECTING)

        val targetUrl = LoopbackGuard.buildLoopbackUrl(tokenStore.port, path, tokenStore.token)
        val authHeaders = LoopbackGuard.getAuthHeaders(tokenStore.token)
        if (authHeaders.isNotEmpty()) {
            webView?.loadUrl(targetUrl, authHeaders)
        } else {
            webView?.loadUrl(targetUrl)
        }
    }

    private fun showOffline() {
        webView?.visibility = View.GONE
        offlineView?.visibility = View.VISIBLE
        updateStatus(ConnectionStatus.OFFLINE)
    }

    private fun updateStatus(status: ConnectionStatus) {
        val dot = statusDot ?: return
        val text = statusSubtitle ?: return

        when (status) {
            ConnectionStatus.CONNECTED -> {
                (dot.background as? GradientDrawable)?.setColor(Color.parseColor("#3FB950"))
                text.text = "127.0.0.1:${tokenStore.port} (Active)"
            }
            ConnectionStatus.CONNECTING -> {
                (dot.background as? GradientDrawable)?.setColor(Color.parseColor("#D29922"))
                text.text = "127.0.0.1:${tokenStore.port} (Connecting…)"
            }
            ConnectionStatus.OFFLINE -> {
                (dot.background as? GradientDrawable)?.setColor(Color.parseColor("#F85149"))
                text.text = "127.0.0.1:${tokenStore.port} (Offline)"
            }
        }
    }

    private fun handleStartTermux() {
        if (!TermuxBridge.isTermuxInstalled(this)) {
            Toast.makeText(this, "Termux not installed. Opening F-Droid...", Toast.LENGTH_SHORT).show()
            TermuxBridge.openFdroidTermux(this)
            return
        }

        val started = TermuxBridge.startAiServeInTermux(this)
        if (started) {
            Toast.makeText(this, "Command \"ai serve\" sent to Termux. Reconnecting...", Toast.LENGTH_SHORT).show()
            updateStatus(ConnectionStatus.CONNECTING)
            mainHandler.postDelayed({ reloadEngine() }, 3000)
        } else {
            Toast.makeText(this, "Could not send command to Termux. Please launch Termux manually.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showConfigDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24), dpToPx(16), dpToPx(24), dpToPx(8))
        }

        val hintText = TextView(this).apply {
            text = "Configure loopback port and secure pairing token. Protected via EncryptedSharedPreferences."
            textSize = 13f
            setTextColor(Color.parseColor("#8B97A8"))
            setPadding(0, 0, 0, dpToPx(12))
        }
        layout.addView(hintText)

        val portInput = EditText(this).apply {
            hint = "Port (Default: 8765)"
            setText(tokenStore.port.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.parseColor("#E6EDF6"))
            setHintTextColor(Color.parseColor("#8B97A8"))
        }
        layout.addView(portInput)

        val tokenInput = EditText(this).apply {
            hint = "Pairing Token (AI_SERVE_TOKEN)"
            setText(tokenStore.token)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.parseColor("#E6EDF6"))
            setHintTextColor(Color.parseColor("#8B97A8"))
        }
        layout.addView(tokenInput)

        if (tokenStore.hasToken()) {
            val maskedText = TextView(this).apply {
                text = "Masked Token: ${tokenStore.getMaskedToken()}"
                textSize = 12f
                setTextColor(Color.parseColor("#3FB950"))
                setPadding(0, dpToPx(6), 0, 0)
            }
            layout.addView(maskedText)
        }

        AlertDialog.Builder(this)
            .setTitle("Engine Configuration")
            .setView(layout)
            .setPositiveButton("Save & Connect") { _, _ ->
                val p = portInput.text.toString().toIntOrNull() ?: TokenStore.DEFAULT_PORT
                tokenStore.port = p
                tokenStore.token = tokenInput.text.toString().trim()
                reloadEngine()
            }
            .setNeutralButton(if (tokenStore.hasToken()) "Clear Token" else null) { _, _ ->
                tokenStore.clearToken()
                reloadEngine()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        webView?.destroy()
        super.onDestroy()
    }
}
