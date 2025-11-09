package com.custombrowser

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.custombrowser.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bookmarkManager: BookmarkManager
    private var isDesktopMode = false
    private var isDarkMode = false
    private val prefs by lazy { getSharedPreferences("browser_settings", Context.MODE_PRIVATE) }

    // File upload support
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    // Terminal working directory
    private var currentWorkingDir: java.io.File = java.io.File("/storage/emulated/0")
    private var selectedFolderPath: String? = null

    // Terminal command history
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1

    // Hotkeys
    private val maxHotkeys = 30
    private val defaultHotkeys = listOf(
        "ls" to "ls",
        "pwd" to "pwd",
        "git status" to "git status",
        "git pull" to "git pull",
        "gradle build" to "gradle assembleDebug"
    )

    companion object {
        private const val KIWI_BROWSER_PACKAGE = "com.kiwibrowser.browser"
        private const val KIWI_BROWSER_PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=$KIWI_BROWSER_PACKAGE"
    }

    private fun shouldUseChrome(key: String): Boolean {
        return prefs.getBoolean("use_chrome_$key", key in listOf("claude", "gemini", "cursor"))
    }

    // Permission launcher for notifications (API 33+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.notification_permission_required, Toast.LENGTH_LONG).show()
        }
    }

    // File picker launcher for file upload
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uris = mutableListOf<Uri>()
            result.data?.let { intent ->
                // Handle multiple files
                intent.clipData?.let { clipData ->
                    for (i in 0 until clipData.itemCount) {
                        clipData.getItemAt(i).uri?.let { uris.add(it) }
                    }
                } ?: intent.data?.let { uris.add(it) }
            }
            fileUploadCallback?.onReceiveValue(uris.toTypedArray())
        } else {
            fileUploadCallback?.onReceiveValue(null)
        }
        fileUploadCallback = null
    }

    // Storage permission launcher for file access
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, "Storage permission required for file access", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply saved theme before setContentView
        isDarkMode = prefs.getBoolean("dark_mode", false)
        applyTheme()

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bookmarkManager = BookmarkManager(this)

        // Request notification permission for API 33+
        requestNotificationPermission()

        // Request storage permission for file upload
        requestStoragePermission()

        setupWebView()
        setupToolbar()
        setupQuickAccessButtons()
        setupSwipeGestures()

        // Handle intent URLs
        intent?.data?.toString()?.let { url ->
            loadUrl(url)
        } ?: run {
            // Load default page
            loadUrl("https://www.google.com")
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestStoragePermission() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: Request granular media permissions
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            // Below Android 13: Request READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            storagePermissionLauncher.launch(permissions.toTypedArray())
        }

        // For Android 11+: Also request MANAGE_EXTERNAL_STORAGE for full file access (Termux integration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Please grant all files access in settings", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // Enable console logging for debugging
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // Enable persistent cookie storage for OAuth sessions (Google login)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webView, true)
            // Remove all cookies first to ensure clean state
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                removeSessionCookies(null)
                flush()
            }
        }

        binding.webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                loadWithOverviewMode = true
                useWideViewPort = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                // For better tablet experience
                layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL

                // Enable local storage for web apps
                allowFileAccess = true
                allowContentAccess = true

                // Enable persistent data for OAuth login sessions
                cacheMode = WebSettings.LOAD_DEFAULT

                // Enhanced settings for Google login and OAuth sites
                javaScriptCanOpenWindowsAutomatically = true
                mediaPlaybackRequiresUserGesture = false

                // Critical for Google login
                setSupportMultipleWindows(true) // Google login uses popups

                // Disable safe browsing for OAuth compatibility (Claude, Google login)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = false
                }

                // User Agent - Use Chrome UA to avoid Google OAuth blocking
                // Google's OAuth policy requires a "secure browser" UA
                userAgentString = if (isDesktopMode) {
                    // Desktop Chrome on Linux
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                } else {
                    // Mobile Chrome on Android - completely replace default UA
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.64 Mobile Safari/537.36"
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false

                    // Block ALL Google account/OAuth URLs - must use external browser
                    if (url.contains("accounts.google.com") ||
                        url.contains("google.com/accounts") ||
                        url.contains("accounts.youtube.com") ||
                        url.contains("gstatic.com/") && url.contains("google")) {

                        openInExternalBrowser(url)
                        return true
                    }
                    return false
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)

                    // Double-check: If Google account page somehow loaded, stop and redirect
                    if (url != null && (url.contains("accounts.google.com") ||
                        url.contains("google.com/accounts"))) {

                        view?.stopLoading()
                        openInExternalBrowser(url)

                        // Show message to user
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "Opening Google login in external browser...",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        return
                    }

                    // Dynamic User Agent based on URL
                    when {
                        url?.contains("claude.ai") == true -> {
                            // Desktop UA for Claude
                            view?.settings?.userAgentString =
                                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                        }
                        !isDesktopMode -> {
                            // Mobile Chrome UA for other sites
                            view?.settings?.userAgentString =
                                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.64 Mobile Safari/537.36"
                        }
                        else -> {
                            // Desktop mode enabled
                            view?.settings?.userAgentString =
                                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                        }
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    binding.progressBar.visibility = View.GONE
                    binding.urlBar.setText(url)
                    updateBookmarkButton(url)

                    // Fix purple link color and improve readability
                    val css = if (isDarkMode) {
                        """
                        a:link { color: #64B5F6 !important; }
                        a:visited { color: #90CAF9 !important; }
                        a:hover { color: #42A5F5 !important; }
                        a:active { color: #2196F3 !important; }
                        """
                    } else {
                        """
                        a:link { color: #1976D2 !important; }
                        a:visited { color: #1E88E5 !important; }
                        a:hover { color: #2196F3 !important; }
                        a:active { color: #0D47A1 !important; }
                        """
                    }
                    val javascript = "javascript:(function() { var style = document.createElement('style'); style.innerHTML = '$css'; document.head.appendChild(style); })()"
                    view?.loadUrl(javascript)
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: android.webkit.SslErrorHandler?,
                    error: android.net.http.SslError?
                ) {
                    // For OAuth sites like Claude.ai, proceed anyway
                    // WARNING: This reduces security, only for development/OAuth compatibility
                    handler?.proceed()
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    if (newProgress < 100) {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.progressBar.progress = newProgress
                    } else {
                        binding.progressBar.visibility = View.GONE
                    }
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    super.onReceivedTitle(view, title)
                    // Could update title in toolbar if needed
                }

                // File upload support
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = filePathCallback

                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }

                    // Allow multiple file selection
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)

                    try {
                        filePickerLauncher.launch(intent)
                    } catch (e: Exception) {
                        fileUploadCallback = null
                        Toast.makeText(this@MainActivity, "Cannot open file picker", Toast.LENGTH_SHORT).show()
                        return false
                    }
                    return true
                }

                // Support popup windows for Google OAuth login
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean {
                    val newWebView = WebView(this@MainActivity)
                    newWebView.settings.javaScriptEnabled = true
                    newWebView.settings.domStorageEnabled = true
                    newWebView.settings.javaScriptCanOpenWindowsAutomatically = true

                    // Create dialog for popup
                    val dialog = AlertDialog.Builder(this@MainActivity)
                        .setView(newWebView)
                        .setNegativeButton("Close") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .create()

                    newWebView.webChromeClient = object : WebChromeClient() {
                        override fun onCloseWindow(window: WebView?) {
                            dialog.dismiss()
                        }
                    }

                    newWebView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            return false
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // If login successful, close popup and reload main page
                            if (url?.contains("accounts.google.com") == false) {
                                dialog.dismiss()
                                binding.webView.reload()
                            }
                        }
                    }

                    dialog.show()

                    val transport = resultMsg?.obj as? WebView.WebViewTransport
                    transport?.webView = newWebView
                    resultMsg?.sendToTarget()

                    return true
                }
            }

            // Setup download listener
            setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
                downloadFile(url, userAgent, contentDisposition, mimetype)
            }
        }
    }

    private fun setupToolbar() {
        // Back button
        binding.btnBack.setOnClickListener {
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            }
        }

        // Forward button
        binding.btnForward.setOnClickListener {
            if (binding.webView.canGoForward()) {
                binding.webView.goForward()
            }
        }

        // Refresh button
        binding.btnRefresh.setOnClickListener {
            binding.webView.reload()
        }

        // URL bar - Make it always editable
        binding.urlBar.apply {
            // Enable editing
            isFocusable = true
            isFocusableInTouchMode = true

            // When clicked, select all text for easy editing
            setOnClickListener {
                selectAll()
            }

            // When focused, select all text
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    // Delay to ensure selection works
                    postDelayed({
                        selectAll()
                    }, 100)
                }
            }

            // Handle enter key
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    (event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                    val input = text.toString().trim()
                    if (input.isNotEmpty()) {
                        loadUrl(input)
                        clearFocus()
                        // Hide keyboard
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                        imm.hideSoftInputFromWindow(windowToken, 0)
                    }
                    true
                } else {
                    false
                }
            }
        }

        // Bookmark button
        binding.btnBookmark.setOnClickListener {
            val currentUrl = binding.webView.url ?: return@setOnClickListener
            if (bookmarkManager.isBookmarked(currentUrl)) {
                removeBookmark(currentUrl)
            } else {
                addBookmark(currentUrl, binding.webView.title ?: currentUrl)
            }
        }

        // Screenshot button
        binding.btnScreenshot.setOnClickListener {
            takeScreenshot()
        }

        // Terminal button
        binding.btnTerminal.setOnClickListener {
            toggleTerminal()
        }

        // Menu button
        binding.btnMenu.setOnClickListener {
            showMenu()
        }
    }

    private fun setupQuickAccessButtons() {
        val quickAccessUrls = bookmarkManager.getQuickAccessUrls()

        binding.btnClaudeCode.setOnClickListener {
            val url = quickAccessUrls["claude"] ?: "https://claude.ai/code"
            if (shouldUseChrome("claude")) {
                openInExternalBrowser(url)
            } else {
                loadUrl(url)
            }
        }

        binding.btnGemini.setOnClickListener {
            val url = quickAccessUrls["gemini"] ?: "https://aistudio.google.com/app"
            if (shouldUseChrome("gemini")) {
                openInExternalBrowser(url)
            } else {
                loadUrl(url)
            }
        }

        binding.btnChatGPT.setOnClickListener {
            val url = quickAccessUrls["chatgpt"] ?: "https://chatgpt.com"
            if (shouldUseChrome("chatgpt")) {
                openInExternalBrowser(url)
            } else {
                loadUrl(url)
            }
        }

        binding.btnGitHub.setOnClickListener {
            val url = quickAccessUrls["github"] ?: "https://github.com"
            if (shouldUseChrome("github")) {
                openInExternalBrowser(url)
            } else {
                loadUrl(url)
            }
        }

        binding.btnCursor.setOnClickListener {
            val url = quickAccessUrls["cursor"] ?: "https://cursor.sh"
            if (shouldUseChrome("cursor")) {
                openInExternalBrowser(url)
            } else {
                loadUrl(url)
            }
        }

        binding.btnLocalhost.setOnClickListener {
            showLocalhostDialog()
        }

        binding.btnKiwi.setOnClickListener {
            val currentUrl = binding.webView.url ?: "https://www.google.com"
            openInKiwiBrowser(currentUrl)
        }
    }

    private fun loadUrl(input: String) {
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.startsWith("localhost:") || input.startsWith("127.0.0.1") -> "http://$input"
            input.contains("localhost") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://www.google.com/search?q=${input}"
        }
        binding.webView.loadUrl(url)
    }

    private fun addBookmark(url: String, title: String) {
        val bookmark = Bookmark(title = title, url = url)
        bookmarkManager.addBookmark(bookmark)
        updateBookmarkButton(url)
        Toast.makeText(this, R.string.bookmark_added, Toast.LENGTH_SHORT).show()
    }

    private fun removeBookmark(url: String) {
        val bookmark = bookmarkManager.getBookmarks().find { it.url == url }
        bookmark?.let {
            bookmarkManager.removeBookmark(it.id)
            updateBookmarkButton(url)
            Toast.makeText(this, R.string.bookmark_removed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateBookmarkButton(url: String?) {
        url?.let {
            val isBookmarked = bookmarkManager.isBookmarked(it)
            binding.btnBookmark.setImageResource(
                if (isBookmarked) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star
            )
        }
    }

    private fun openInExternalBrowser(url: String) {
        try {
            // Try Chrome Custom Tabs first
            val builder = androidx.browser.customtabs.CustomTabsIntent.Builder()
            builder.setShowTitle(true)
            builder.setUrlBarHidingEnabled(false)
            val customTabsIntent = builder.build()
            customTabsIntent.launchUrl(this, android.net.Uri.parse(url))
        } catch (e: Exception) {
            // Fallback to default browser
            try {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(this, "Cannot open external browser", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showLocalhostDialog() {
        val dialogView = layoutInflater.inflate(android.R.layout.simple_list_item_1, null)
        val editText = EditText(this).apply {
            hint = "Enter port (e.g., 3000, 8080)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("3000")
        }

        AlertDialog.Builder(this)
            .setTitle("Open Localhost")
            .setView(editText)
            .setPositiveButton("Go") { _, _ ->
                val port = editText.text.toString()
                loadUrl("http://localhost:$port")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun downloadFile(url: String, userAgent: String, contentDisposition: String, mimetype: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimetype)
                addRequestHeader("User-Agent", userAgent)
                setDescription("Downloading file...")
                setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(url, contentDisposition, mimetype))
            }

            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private fun openDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (e: Exception) {
            Toast.makeText(this, "Downloads app not found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isKiwiBrowserInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo(KIWI_BROWSER_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun openInKiwiBrowser(url: String) {
        if (isKiwiBrowserInstalled()) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    setPackage(KIWI_BROWSER_PACKAGE)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
                Toast.makeText(this, R.string.opened_in_kiwi, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to open Kiwi Browser", Toast.LENGTH_SHORT).show()
                e.printStackTrace()
            }
        } else {
            showKiwiInstallDialog()
        }
    }

    private fun showKiwiInstallDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.kiwi_not_installed)
            .setMessage(R.string.kiwi_install_prompt)
            .setPositiveButton(R.string.install) { _, _ ->
                openPlayStore(KIWI_BROWSER_PACKAGE)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openPlayStore(packageName: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
        } catch (e: Exception) {
            // If Play Store app is not installed, open in browser
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KIWI_BROWSER_PLAY_STORE_URL)))
        }
    }

    private fun showMenu() {
        val kiwiStatus = if (isKiwiBrowserInstalled()) "✓" else "Install"
        val terminalStatus = if (binding.terminalPanel.visibility == View.VISIBLE) "Hide" else "Show"
        val themeIcon = if (isDarkMode) "🌙" else "☀️"
        val themeStatus = if (isDarkMode) "Dark" else "Light"

        val options = arrayOf(
            getString(R.string.menu_bookmarks),
            getString(R.string.menu_downloads),
            getString(R.string.menu_open_in_kiwi) + " ($kiwiStatus)",
            getString(R.string.menu_refresh),
            getString(R.string.menu_desktop_mode) + " (${if (isDesktopMode) "ON" else "OFF"})",
            "$themeIcon Theme: $themeStatus",
            "$terminalStatus Terminal",
            "GitHub Repositories",
            "Manage Quick Access Buttons",
            getString(R.string.menu_settings)
        )

        AlertDialog.Builder(this)
            .setTitle("Menu")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showBookmarks()
                    1 -> openDownloads()
                    2 -> {
                        val currentUrl = binding.webView.url ?: "https://www.google.com"
                        openInKiwiBrowser(currentUrl)
                    }
                    3 -> binding.webView.reload()
                    4 -> toggleDesktopMode()
                    5 -> toggleTheme()
                    6 -> toggleTerminal()
                    7 -> showGitHubRepositories()
                    8 -> showQuickAccessManager()
                    9 -> showSettings()
                }
            }
            .show()
    }

    private fun applyTheme() {
        if (isDarkMode) {
            setTheme(R.style.Theme_CustomBrowser_Dark)
        } else {
            setTheme(R.style.Theme_CustomBrowser_Light)
        }
    }

    private fun toggleTheme() {
        isDarkMode = !isDarkMode
        prefs.edit().putBoolean("dark_mode", isDarkMode).apply()

        // Recreate activity to apply theme
        recreate()
    }

    private fun showGitHubRepositories() {
        val token = prefs.getString("github_token", "")
        if (token.isNullOrEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("GitHub Token Required")
                .setMessage("Please set up your GitHub Personal Access Token in Settings first.")
                .setPositiveButton("Go to Settings") { _, _ ->
                    showGitHubTokenSettings()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val options = arrayOf(
            "My Repositories",
            "Clone by URL"
        )

        AlertDialog.Builder(this)
            .setTitle("GitHub")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> fetchGitHubRepos(token)
                    1 -> showGitCloneDialog()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun fetchGitHubRepos(token: String) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Loading Repositories")
            .setMessage("Fetching your repositories...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        Thread {
            try {
                // Fetch repos from GitHub API
                val url = java.net.URL("https://api.github.com/user/repos?per_page=100&sort=updated")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Accept", "application/vnd.github+json")

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(connection.inputStream))
                    val response = reader.readText()
                    reader.close()

                    // Parse JSON response
                    val repos = parseGitHubRepos(response)

                    runOnUiThread {
                        progressDialog.dismiss()
                        if (repos.isNotEmpty()) {
                            showRepoSelectionDialog(repos)
                        } else {
                            Toast.makeText(this, "No repositories found", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    val errorReader = java.io.BufferedReader(java.io.InputStreamReader(connection.errorStream))
                    val error = errorReader.readText()
                    errorReader.close()

                    runOnUiThread {
                        progressDialog.dismiss()
                        Toast.makeText(this, "Error: $responseCode - $error", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun parseGitHubRepos(json: String): List<Pair<String, String>> {
        val repos = mutableListOf<Pair<String, String>>()
        try {
            // Simple JSON parsing (name and clone_url)
            val items = json.split("\"full_name\":\"")
            for (i in 1 until items.size) {
                val name = items[i].substringBefore("\"")
                val cloneUrlPart = items[i].substringAfter("\"clone_url\":\"")
                val cloneUrl = cloneUrlPart.substringBefore("\"")

                val isPrivate = items[i].contains("\"private\":true")
                val displayName = if (isPrivate) "$name 🔒" else name

                repos.add(displayName to cloneUrl)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return repos
    }

    private fun showRepoSelectionDialog(repos: List<Pair<String, String>>) {
        val repoNames = repos.map { it.first }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Repository (${repos.size})")
            .setItems(repoNames) { _, which ->
                val (_, cloneUrl) = repos[which]
                if (binding.terminalPanel.visibility == View.GONE) {
                    toggleTerminal()
                }
                executeTermuxCommand("git clone $cloneUrl")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showQuickAccessManager() {
        val quickAccessUrls = bookmarkManager.getQuickAccessUrls().toMutableMap()
        val buttons = arrayOf("Claude", "Gemini", "ChatGPT", "GitHub", "Cursor", "Add New Button")

        AlertDialog.Builder(this)
            .setTitle("Manage Quick Access Buttons")
            .setItems(buttons) { _, which ->
                when (which) {
                    0 -> editQuickAccessUrl("claude", quickAccessUrls["claude"] ?: "https://claude.ai/code")
                    1 -> editQuickAccessUrl("gemini", quickAccessUrls["gemini"] ?: "https://aistudio.google.com/app")
                    2 -> editQuickAccessUrl("chatgpt", quickAccessUrls["chatgpt"] ?: "https://chatgpt.com")
                    3 -> editQuickAccessUrl("github", quickAccessUrls["github"] ?: "https://github.com")
                    4 -> editQuickAccessUrl("cursor", quickAccessUrls["cursor"] ?: "https://cursor.sh")
                    5 -> addNewQuickAccessButton()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun addNewQuickAccessButton() {
        val nameInput = EditText(this).apply {
            hint = "Button name (e.g., YouTube)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val urlInput = EditText(this).apply {
            hint = "URL (e.g., https://youtube.com)"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
            addView(nameInput)
            addView(urlInput)
        }

        AlertDialog.Builder(this)
            .setTitle("Add New Quick Access Button")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val name = nameInput.text.toString().lowercase()
                val url = urlInput.text.toString()
                if (name.isNotEmpty() && url.isNotEmpty()) {
                    val urls = bookmarkManager.getQuickAccessUrls().toMutableMap()
                    urls[name] = url
                    bookmarkManager.setQuickAccessUrls(urls)
                    Toast.makeText(this, "Button added! Restart app to see changes", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showBookmarks() {
        val bookmarks = bookmarkManager.getBookmarks()
        if (bookmarks.isEmpty()) {
            Toast.makeText(this, "No bookmarks yet", Toast.LENGTH_SHORT).show()
            return
        }

        val titles = bookmarks.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_bookmarks)
            .setItems(titles) { _, which ->
                loadUrl(bookmarks[which].url)
            }
            .setNeutralButton("Manage") { _, _ ->
                showBookmarkManager()
            }
            .show()
    }

    private fun showBookmarkManager() {
        val bookmarks = bookmarkManager.getBookmarks()
        val titles = bookmarks.map { it.title }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Manage Bookmarks")
            .setItems(titles) { _, which ->
                val bookmark = bookmarks[which]
                AlertDialog.Builder(this)
                    .setTitle(bookmark.title)
                    .setMessage(bookmark.url)
                    .setPositiveButton("Open") { _, _ -> loadUrl(bookmark.url) }
                    .setNegativeButton(R.string.delete) { _, _ ->
                        bookmarkManager.removeBookmark(bookmark.id)
                        showBookmarkManager()
                    }
                    .setNeutralButton(R.string.cancel, null)
                    .show()
            }
            .show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun toggleDesktopMode() {
        isDesktopMode = !isDesktopMode
        binding.webView.settings.userAgentString = if (isDesktopMode) {
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        } else {
            WebSettings.getDefaultUserAgent(this)
        }
        binding.webView.reload()
        Toast.makeText(this, "Desktop mode: ${if (isDesktopMode) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
    }

    private fun showSettings() {
        val options = arrayOf(
            "Chrome Link Settings",
            "Edit URLs",
            "Hotkey Settings",
            "GitHub Token Settings",
            "Gemini API Key (Auto-fix errors)"
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.menu_settings)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showChromeLinkSettings()
                    1 -> showUrlEditSettings()
                    2 -> showHotkeySettings()
                    3 -> showGitHubTokenSettings()
                    4 -> showGeminiApiKeySettings()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showGeminiApiKeySettings() {
        val apiKeyInput = EditText(this).apply {
            hint = "Gemini API Key"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            val savedKey = prefs.getString("gemini_api_key", "")
            setText(savedKey)
        }

        AlertDialog.Builder(this)
            .setTitle("Gemini API Key")
            .setMessage("Enter your Gemini API key for automatic build error fixing.\n\nGet API key at:\naistudio.google.com → Get API key")
            .setView(apiKeyInput)
            .setPositiveButton("Save") { _, _ ->
                val apiKey = apiKeyInput.text.toString().trim()
                prefs.edit().putString("gemini_api_key", apiKey).apply()
                Toast.makeText(this, "Gemini API key saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Test") { _, _ ->
                val apiKey = apiKeyInput.text.toString().trim()
                if (apiKey.isNotEmpty()) {
                    testGeminiApiKey(apiKey)
                }
            }
            .show()
    }

    private fun testGeminiApiKey(apiKey: String) {
        Thread {
            try {
                val response = callGeminiApi(apiKey, "Say 'API key is working!' in one sentence.")
                runOnUiThread {
                    Toast.makeText(this, "API Key Valid! Response: $response", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "API Key Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showHotkeySettings() {
        val hotkeyCount = prefs.getInt("hotkey_count", defaultHotkeys.size)
        val hotkeyLabels = mutableListOf<String>()

        for (i in 0 until hotkeyCount) {
            val label = prefs.getString("hotkey_label_$i",
                if (i < defaultHotkeys.size) defaultHotkeys[i].first else "Hotkey ${i+1}") ?: "Hotkey ${i+1}"
            hotkeyLabels.add(label)
        }
        hotkeyLabels.add("+ Add New Hotkey")

        AlertDialog.Builder(this)
            .setTitle("Hotkey Settings (${hotkeyCount}/$maxHotkeys)")
            .setItems(hotkeyLabels.toTypedArray()) { _, which ->
                if (which < hotkeyCount) {
                    val label = prefs.getString("hotkey_label_$which", "") ?: ""
                    val command = prefs.getString("hotkey_command_$which", "") ?: ""
                    showEditHotkeyDialog(label, command, which)
                } else {
                    if (hotkeyCount < maxHotkeys) {
                        showAddHotkeyDialog()
                    } else {
                        Toast.makeText(this, "Maximum $maxHotkeys hotkeys reached", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showAddHotkeyDialog() {
        val labelInput = EditText(this).apply {
            hint = "Hotkey Label (e.g., 'Build')"
        }
        val commandInput = EditText(this).apply {
            hint = "Command (e.g., 'gradle assembleDebug')"
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
            addView(labelInput)
            addView(commandInput)
        }

        AlertDialog.Builder(this)
            .setTitle("Add New Hotkey")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val label = labelInput.text.toString()
                val command = commandInput.text.toString()

                if (label.isNotEmpty() && command.isNotEmpty()) {
                    val hotkeyCount = prefs.getInt("hotkey_count", defaultHotkeys.size)
                    if (hotkeyCount < maxHotkeys) {
                        prefs.edit()
                            .putString("hotkey_label_$hotkeyCount", label)
                            .putString("hotkey_command_$hotkeyCount", command)
                            .putInt("hotkey_count", hotkeyCount + 1)
                            .apply()

                        Toast.makeText(this, "Hotkey added", Toast.LENGTH_SHORT).show()
                        loadHotkeys()
                    } else {
                        Toast.makeText(this, "Maximum $maxHotkeys hotkeys reached", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditHotkeyDialog(currentLabel: String, currentCommand: String, index: Int = -1) {
        val labelInput = EditText(this).apply {
            hint = "Hotkey Label"
            setText(currentLabel)
        }
        val commandInput = EditText(this).apply {
            hint = "Command"
            setText(currentCommand)
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
            addView(labelInput)
            addView(commandInput)
        }

        AlertDialog.Builder(this)
            .setTitle("Edit Hotkey")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val label = labelInput.text.toString()
                val command = commandInput.text.toString()

                if (label.isNotEmpty() && command.isNotEmpty() && index >= 0) {
                    prefs.edit()
                        .putString("hotkey_label_$index", label)
                        .putString("hotkey_command_$index", command)
                        .apply()

                    Toast.makeText(this, "Hotkey updated", Toast.LENGTH_SHORT).show()
                    loadHotkeys()
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete") { _, _ ->
                if (index >= 0) {
                    deleteHotkey(index)
                }
            }
            .show()
    }

    private fun deleteHotkey(index: Int) {
        val hotkeyCount = prefs.getInt("hotkey_count", defaultHotkeys.size)
        val editor = prefs.edit()

        // Shift all hotkeys after this one down
        for (i in index until hotkeyCount - 1) {
            val nextLabel = prefs.getString("hotkey_label_${i+1}", "")
            val nextCommand = prefs.getString("hotkey_command_${i+1}", "")
            editor.putString("hotkey_label_$i", nextLabel)
            editor.putString("hotkey_command_$i", nextCommand)
        }

        // Remove the last one
        editor.remove("hotkey_label_${hotkeyCount - 1}")
        editor.remove("hotkey_command_${hotkeyCount - 1}")
        editor.putInt("hotkey_count", hotkeyCount - 1)
        editor.apply()

        Toast.makeText(this, "Hotkey deleted", Toast.LENGTH_SHORT).show()
        loadHotkeys()
    }

    private fun showGitHubTokenSettings() {
        val tokenInput = EditText(this).apply {
            hint = "GitHub Personal Access Token"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            val savedToken = prefs.getString("github_token", "")
            setText(savedToken)
        }

        AlertDialog.Builder(this)
            .setTitle("GitHub Token")
            .setMessage("Enter your GitHub Personal Access Token to access private repositories.\n\nCreate token at: github.com → Settings → Developer settings → Personal access tokens")
            .setView(tokenInput)
            .setPositiveButton("Save") { _, _ ->
                val token = tokenInput.text.toString().trim()
                prefs.edit().putString("github_token", token).apply()
                Toast.makeText(this, "GitHub token saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Test") { _, _ ->
                val token = tokenInput.text.toString().trim()
                if (token.isNotEmpty()) {
                    testGitHubToken(token)
                }
            }
            .show()
    }

    private fun testGitHubToken(token: String) {
        Thread {
            try {
                val url = java.net.URL("https://api.github.com/user")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Accept", "application/vnd.github+json")

                val responseCode = connection.responseCode
                val reader = java.io.BufferedReader(java.io.InputStreamReader(
                    if (responseCode == 200) connection.inputStream else connection.errorStream
                ))
                val response = reader.readText()
                reader.close()

                runOnUiThread {
                    if (responseCode == 200) {
                        val username = response.substringAfter("\"login\":\"").substringBefore("\"")
                        Toast.makeText(this, "Token valid! User: $username", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Invalid token: $responseCode", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showChromeLinkSettings() {
        val buttons = arrayOf("Claude", "Gemini", "ChatGPT", "GitHub", "Cursor")
        val checkedItems = booleanArrayOf(
            shouldUseChrome("claude"),
            shouldUseChrome("gemini"),
            shouldUseChrome("chatgpt"),
            shouldUseChrome("github"),
            shouldUseChrome("cursor")
        )

        AlertDialog.Builder(this)
            .setTitle("Open in Chrome Custom Tabs")
            .setMultiChoiceItems(buttons, checkedItems) { _, which, isChecked ->
                val key = when (which) {
                    0 -> "claude"
                    1 -> "gemini"
                    2 -> "chatgpt"
                    3 -> "github"
                    4 -> "cursor"
                    else -> return@setMultiChoiceItems
                }
                prefs.edit().putBoolean("use_chrome_$key", isChecked).apply()
            }
            .setPositiveButton("Done", null)
            .show()
    }

    private fun showUrlEditSettings() {
        val quickAccessUrls = bookmarkManager.getQuickAccessUrls()
        val buttons = arrayOf("Claude", "Gemini", "ChatGPT", "GitHub", "Cursor")

        AlertDialog.Builder(this)
            .setTitle("Edit URLs")
            .setItems(buttons) { _, which ->
                val (key, defaultUrl) = when (which) {
                    0 -> "claude" to "https://claude.ai/code"
                    1 -> "gemini" to "https://aistudio.google.com/app"
                    2 -> "chatgpt" to "https://chatgpt.com"
                    3 -> "github" to "https://github.com"
                    4 -> "cursor" to "https://cursor.sh"
                    else -> return@setItems
                }
                editQuickAccessUrl(key, quickAccessUrls[key] ?: defaultUrl)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun editQuickAccessUrl(key: String, currentUrl: String) {
        val editText = EditText(this).apply {
            setText(currentUrl)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }

        AlertDialog.Builder(this)
            .setTitle("Edit URL for $key")
            .setView(editText)
            .setPositiveButton(R.string.save) { _, _ ->
                val urls = bookmarkManager.getQuickAccessUrls().toMutableMap()
                urls[key] = editText.text.toString()
                bookmarkManager.setQuickAccessUrls(urls)
                Toast.makeText(this, "URL saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toggleTerminal() {
        if (binding.terminalPanel.visibility == View.GONE) {
            binding.terminalPanel.visibility = View.VISIBLE
            binding.terminalInput.requestFocus()

            // Restore saved terminal size
            val savedSize = prefs.getFloat("terminal_size", 0.4f)
            setTerminalSize(savedSize)

            // Load command history
            loadCommandHistory()

            // Show welcome message
            appendTerminalOutput("Custom Browser Terminal v1.0\n")
            appendTerminalOutput("Type 'help' for available commands\n")
            appendTerminalOutput("Working directory: ${currentWorkingDir.absolutePath}\n")
            appendTerminalOutput("Type 'git-config' to setup git user info\n")
            appendTerminalOutput("Use ↑↓ arrows for command history\n\n")

            setupTerminalListeners()
            loadHotkeys()
            loadFolderTree()
            loadTerminalKeyboard()
        } else {
            binding.terminalPanel.visibility = View.GONE
        }
    }

    private fun loadHotkeys() {
        binding.hotkeyContainer.removeAllViews()

        val hotkeyCount = prefs.getInt("hotkey_count", defaultHotkeys.size)

        for (i in 0 until hotkeyCount) {
            val label = prefs.getString("hotkey_label_$i",
                if (i < defaultHotkeys.size) defaultHotkeys[i].first else "Hotkey ${i+1}") ?: "Hotkey ${i+1}"
            val command = prefs.getString("hotkey_command_$i",
                if (i < defaultHotkeys.size) defaultHotkeys[i].second else "") ?: ""

            if (command.isNotEmpty()) {
                addHotkeyButton(label, command)
            }
        }

        // Add "+" button to add new hotkey
        val addButton = com.google.android.material.button.MaterialButton(this).apply {
            text = "+"
            textSize = 12f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#FF555555"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 8
            }
            setOnClickListener { showAddHotkeyDialog() }
        }
        binding.hotkeyContainer.addView(addButton)
    }

    private fun addHotkeyButton(label: String, command: String) {
        val button = com.google.android.material.button.MaterialButton(this).apply {
            text = label
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#FF00FF00"))
            setBackgroundColor(android.graphics.Color.parseColor("#FF2C2C2C"))
            strokeColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF00FF00"))
            strokeWidth = 2
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 8
            }
            setOnClickListener {
                executeCommand(command)
            }
            setOnLongClickListener {
                showEditHotkeyDialog(label, command)
                true
            }
        }
        binding.hotkeyContainer.addView(button, binding.hotkeyContainer.childCount - 1)
    }

    private fun setupTerminalListeners() {
        binding.btnCloseTerminal.setOnClickListener {
            binding.terminalPanel.visibility = View.GONE
        }

        binding.btnClearTerminal.setOnClickListener {
            binding.terminalOutput.text = ""
        }

        // Copy/Paste buttons
        binding.btnCopyTerminal.setOnClickListener {
            val text = binding.terminalOutput.text.toString()
            if (text.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Terminal Output", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Terminal output copied", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnPasteTerminal.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.primaryClip?.getItemAt(0)?.text?.let { text ->
                binding.terminalInput.append(text)
            }
        }

        // Terminal size buttons
        binding.btnTerminalSizeSmall.setOnClickListener {
            setTerminalSize(0.3f)
        }

        binding.btnTerminalSizeMedium.setOnClickListener {
            setTerminalSize(0.5f)
        }

        binding.btnTerminalSizeLarge.setOnClickListener {
            setTerminalSize(0.7f)
        }

        // Command history with arrow keys
        binding.terminalInput.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        navigateHistory(-1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        navigateHistory(1)
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }

        binding.terminalInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                val command = binding.terminalInput.text.toString().trim()
                if (command.isNotEmpty()) {
                    // Add to history
                    commandHistory.add(command)
                    historyIndex = commandHistory.size
                    saveCommandHistory()

                    executeCommand(command)
                    binding.terminalInput.text.clear()
                }
                true
            } else {
                false
            }
        }
    }

    private fun navigateHistory(direction: Int) {
        if (commandHistory.isEmpty()) return

        historyIndex += direction
        historyIndex = historyIndex.coerceIn(0, commandHistory.size)

        if (historyIndex < commandHistory.size) {
            binding.terminalInput.setText(commandHistory[historyIndex])
            binding.terminalInput.setSelection(binding.terminalInput.text.length)
        } else {
            binding.terminalInput.text.clear()
        }
    }

    private fun loadCommandHistory() {
        val historyJson = prefs.getString("command_history", "[]") ?: "[]"
        try {
            val items = historyJson.trim('[', ']').split("\",\"")
            items.forEach { item ->
                val cleaned = item.trim('"', ' ')
                if (cleaned.isNotEmpty()) {
                    commandHistory.add(cleaned)
                }
            }
            historyIndex = commandHistory.size
        } catch (e: Exception) {
            // Ignore parsing errors
        }
    }

    private fun saveCommandHistory() {
        // Keep only last 50 commands
        val recentHistory = commandHistory.takeLast(50)
        val historyJson = recentHistory.joinToString("\",\"", prefix = "[\"", postfix = "\"]")
        prefs.edit().putString("command_history", historyJson).apply()
    }

    private fun setTerminalSize(heightPercent: Float) {
        val layoutParams = binding.terminalPanel.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        layoutParams.matchConstraintPercentHeight = heightPercent
        binding.terminalPanel.layoutParams = layoutParams

        // Save preference
        prefs.edit().putFloat("terminal_size", heightPercent).apply()
    }

    private fun executeCommand(command: String) {
        appendTerminalOutput("$ $command\n")

        when {
            command == "help" -> {
                appendTerminalOutput("""
                    Available commands:
                    - help: Show this help message
                    - clear: Clear terminal output
                    - pwd: Print working directory
                    - ls [path]: List directory contents
                    - cd <path>: Change directory
                    - git-config: Setup git user info
                    - git clone <url>: Clone a repository
                    - git add <files>: Stage files
                    - git commit -m "msg": Commit changes
                    - git push: Push to remote
                    - gradle <task>: Run gradle build
                    - cat <file>: Display file contents
                    - download <url>: Download a file
                    - mkdir <dir>: Create directory
                    - setup-claude: Install Claude Code CLI
                    - node/npm/npx: Node.js commands
                    - claude: Run Claude Code
                    - claude --image <path>: Send image to Claude

                    📸 Screenshot Feature:
                    1. Click camera button (📷) in toolbar
                    2. Preview screenshot
                    3. Click "Save & Send to Claude"
                    4. Image path auto-inserted in terminal!
                    5. Add your prompt and press Enter

                    - Any other command runs in Termux environment

                """.trimIndent() + "\n")
            }
            command == "clear" -> {
                binding.terminalOutput.text = ""
            }
            command == "setup-claude" -> {
                showClaudeSetupDialog()
            }
            command.startsWith("claude") -> {
                // Check if Node.js is installed
                checkAndRunClaude(command)
            }
            command == "pwd" -> {
                appendTerminalOutput("${currentWorkingDir.absolutePath}\n")
            }
            command == "git-config" -> {
                showGitConfigDialog()
            }
            command.startsWith("cd ") -> {
                val path = command.substring(3).trim()
                executeChangeDirectory(path)
            }
            command.startsWith("mkdir ") -> {
                val dirName = command.substring(6).trim()
                val newDir = java.io.File(currentWorkingDir, dirName)
                if (newDir.mkdirs()) {
                    appendTerminalOutput("Directory created: ${newDir.absolutePath}\n")
                } else {
                    appendTerminalOutput("Failed to create directory\n")
                }
            }
            command.startsWith("git ") -> {
                executeTermuxCommand(command)
            }
            command.startsWith("gradle ") -> {
                executeTermuxCommand(command)
            }
            command.startsWith("ls") -> {
                val path = if (command.length > 3) command.substring(3).trim() else "."
                executeListFiles(path)
            }
            command.startsWith("cat ") -> {
                val filePath = command.substring(4).trim()
                executeCatFile(filePath)
            }
            command.startsWith("download ") -> {
                val url = command.substring(9).trim()
                downloadFile(url, "", "", "application/octet-stream")
                appendTerminalOutput("Download started: $url\n")
            }
            else -> {
                // Execute in Termux environment
                executeTermuxCommand(command)
            }
        }
    }

    private fun executeChangeDirectory(path: String) {
        val newDir = when {
            path == ".." -> currentWorkingDir.parentFile
            path.startsWith("/") -> java.io.File(path)
            path == "~" -> java.io.File("/data/data/com.termux/files/home")
            else -> java.io.File(currentWorkingDir, path)
        }

        if (newDir != null && newDir.exists() && newDir.isDirectory) {
            currentWorkingDir = newDir
            appendTerminalOutput("Changed directory to: ${currentWorkingDir.absolutePath}\n")
        } else {
            appendTerminalOutput("Error: Directory not found: $path\n")
        }
    }

    private fun executeTermuxCommand(command: String, autoFixErrors: Boolean = true) {
        Thread {
            try {
                // Safely get first word of command
                val commandParts = command.trim().split(" ", limit = 2)
                if (commandParts.isEmpty() || commandParts[0].isEmpty()) {
                    runOnUiThread {
                        appendTerminalOutput("❌ Empty command\n")
                    }
                    return@Thread
                }
                val firstWord = commandParts[0]

                // Check if this is a Termux-specific command
                val termuxCommands = listOf("pkg", "apt", "npm", "npx", "node", "claude", "pip", "python", "ruby", "perl", "git")
                val isTermuxCommand = termuxCommands.contains(firstWord)

                // Check if Termux is installed using PackageManager
                val isTermuxInstalled = try {
                    packageManager.getPackageInfo("com.termux", 0)
                    true
                } catch (e: Exception) {
                    false
                }

                if (isTermuxCommand && !isTermuxInstalled) {
                    runOnUiThread {
                        appendTerminalOutput("❌ Termux not installed!\n")
                        appendTerminalOutput("Install Termux from F-Droid or GitHub to use: $command\n")
                        appendTerminalOutput("Download: https://f-droid.org/en/packages/com.termux/\n")
                    }
                    return@Thread
                }

                // For Termux-specific commands, use am to execute in Termux
                if (isTermuxCommand && isTermuxInstalled) {
                    runOnUiThread {
                        appendTerminalOutput("🔄 Executing in Termux...\n")
                        appendTerminalOutput("Check Termux window for output\n")
                    }

                    // Create a script file in shared storage
                    val scriptFile = java.io.File(currentWorkingDir, ".termux_cmd.sh")
                    try {
                        scriptFile.writeText("#!/data/data/com.termux/files/usr/bin/bash\ncd \"${currentWorkingDir.absolutePath}\"\n$command\n")
                    } catch (e: Exception) {
                        runOnUiThread {
                            appendTerminalOutput("❌ Failed to create script: ${e.message}\n")
                        }
                        return@Thread
                    }

                    // Execute via Termux using am broadcast
                    val amCommand = arrayOf(
                        "am", "broadcast",
                        "-a", "com.termux.RUN_COMMAND",
                        "-n", "com.termux/com.termux.app.RunCommandReceiver",
                        "--es", "com.termux.RUN_COMMAND_PATH", scriptFile.absolutePath,
                        "--es", "com.termux.RUN_COMMAND_WORKDIR", currentWorkingDir.absolutePath,
                        "--ez", "com.termux.RUN_COMMAND_BACKGROUND", "false"
                    )

                    val amProcess = Runtime.getRuntime().exec(amCommand)
                    amProcess.waitFor()

                    runOnUiThread {
                        appendTerminalOutput("✅ Command sent to Termux\n")
                    }
                    return@Thread
                }

                // Regular shell commands - use /system/bin/sh
                val env = arrayOf(
                    "PATH=/system/bin:/system/xbin",
                    "ANDROID_DATA=/data",
                    "ANDROID_ROOT=/system"
                )

                val process = Runtime.getRuntime().exec(
                    arrayOf("/system/bin/sh", "-c", command),
                    env,
                    currentWorkingDir
                )

                val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
                val errorReader = java.io.BufferedReader(java.io.InputStreamReader(process.errorStream))

                val outputLines = mutableListOf<String>()
                val errorLines = mutableListOf<String>()

                // Read output in real-time
                Thread {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        outputLines.add(line!!)
                        runOnUiThread { appendTerminalOutput(line!! + "\n") }
                    }
                }.start()

                Thread {
                    var line: String?
                    while (errorReader.readLine().also { line = it } != null) {
                        errorLines.add(line!!)
                        runOnUiThread { appendTerminalOutput(line!! + "\n") }
                    }
                }.start()

                val exitCode = process.waitFor()
                runOnUiThread {
                    if (exitCode != 0) {
                        appendTerminalOutput("Command exited with code: $exitCode\n")

                        // Check if this is a gradle build command and auto-fix is enabled
                        if (autoFixErrors && command.contains("gradle") && command.contains("assemble")) {
                            val geminiKey = prefs.getString("gemini_api_key", "")
                            if (!geminiKey.isNullOrEmpty()) {
                                appendTerminalOutput("\n🤖 Gemini is analyzing the error...\n")
                                analyzeAndFixBuildError(errorLines.joinToString("\n"), command)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { appendTerminalOutput("Error: ${e.message}\n") }
            }
        }.start()
    }

    private fun analyzeAndFixBuildError(errorLog: String, originalCommand: String) {
        Thread {
            try {
                val geminiKey = prefs.getString("gemini_api_key", "") ?: return@Thread

                val prompt = """
You are an expert Android/Kotlin developer. Analyze this build error and provide a fix.

Build command: $originalCommand
Working directory: ${currentWorkingDir.absolutePath}

Error log:
```
$errorLog
```

Provide your response in this exact format:
FILE_PATH: /path/to/file.kt
ERROR: Brief description of the error
FIX: Detailed explanation of the fix
CODE:
```
// The exact code that needs to be changed or added
```

If multiple files need to be fixed, provide each one separately with the same format.
                """.trimIndent()

                runOnUiThread {
                    appendTerminalOutput("🔍 Sending error to Gemini for analysis...\n")
                }

                val response = callGeminiApi(geminiKey, prompt)

                runOnUiThread {
                    appendTerminalOutput("\n📝 Gemini's Analysis:\n")
                    appendTerminalOutput("$response\n\n")
                    showFixApprovalDialog(response, originalCommand)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    appendTerminalOutput("❌ Gemini analysis failed: ${e.message}\n")
                }
            }
        }.start()
    }

    private fun callGeminiApi(apiKey: String, prompt: String): String {
        val url = java.net.URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent?key=$apiKey")
        val connection = url.openConnection() as java.net.HttpURLConnection

        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true

        val jsonBody = """
        {
            "contents": [{
                "parts": [{
                    "text": ${org.json.JSONObject.quote(prompt)}
                }]
            }],
            "generationConfig": {
                "temperature": 0.2,
                "maxOutputTokens": 2048
            }
        }
        """.trimIndent()

        connection.outputStream.write(jsonBody.toByteArray())

        val responseCode = connection.responseCode
        if (responseCode == 200) {
            val reader = java.io.BufferedReader(java.io.InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()

            // Parse JSON response
            val textStart = response.indexOf("\"text\": \"") + 9
            val textEnd = response.indexOf("\"", textStart)
            return if (textStart > 8 && textEnd > textStart) {
                response.substring(textStart, textEnd)
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
            } else {
                "Could not parse response"
            }
        } else {
            val errorReader = java.io.BufferedReader(java.io.InputStreamReader(connection.errorStream))
            val error = errorReader.readText()
            errorReader.close()
            throw Exception("API Error $responseCode: $error")
        }
    }

    private fun showFixApprovalDialog(geminiResponse: String, originalCommand: String) {
        AlertDialog.Builder(this)
            .setTitle("🤖 Gemini's Fix Suggestion")
            .setMessage("Gemini analyzed the error and suggests a fix.\n\nDo you want to:\n1. Apply the fix\n2. Build again\n3. Commit & push if successful?")
            .setPositiveButton("Auto-fix & Build") { _, _ ->
                applyGeminiFix(geminiResponse, originalCommand)
            }
            .setNeutralButton("Just Build Again") { _, _ ->
                executeTermuxCommand(originalCommand, autoFixErrors = false)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyGeminiFix(geminiResponse: String, originalCommand: String) {
        appendTerminalOutput("\n🔧 Applying Gemini's fix...\n")

        try {
            // Parse the response to extract file path and code
            // This is a simple parser - in production you'd want something more robust
            val lines = geminiResponse.lines()
            var filePath = ""
            var inCodeBlock = false
            val codeLines = mutableListOf<String>()

            for (line in lines) {
                when {
                    line.startsWith("FILE_PATH:") -> {
                        filePath = line.substringAfter("FILE_PATH:").trim()
                    }
                    line.trim() == "```" || line.trim().startsWith("```kotlin") || line.trim().startsWith("```java") -> {
                        inCodeBlock = !inCodeBlock
                    }
                    inCodeBlock -> {
                        codeLines.add(line)
                    }
                }
            }

            if (filePath.isNotEmpty() && codeLines.isNotEmpty()) {
                val fixedCode = codeLines.joinToString("\n")
                val file = java.io.File(filePath)

                if (file.exists()) {
                    // Backup original file
                    val backup = java.io.File("$filePath.backup")
                    file.copyTo(backup, overwrite = true)

                    // Apply fix (this is simplified - in reality you'd want more sophisticated code merging)
                    file.writeText(fixedCode)

                    appendTerminalOutput("✅ Applied fix to: $filePath\n")
                    appendTerminalOutput("📁 Backup saved: $filePath.backup\n\n")

                    // Rebuild
                    appendTerminalOutput("🔨 Rebuilding...\n")
                    executeTermuxCommandWithCallback(originalCommand,
                        onSuccess = {
                            appendTerminalOutput("\n✅ Build successful!\n")
                            askToCommitAndPush()
                        },
                        onFailure = {
                            appendTerminalOutput("\n❌ Build still failed. You may need to manually review the fix.\n")
                        }
                    )
                } else {
                    appendTerminalOutput("❌ File not found: $filePath\n")
                }
            } else {
                appendTerminalOutput("⚠️ Could not parse fix from Gemini's response\n")
                appendTerminalOutput("Please apply the fix manually\n")
            }
        } catch (e: Exception) {
            appendTerminalOutput("❌ Error applying fix: ${e.message}\n")
        }
    }

    private fun executeTermuxCommandWithCallback(command: String, onSuccess: () -> Unit, onFailure: () -> Unit) {
        Thread {
            try {
                val termuxPath = "/data/data/com.termux/files/usr/bin"
                val env = arrayOf(
                    "PATH=$termuxPath:/system/bin:/system/xbin",
                    "HOME=/data/data/com.termux/files/home",
                    "TMPDIR=/data/data/com.termux/files/usr/tmp",
                    "PREFIX=/data/data/com.termux/files/usr",
                    "ANDROID_DATA=/data",
                    "ANDROID_ROOT=/system"
                )

                val process = Runtime.getRuntime().exec(command, env, currentWorkingDir)

                val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
                val errorReader = java.io.BufferedReader(java.io.InputStreamReader(process.errorStream))

                Thread {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        runOnUiThread { appendTerminalOutput(line!! + "\n") }
                    }
                }.start()

                Thread {
                    var line: String?
                    while (errorReader.readLine().also { line = it } != null) {
                        runOnUiThread { appendTerminalOutput(line!! + "\n") }
                    }
                }.start()

                val exitCode = process.waitFor()
                runOnUiThread {
                    if (exitCode == 0) {
                        onSuccess()
                    } else {
                        appendTerminalOutput("Command exited with code: $exitCode\n")
                        onFailure()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    appendTerminalOutput("Error: ${e.message}\n")
                    onFailure()
                }
            }
        }.start()
    }

    private fun askToCommitAndPush() {
        AlertDialog.Builder(this)
            .setTitle("✅ Build Successful!")
            .setMessage("Do you want to commit and push the changes to GitHub?")
            .setPositiveButton("Commit & Push") { _, _ ->
                val commitMsg = "Auto-fix build errors with Gemini AI"
                appendTerminalOutput("\n📤 Committing and pushing...\n")
                executeTermuxCommand("git add .")
                Thread.sleep(1000)
                executeTermuxCommand("git commit -m \"$commitMsg\"")
                Thread.sleep(1000)
                executeTermuxCommand("git push")
                appendTerminalOutput("\n🎉 All done! Changes pushed to GitHub.\n")
            }
            .setNegativeButton("Not Now", null)
            .show()
    }

    private fun showGitConfigDialog() {
        val nameInput = EditText(this).apply {
            hint = "Your Name"
        }
        val emailInput = EditText(this).apply {
            hint = "your.email@example.com"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }

        // Load saved values
        val savedName = prefs.getString("git_user_name", "")
        val savedEmail = prefs.getString("git_user_email", "")
        nameInput.setText(savedName)
        emailInput.setText(savedEmail)

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
            addView(nameInput)
            addView(emailInput)
        }

        AlertDialog.Builder(this)
            .setTitle("Git Configuration")
            .setMessage("Setup your git user info for commits")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString()
                val email = emailInput.text.toString()

                if (name.isNotEmpty() && email.isNotEmpty()) {
                    // Save to preferences
                    prefs.edit()
                        .putString("git_user_name", name)
                        .putString("git_user_email", email)
                        .apply()

                    // Execute git config commands
                    executeTermuxCommand("git config --global user.name \"$name\"")
                    executeTermuxCommand("git config --global user.email \"$email\"")

                    appendTerminalOutput("Git config saved:\n")
                    appendTerminalOutput("  user.name = $name\n")
                    appendTerminalOutput("  user.email = $email\n")
                } else {
                    appendTerminalOutput("Error: Name and email are required\n")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun appendTerminalOutput(text: String) {
        binding.terminalOutput.append(text)
        // Auto-scroll to bottom
        binding.terminalOutput.post {
            val scrollView = binding.terminalOutput.parent as? ScrollView
            scrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun executeListFiles(path: String) {
        try {
            val dir = if (path == ".") {
                currentWorkingDir
            } else if (path.startsWith("/")) {
                java.io.File(path)
            } else {
                java.io.File(currentWorkingDir, path)
            }

            if (dir.exists() && dir.isDirectory) {
                val files = dir.listFiles()
                if (files != null && files.isNotEmpty()) {
                    files.sortedWith(compareBy({ !it.isDirectory }, { it.name })).forEach { file ->
                        val type = if (file.isDirectory) "d" else "-"
                        val size = if (file.isFile) file.length() else 0
                        val sizeStr = when {
                            size > 1024 * 1024 -> "${size / (1024 * 1024)}M"
                            size > 1024 -> "${size / 1024}K"
                            else -> "${size}B"
                        }
                        appendTerminalOutput("$type  ${file.name.padEnd(30)}  $sizeStr\n")
                    }
                } else {
                    appendTerminalOutput("Empty directory\n")
                }
            } else {
                appendTerminalOutput("Error: Directory not found: $path\n")
            }
        } catch (e: Exception) {
            appendTerminalOutput("Error: ${e.message}\n")
        }
    }

    private fun executeCatFile(filePath: String) {
        try {
            val file = if (filePath.startsWith("/")) {
                java.io.File(filePath)
            } else {
                java.io.File(currentWorkingDir, filePath)
            }

            if (file.exists() && file.isFile) {
                val content = file.readText()
                appendTerminalOutput(content + "\n")
            } else {
                appendTerminalOutput("Error: File not found: $filePath\n")
            }
        } catch (e: Exception) {
            appendTerminalOutput("Error: ${e.message}\n")
        }
    }

    private fun showGitCloneDialog() {
        val editText = EditText(this).apply {
            hint = "Enter Git repository URL"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }

        AlertDialog.Builder(this)
            .setTitle("Git Clone")
            .setMessage("Clone a GitHub repository")
            .setView(editText)
            .setPositiveButton("Clone") { _, _ ->
                val url = editText.text.toString().trim()
                if (url.isNotEmpty()) {
                    if (binding.terminalPanel.visibility == View.GONE) {
                        toggleTerminal()
                    }
                    executeTermuxCommand("git clone $url")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadFolderTree() {
        binding.folderTreeContainer.removeAllViews()

        // Add "Quick Jump" button
        val quickJumpButton = com.google.android.material.button.MaterialButton(this).apply {
            text = "⚡ Quick Jump"
            textSize = 10f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#FF555555"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8
            }
            setOnClickListener {
                showFolderPicker()
            }
        }
        binding.folderTreeContainer.addView(quickJumpButton)

        // Current directory label
        val currentDirLabel = android.widget.TextView(this).apply {
            text = "Current: ${currentWorkingDir.name}"
            textSize = 10f
            setTextColor(android.graphics.Color.parseColor("#FFAAAAAA"))
            setPadding(8, 8, 8, 8)
        }
        binding.folderTreeContainer.addView(currentDirLabel)

        // Parent directory button
        if (currentWorkingDir.parent != null) {
            val parentButton = createFolderTreeItem("..", currentWorkingDir.parent!!, true)
            binding.folderTreeContainer.addView(parentButton)
        }

        // List files and folders
        try {
            val files = currentWorkingDir.listFiles()?.sortedWith(
                compareBy({ !it.isDirectory }, { it.name.lowercase() })
            )

            files?.forEach { file ->
                val item = createFolderTreeItem(file.name, file.absolutePath, file.isDirectory)
                binding.folderTreeContainer.addView(item)
            }
        } catch (e: Exception) {
            val errorText = android.widget.TextView(this).apply {
                text = "Error: ${e.message}"
                textSize = 10f
                setTextColor(android.graphics.Color.RED)
                setPadding(8, 8, 8, 8)
            }
            binding.folderTreeContainer.addView(errorText)
        }
    }

    private fun createFolderTreeItem(name: String, path: String, isDirectory: Boolean): android.view.View {
        val button = com.google.android.material.button.MaterialButton(this).apply {
            val icon = if (isDirectory) "📁" else {
                when {
                    name.endsWith(".apk") -> "📦"
                    name.endsWith(".txt") || name.endsWith(".md") -> "📄"
                    name.endsWith(".kt") || name.endsWith(".java") -> "💻"
                    name.endsWith(".xml") -> "🔧"
                    name.endsWith(".gradle") -> "🔨"
                    else -> "📄"
                }
            }
            text = "$icon ${name.take(15)}${if (name.length > 15) "..." else ""}"
            textSize = 9f
            setTextColor(
                if (isDirectory) android.graphics.Color.parseColor("#FF00FF00")
                else android.graphics.Color.WHITE
            )
            setBackgroundColor(
                if (selectedFolderPath == path) android.graphics.Color.parseColor("#FF555555")
                else android.graphics.Color.parseColor("#FF2C2C2C")
            )
            strokeColor = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#FF444444")
            )
            strokeWidth = 1
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 4
            }

            setOnClickListener {
                if (isDirectory) {
                    // Navigate into folder immediately and refresh tree
                    currentWorkingDir = java.io.File(path)
                    appendTerminalOutput("cd ${currentWorkingDir.absolutePath}\n")
                    loadFolderTree()
                } else if (name.endsWith(".apk")) {
                    // Install APK
                    installApk(path)
                } else {
                    // Show file options
                    showFileOptions(name, path)
                }
            }

            setOnLongClickListener {
                if (isDirectory) {
                    // Long press on folder: show folder options
                    showFolderOptions(name, path)
                } else {
                    // Long press on file: show file options
                    showFileOptions(name, path)
                }
                true
            }
        }
        return button
    }

    private fun showFolderNavigationDialog() {
        // Build recursive folder list
        val folders = mutableListOf<Pair<String, String>>()

        // Add parent directory if available
        currentWorkingDir.parent?.let { parentPath ->
            folders.add(parentPath to "⬆️ .. (Parent)")
        }

        // Add current directory subdirectories recursively
        try {
            addFoldersRecursively(currentWorkingDir, folders, depth = 0, maxDepth = 2)
        } catch (e: Exception) {
            Toast.makeText(this, "Error reading folders: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }

        if (folders.isEmpty()) {
            Toast.makeText(this, "No folders found", Toast.LENGTH_SHORT).show()
            return
        }

        val options = folders.map { it.second }.toMutableList()
        options.add("✏️ Enter path manually...")

        AlertDialog.Builder(this)
            .setTitle("Navigate to Folder\n📍 ${currentWorkingDir.absolutePath}")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == options.size - 1) {
                    // Enter path manually
                    showManualPathInput()
                } else {
                    val targetPath = folders[which].first
                    executeChangeDirectory(targetPath)
                    loadFolderTree()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addFoldersRecursively(dir: java.io.File, folders: MutableList<Pair<String, String>>, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) return

        try {
            val subdirs = dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() } ?: return

            subdirs.forEach { subdir ->
                val indent = "  ".repeat(depth)
                val icon = if (depth == 0) "📁" else "  📂"
                folders.add(subdir.absolutePath to "$indent$icon ${subdir.name}")

                // Recursively add subfolders (but limit to avoid too many items)
                if (depth < maxDepth && subdirs.size < 20) {
                    addFoldersRecursively(subdir, folders, depth + 1, maxDepth)
                }
            }
        } catch (e: Exception) {
            // Skip folders we can't read
        }
    }

    private fun showManualPathInput() {
        val pathInput = EditText(this).apply {
            hint = "Enter full path (e.g., /storage/emulated/0/Download)"
            setText(currentWorkingDir.absolutePath)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            selectAll()
        }

        val recentPaths = getRecentPaths()
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
            addView(pathInput)

            if (recentPaths.isNotEmpty()) {
                addView(android.widget.TextView(this@MainActivity).apply {
                    text = "\nRecent paths:"
                    textSize = 12f
                    setTextColor(android.graphics.Color.GRAY)
                    setPadding(0, 16, 0, 8)
                })

                recentPaths.take(3).forEach { path ->
                    addView(com.google.android.material.button.MaterialButton(this@MainActivity).apply {
                        text = path
                        textSize = 10f
                        setOnClickListener {
                            pathInput.setText(path)
                        }
                    })
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Enter Path")
            .setView(layout)
            .setPositiveButton("Go") { _, _ ->
                val path = pathInput.text.toString().trim()
                if (path.isNotEmpty()) {
                    saveRecentPath(path)
                    executeChangeDirectory(path)
                    loadFolderTree()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getRecentPaths(): List<String> {
        val paths = mutableListOf<String>()
        for (i in 0 until 5) {
            prefs.getString("recent_path_$i", null)?.let { paths.add(it) }
        }
        return paths
    }

    private fun saveRecentPath(path: String) {
        val recentPaths = getRecentPaths().toMutableList()
        recentPaths.remove(path)
        recentPaths.add(0, path)

        val editor = prefs.edit()
        recentPaths.take(5).forEachIndexed { index, p ->
            editor.putString("recent_path_$index", p)
        }
        editor.apply()
    }

    private fun showFolderPicker() {
        val commonFolders = arrayOf(
            "/storage/emulated/0" to "📱 Internal Storage",
            "/storage/emulated/0/Download" to "📥 Downloads",
            "/storage/emulated/0/Documents" to "📄 Documents",
            "/data/data/com.termux/files/home" to "🖥️ Termux Home",
            "/storage/emulated/0/Download/GitHubProjects" to "💻 GitHub Projects",
            currentWorkingDir.absolutePath to "📍 Current (${currentWorkingDir.name})"
        )

        AlertDialog.Builder(this)
            .setTitle("Quick Jump")
            .setItems(commonFolders.map { it.second }.toTypedArray()) { _, which ->
                val targetPath = commonFolders[which].first
                executeChangeDirectory(targetPath)
                loadFolderTree()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFolderOptions(folderName: String, folderPath: String) {
        val options = arrayOf(
            "Open (cd)",
            "Delete Folder",
            "Copy Path"
        )

        AlertDialog.Builder(this)
            .setTitle("📁 $folderName")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        executeChangeDirectory(folderPath)
                        loadFolderTree()
                    }
                    1 -> {
                        AlertDialog.Builder(this)
                            .setTitle("Delete Folder?")
                            .setMessage("Are you sure you want to delete $folderName and all its contents?")
                            .setPositiveButton("Delete") { _, _ ->
                                val folder = java.io.File(folderPath)
                                if (folder.deleteRecursively()) {
                                    Toast.makeText(this, "Folder deleted", Toast.LENGTH_SHORT).show()
                                    loadFolderTree()
                                } else {
                                    Toast.makeText(this, "Failed to delete folder", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    2 -> {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Folder Path", folderPath)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(this, "Path copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun installApk(apkPath: String) {
        try {
            val apkFile = java.io.File(apkPath)
            if (!apkFile.exists()) {
                Toast.makeText(this, "APK file not found", Toast.LENGTH_SHORT).show()
                return
            }

            val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(intent)
            Toast.makeText(this, "Opening APK installer...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to install APK: ${e.message}", Toast.LENGTH_LONG).show()
            appendTerminalOutput("APK Install Error: ${e.message}\n")
        }
    }

    private fun showFileOptions(fileName: String, filePath: String) {
        val options = arrayOf(
            "View in Terminal (cat)",
            "Delete",
            "Copy Path"
        )

        AlertDialog.Builder(this)
            .setTitle(fileName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> executeCatFile(filePath)
                    1 -> {
                        AlertDialog.Builder(this)
                            .setTitle("Delete File?")
                            .setMessage("Are you sure you want to delete $fileName?")
                            .setPositiveButton("Delete") { _, _ ->
                                val file = java.io.File(filePath)
                                if (file.delete()) {
                                    Toast.makeText(this, "File deleted", Toast.LENGTH_SHORT).show()
                                    loadFolderTree()
                                } else {
                                    Toast.makeText(this, "Failed to delete file", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    2 -> {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("File Path", filePath)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(this, "Path copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadTerminalKeyboard() {
        binding.terminalKeyboardContainer.removeAllViews()

        val defaultKeys = listOf(
            "Tab" to "\t",
            "Ctrl+C" to "\u0003",
            "↑" to "↑",
            "↓" to "↓",
            "←" to "←",
            "→" to "→",
            "/" to "/",
            "-" to "-",
            "~" to "~",
            "|" to "|"
        )

        // Add default keys
        defaultKeys.forEach { (label, value) ->
            addTerminalKeyButton(label, value, isCustom = false)
        }

        // Add custom keys
        val customKeyCount = prefs.getInt("terminal_key_count", 0)
        for (i in 0 until customKeyCount) {
            val label = prefs.getString("terminal_key_label_$i", "") ?: ""
            val value = prefs.getString("terminal_key_value_$i", "") ?: ""
            if (label.isNotEmpty() && value.isNotEmpty()) {
                addTerminalKeyButton(label, value, isCustom = true, index = i)
            }
        }

        // Add "+" button
        val addButton = com.google.android.material.button.MaterialButton(this).apply {
            text = "+"
            textSize = 12f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#FF555555"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 4
            }
            setOnClickListener {
                showAddTerminalKeyDialog()
            }
        }
        binding.terminalKeyboardContainer.addView(addButton)

        // Add settings button
        val settingsButton = com.google.android.material.button.MaterialButton(this).apply {
            text = "⚙️"
            textSize = 10f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#FF555555"))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                showTerminalKeyboardSettings()
            }
        }
        binding.terminalKeyboardContainer.addView(settingsButton)
    }

    private fun addTerminalKeyButton(label: String, value: String, isCustom: Boolean, index: Int = -1) {
        val button = com.google.android.material.button.MaterialButton(this).apply {
            text = label
            textSize = 10f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(
                if (isCustom) android.graphics.Color.parseColor("#FF2C5F2D")
                else android.graphics.Color.parseColor("#FF444444")
            )
            strokeColor = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#FF666666")
            )
            strokeWidth = 1
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 4
            }
            setOnClickListener {
                insertTextAtCursor(value)
            }
            if (isCustom) {
                setOnLongClickListener {
                    showEditTerminalKeyDialog(label, value, index)
                    true
                }
            }
        }
        binding.terminalKeyboardContainer.addView(button)
    }

    private fun insertTextAtCursor(text: String) {
        val start = binding.terminalInput.selectionStart
        val end = binding.terminalInput.selectionEnd
        val currentText = binding.terminalInput.text.toString()

        val newText = currentText.substring(0, start) + text + currentText.substring(end)
        binding.terminalInput.setText(newText)
        binding.terminalInput.setSelection(start + text.length)
    }

    private fun showAddTerminalKeyDialog() {
        val labelInput = EditText(this).apply {
            hint = "Label (e.g., 'Esc', '..')"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val valueInput = EditText(this).apply {
            hint = "Value (e.g., text or \\n for newline)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
            addView(android.widget.TextView(this@MainActivity).apply {
                text = "Add Custom Terminal Key"
                textSize = 14f
                setPadding(0, 0, 0, 16)
            })
            addView(labelInput)
            addView(valueInput)
            addView(android.widget.TextView(this@MainActivity).apply {
                text = "\nSpecial sequences:\n\\n = newline\n\\t = tab\n\\u0003 = Ctrl+C"
                textSize = 11f
                setTextColor(android.graphics.Color.GRAY)
                setPadding(0, 16, 0, 0)
            })
        }

        AlertDialog.Builder(this)
            .setTitle("Add Terminal Key")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                var label = labelInput.text.toString().trim()
                var value = valueInput.text.toString()

                // Process escape sequences
                value = value.replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\r", "\r")

                if (label.isNotEmpty() && value.isNotEmpty()) {
                    val keyCount = prefs.getInt("terminal_key_count", 0)
                    prefs.edit()
                        .putString("terminal_key_label_$keyCount", label)
                        .putString("terminal_key_value_$keyCount", value)
                        .putInt("terminal_key_count", keyCount + 1)
                        .apply()

                    Toast.makeText(this, "Terminal key added", Toast.LENGTH_SHORT).show()
                    loadTerminalKeyboard()
                } else {
                    Toast.makeText(this, "Label and value required", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditTerminalKeyDialog(currentLabel: String, currentValue: String, index: Int) {
        val labelInput = EditText(this).apply {
            hint = "Label"
            setText(currentLabel)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val valueInput = EditText(this).apply {
            hint = "Value"
            setText(currentValue)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
            addView(labelInput)
            addView(valueInput)
        }

        AlertDialog.Builder(this)
            .setTitle("Edit Terminal Key")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                var label = labelInput.text.toString().trim()
                var value = valueInput.text.toString()

                // Process escape sequences
                value = value.replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\r", "\r")

                if (label.isNotEmpty() && value.isNotEmpty()) {
                    prefs.edit()
                        .putString("terminal_key_label_$index", label)
                        .putString("terminal_key_value_$index", value)
                        .apply()

                    Toast.makeText(this, "Terminal key updated", Toast.LENGTH_SHORT).show()
                    loadTerminalKeyboard()
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete") { _, _ ->
                deleteTerminalKey(index)
            }
            .show()
    }

    private fun deleteTerminalKey(index: Int) {
        val keyCount = prefs.getInt("terminal_key_count", 0)
        val editor = prefs.edit()

        // Shift all keys after this one down
        for (i in index until keyCount - 1) {
            val nextLabel = prefs.getString("terminal_key_label_${i+1}", "")
            val nextValue = prefs.getString("terminal_key_value_${i+1}", "")
            editor.putString("terminal_key_label_$i", nextLabel)
            editor.putString("terminal_key_value_$i", nextValue)
        }

        // Remove the last one
        editor.remove("terminal_key_label_${keyCount - 1}")
        editor.remove("terminal_key_value_${keyCount - 1}")
        editor.putInt("terminal_key_count", keyCount - 1)
        editor.apply()

        Toast.makeText(this, "Terminal key deleted", Toast.LENGTH_SHORT).show()
        loadTerminalKeyboard()
    }

    private fun showTerminalKeyboardSettings() {
        val customKeyCount = prefs.getInt("terminal_key_count", 0)

        val message = """
Terminal keyboard provides quick access to special characters and shortcuts.

Default keys (Gray):
• Tab, Ctrl+C, Arrow keys
• Special chars: /, -, ~, |

Custom keys (Green): $customKeyCount
• Click "+" to add new keys
• Long-press custom keys to edit/delete

Special sequences you can use:
• \n = newline
• \t = tab
• \u0003 = Ctrl+C
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Terminal Keyboard Help")
            .setMessage(message)
            .setPositiveButton("Add Key") { _, _ ->
                showAddTerminalKeyDialog()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showClaudeSetupDialog() {
        val message = """
Claude Code Setup Guide

1️⃣ Install Node.js in Termux:
   pkg install nodejs

2️⃣ Install Claude Code globally:
   npm install -g @anthropic-ai/claude-code

3️⃣ Verify installation:
   claude --version

4️⃣ Login to Claude:
   claude login

5️⃣ Start using Claude Code:
   claude

Note: You need a Claude Pro or API account to use Claude Code.

Would you like to run the installation commands now?
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Claude Code Setup")
            .setMessage(message)
            .setPositiveButton("Install Now") { _, _ ->
                appendTerminalOutput("\n📦 Installing Node.js...\n")
                executeTermuxCommand("pkg install nodejs -y")
                Thread.sleep(2000)
                appendTerminalOutput("\n📦 Installing Claude Code...\n")
                executeTermuxCommand("npm install -g @anthropic-ai/claude-code")
            }
            .setNeutralButton("Manual Setup") { _, _ ->
                appendTerminalOutput("\nManual setup steps:\n")
                appendTerminalOutput("1. pkg install nodejs\n")
                appendTerminalOutput("2. npm install -g @anthropic-ai/claude-code\n")
                appendTerminalOutput("3. claude login\n")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkAndRunClaude(command: String) {
        Thread {
            try {
                // Check if Termux is installed
                val isTermuxInstalled = try {
                    packageManager.getPackageInfo("com.termux", 0)
                    true
                } catch (e: Exception) {
                    false
                }

                if (!isTermuxInstalled) {
                    runOnUiThread {
                        appendTerminalOutput("❌ Termux not installed!\n")
                        appendTerminalOutput("Claude Code requires Termux to run.\n")
                        appendTerminalOutput("Download: https://f-droid.org/en/packages/com.termux/\n")
                    }
                    return@Thread
                }

                // Just run the command - Termux will handle it
                runOnUiThread {
                    appendTerminalOutput("🤖 Running Claude Code in Termux...\n")
                    appendTerminalOutput("Note: Output will appear in Termux window\n")
                    executeTermuxCommand(command)
                }

            } catch (e: Exception) {
                runOnUiThread {
                    appendTerminalOutput("Error: ${e.message}\n")
                    appendTerminalOutput("Run 'setup-claude' to install Node.js and Claude Code\n")
                }
            }
        }.start()
    }

    private fun setupSwipeGestures() {
        var startX = 0f
        var startY = 0f
        val swipeThreshold = 100
        val swipeVelocityThreshold = 100

        binding.webView.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val endX = event.x
                    val endY = event.y
                    val deltaX = endX - startX
                    val deltaY = endY - startY

                    if (Math.abs(deltaX) > Math.abs(deltaY) && Math.abs(deltaX) > swipeThreshold) {
                        if (deltaX > 0) {
                            // Swipe right - go back
                            if (binding.webView.canGoBack()) {
                                binding.webView.goBack()
                            }
                        } else {
                            // Swipe left - go forward
                            if (binding.webView.canGoForward()) {
                                binding.webView.goForward()
                            }
                        }
                    }
                }
            }
            false
        }
    }

    private fun takeScreenshot() {
        try {
            // Capture the entire activity view
            val rootView = window.decorView.rootView
            rootView.isDrawingCacheEnabled = true
            val bitmap = android.graphics.Bitmap.createBitmap(rootView.drawingCache)
            rootView.isDrawingCacheEnabled = false

            // Show preview dialog
            showScreenshotPreview(bitmap)

        } catch (e: Exception) {
            Toast.makeText(this, "Screenshot failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showScreenshotPreview(bitmap: android.graphics.Bitmap) {
        val imageView = android.widget.ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }

        val scrollView = android.widget.ScrollView(this).apply {
            addView(imageView)
        }

        AlertDialog.Builder(this)
            .setTitle("📸 Screenshot Preview")
            .setView(scrollView)
            .setPositiveButton("Save & Send to Claude") { _, _ ->
                saveScreenshotAndInsertPath(bitmap)
            }
            .setNeutralButton("Just Save") { _, _ ->
                saveScreenshot(bitmap, insertPath = false)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveScreenshotAndInsertPath(bitmap: android.graphics.Bitmap) {
        val path = saveScreenshot(bitmap, insertPath = true)
        if (path != null) {
            // Open terminal if not open
            if (binding.terminalPanel.visibility == View.GONE) {
                toggleTerminal()
            }

            // Insert Claude command with image path
            val claudeCommand = "claude --image \"$path\" "
            binding.terminalInput.setText(claudeCommand)
            binding.terminalInput.setSelection(claudeCommand.length)
            binding.terminalInput.requestFocus()

            Toast.makeText(this, "Screenshot saved! Path inserted in terminal", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveScreenshot(bitmap: android.graphics.Bitmap, insertPath: Boolean): String? {
        try {
            // Create screenshots directory
            val picturesDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_PICTURES
            )
            val screenshotsDir = java.io.File(picturesDir, "Screenshots")
            if (!screenshotsDir.exists()) {
                screenshotsDir.mkdirs()
            }

            // Generate filename with timestamp
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val filename = "Screenshot_$timestamp.png"
            val file = java.io.File(screenshotsDir, filename)

            // Save bitmap to file
            java.io.FileOutputStream(file).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }

            // Notify media scanner
            val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            intent.data = Uri.fromFile(file)
            sendBroadcast(intent)

            if (!insertPath) {
                Toast.makeText(this, "Screenshot saved to ${file.absolutePath}", Toast.LENGTH_LONG).show()
            }

            return file.absolutePath

        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save screenshot: ${e.message}", Toast.LENGTH_SHORT).show()
            return null
        }
    }

    override fun onBackPressed() {
        if (binding.terminalPanel.visibility == View.VISIBLE) {
            binding.terminalPanel.visibility = View.GONE
        } else if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        binding.webView.restoreState(savedInstanceState)
    }
}
