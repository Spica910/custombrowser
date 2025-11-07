package com.custombrowser

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
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

    // Permission launcher for notifications (API 33+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.notification_permission_required, Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bookmarkManager = BookmarkManager(this)

        // Request notification permission for API 33+
        requestNotificationPermission()

        setupWebView()
        setupToolbar()
        setupQuickAccessButtons()

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

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
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

                // Set user agent for desktop mode option
                userAgentString = if (isDesktopMode) {
                    settings.userAgentString.replace("Mobile", "")
                } else {
                    WebSettings.getDefaultUserAgent(this@MainActivity)
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    binding.progressBar.visibility = View.GONE
                    binding.urlBar.setText(url)
                    updateBookmarkButton(url)
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
            }

            // Enable console logging for debugging
            setWebContentsDebuggingEnabled(true)

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

        // URL bar
        binding.urlBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                val input = binding.urlBar.text.toString()
                loadUrl(input)
                binding.urlBar.clearFocus()
                true
            } else {
                false
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

        // Menu button
        binding.btnMenu.setOnClickListener {
            showMenu()
        }
    }

    private fun setupQuickAccessButtons() {
        val quickAccessUrls = bookmarkManager.getQuickAccessUrls()

        binding.btnClaudeCode.setOnClickListener {
            loadUrl(quickAccessUrls["claude"] ?: "https://claude.ai/code")
        }

        binding.btnGemini.setOnClickListener {
            loadUrl(quickAccessUrls["gemini"] ?: "https://gemini.google.com")
        }

        binding.btnCodex.setOnClickListener {
            loadUrl(quickAccessUrls["codex"] ?: "https://platform.openai.com/playground")
        }

        binding.btnLocalhost.setOnClickListener {
            showLocalhostDialog()
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
            startActivity(android.content.Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (e: Exception) {
            Toast.makeText(this, "Downloads app not found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMenu() {
        val options = arrayOf(
            getString(R.string.menu_bookmarks),
            getString(R.string.menu_downloads),
            getString(R.string.menu_refresh),
            getString(R.string.menu_desktop_mode) + " (${if (isDesktopMode) "ON" else "OFF"})",
            getString(R.string.menu_settings)
        )

        AlertDialog.Builder(this)
            .setTitle("Menu")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showBookmarks()
                    1 -> openDownloads()
                    2 -> binding.webView.reload()
                    3 -> toggleDesktopMode()
                    4 -> showSettings()
                }
            }
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
        val quickAccessUrls = bookmarkManager.getQuickAccessUrls()

        AlertDialog.Builder(this)
            .setTitle(R.string.menu_settings)
            .setMessage("Configure quick access URLs")
            .setPositiveButton("Edit Claude URL") { _, _ ->
                editQuickAccessUrl("claude", quickAccessUrls["claude"] ?: "")
            }
            .setNegativeButton("Edit Gemini URL") { _, _ ->
                editQuickAccessUrl("gemini", quickAccessUrls["gemini"] ?: "")
            }
            .setNeutralButton("Edit Codex URL") { _, _ ->
                editQuickAccessUrl("codex", quickAccessUrls["codex"] ?: "")
            }
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

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
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
