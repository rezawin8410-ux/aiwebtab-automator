package com.example.aiwebtabautomator

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private data class TabState(
        val webView: WebView,
        var title: String,
        var url: String
    )

    companion object {
        private const val MAX_TABS = 5
        private const val MAX_AUTOMATION_ATTEMPTS = 3
        private const val DEFAULT_URL = "https://chatgpt.com/"
        private const val NOTIFICATION_REQUEST = 301
    }

    private val tabs = mutableListOf<TabState>()
    private var activeTabIndex = -1
    private var urlBar: EditText? = null
    private lateinit var tabStrip: LinearLayout
    private lateinit var contentFrame: FrameLayout
    private lateinit var setupOverlay: SetupOverlayView
    private lateinit var statusText: TextView
    private val helper = WebViewAutomationHelper()
    private val commandQueue = ArrayDeque<AutomationCommand>()
    private var automationBusy = false
    private var setupFirstPoint: Pair<Float, Float>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestNotificationPermissionIfNeeded()
        scheduleFileFallback()
        AutomationService.start(this)
        addTab(DEFAULT_URL)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val tab = tabs.getOrNull(activeTabIndex)
                if (tab?.webView?.canGoBack() == true) tab.webView.goBack() else finish()
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AutomationBus.events.collect { command ->
                        CommandStore.remove(this@MainActivity, command.id)
                        enqueueAutomation(command)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Commands accepted while the Activity was not visible are persisted by the service.
        CommandStore.all(this).forEach { command ->
            CommandStore.remove(this, command.id)
            enqueueAutomation(command)
        }
    }

    override fun onDestroy() {
        helper.clearCallbacks()
        if (isFinishing) {
            tabs.forEach { destroyWebView(it.webView) }
        }
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val browserBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 4, 4, 4)
        }
        val back = smallButton("‹") { activeWebView()?.takeIf { it.canGoBack() }?.goBack() }
        val forward = smallButton("›") { activeWebView()?.takeIf { it.canGoForward() }?.goForward() }
        val reload = smallButton("↻") { activeWebView()?.reload() }
        urlBar = EditText(this).apply {
            hint = "https://example.com"
            singleLine = true
            setTextSize(14f)
            setPadding(12, 0, 12, 0)
            setOnEditorActionListener { _, _, _ -> navigateFromUrlBar(); true }
        }
        val go = smallButton("GO") { navigateFromUrlBar() }
        browserBar.addView(back, weightParams(0.08f))
        browserBar.addView(forward, weightParams(0.08f))
        browserBar.addView(reload, weightParams(0.08f))
        browserBar.addView(urlBar, weightParams(0.64f))
        browserBar.addView(go, weightParams(0.12f))
        root.addView(browserBar, LinearLayout.LayoutParams(-1, dp(52)))

        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(0xFFECEFF3.toInt())
        }
        tabStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabScroll.addView(tabStrip, ViewGroup.LayoutParams(-2, dp(44)))
        root.addView(tabScroll)

        contentFrame = FrameLayout(this)
        root.addView(contentFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 4, 4, 4)
        }
        val setup = smallButton("SETUP") { beginSetup() }
        val add = smallButton("+") { addTab() }
        val mappings = smallButton("MAPS") { showMappingsDialog() }
        statusText = TextView(this).apply {
            text = "Bridge: starting"
            setTextSize(11f)
            setTextColor(0xFF44515F.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 0, 4, 0)
        }
        bottom.addView(setup, weightParams(0.25f))
        bottom.addView(add, weightParams(0.12f))
        bottom.addView(mappings, weightParams(0.22f))
        bottom.addView(statusText, weightParams(0.41f))
        root.addView(bottom, LinearLayout.LayoutParams(-1, dp(48)))

        setupOverlay = SetupOverlayView(this).apply {
            visibility = View.GONE
            onPointSelected = { step, x, y -> onSetupPoint(step, x, y) }
        }
        contentFrame.addView(setupOverlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun addTab(initialUrl: String = "about:blank") {
        if (tabs.size >= MAX_TABS) {
            toast("Maximum of $MAX_TABS tabs")
            return
        }
        val webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = false
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = true
            settings.setGeolocationEnabled(false)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    tabs.firstOrNull { it.webView === view }?.let {
                        it.url = url
                        if (tabs.indexOf(it) == activeTabIndex) urlBar?.setText(url)
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView, title: String) {
                    tabs.firstOrNull { it.webView === view }?.let {
                        if (title.isNotBlank()) it.title = title.take(24)
                        rebuildTabStrip()
                    }
                }
            }
            // Hardware acceleration remains enabled: it is generally faster and more stable for
            // WebView on supported A-series firmware than forcing software rendering.
            CookieManager.getInstance().setAcceptCookie(true)
            loadUrl(initialUrl)
        }
        tabs += TabState(webView, "Tab ${tabs.size + 1}", initialUrl)
        if (activeTabIndex == -1) activeTabIndex = 0
        contentFrame.addView(webView, 0, FrameLayout.LayoutParams(-1, -1))
        switchToTab(tabs.lastIndex)
        rebuildTabStrip()
    }

    private fun closeTab(index: Int) {
        if (tabs.size == 1) {
            toast("Keep at least one tab open")
            return
        }
        val removed = tabs.removeAt(index)
        contentFrame.removeView(removed.webView)
        destroyWebView(removed.webView)
        activeTabIndex = when {
            activeTabIndex > index -> activeTabIndex - 1
            activeTabIndex >= tabs.size -> tabs.lastIndex
            else -> activeTabIndex
        }
        switchToTab(activeTabIndex)
        rebuildTabStrip()
    }

    private fun switchToTab(index: Int) {
        if (index !in tabs.indices) return
        tabs.forEachIndexed { i, tab ->
            val selected = i == index
            tab.webView.visibility = if (selected) View.VISIBLE else View.GONE
            if (selected) tab.webView.onResume() else tab.webView.onPause()
        }
        activeTabIndex = index
        urlBar?.setText(tabs[index].webView.url ?: tabs[index].url)
        rebuildTabStrip()
    }

    private fun rebuildTabStrip() {
        if (!::tabStrip.isInitialized) return
        tabStrip.removeAllViews()
        tabs.forEachIndexed { index, tab ->
            val label = if (index == activeTabIndex) "● ${tab.title}" else "${index + 1} ${tab.title}"
            val button = Button(this).apply {
                text = label
                setTextSize(11f)
                isAllCaps = false
                setPadding(8, 0, 8, 0)
                setOnClickListener { switchToTab(index) }
                setOnLongClickListener {
                    closeTab(index)
                    true
                }
            }
            tabStrip.addView(button, LinearLayout.LayoutParams(dp(132), -1))
        }
    }

    private fun navigateFromUrlBar() {
        val value = urlBar?.text?.toString()?.trim().orEmpty()
        if (value.isBlank()) return
        val normalized = if (value.contains("://")) value else "https://$value"
        activeWebView()?.loadUrl(normalized)
    }

    private fun beginSetup() {
        val host = normalizeHost(activeWebView()?.url)
        if (host == null) {
            toast("Open a website before mapping coordinates")
            return
        }
        setupFirstPoint = null
        setupOverlay.reset()
        setupOverlay.visibility = View.VISIBLE
        toast("Tap the input, then the send button")
    }

    private fun onSetupPoint(step: Int, x: Float, y: Float) {
        if (!setupOverlay.isShown) return
        if (step == 1) {
            setupFirstPoint = x to y
            return
        }
        val first = setupFirstPoint ?: return
        if (setupOverlay.width <= 0 || setupOverlay.height <= 0) return
        val host = normalizeHost(activeWebView()?.url) ?: return
        val mapping = CoordinateMapping(
            host = host,
            inputX = (first.first / setupOverlay.width).coerceIn(0f, 1f),
            inputY = (first.second / setupOverlay.height).coerceIn(0f, 1f),
            sendX = (x / setupOverlay.width).coerceIn(0f, 1f),
            sendY = (y / setupOverlay.height).coerceIn(0f, 1f)
        )
        AppPrefs.saveMapping(this, mapping)
        setupOverlay.visibility = View.GONE
        toast("Saved mapping for $host")
    }

    private fun showMappingsDialog() {
        val mappings = AppPrefs.mappings(this).values.sortedBy { it.host }
        if (mappings.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Automation mappings")
                .setMessage("No mappings yet. Open a site and tap SETUP.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val labels = mappings.map { "${it.host}\ninput ${percent(it.inputX)}, ${percent(it.inputY)}  send ${percent(it.sendX)}, ${percent(it.sendY)}" }
        AlertDialog.Builder(this)
            .setTitle("Mappings — tap one to edit/delete")
            .setItems(labels.toTypedArray()) { _, which ->
                val mapping = mappings[which]
                AlertDialog.Builder(this)
                    .setTitle(mapping.host)
                    .setItems(arrayOf("Edit on current tab", "Route this host to a tab", "Delete")) { _, action ->
                        when (action) {
                            0 -> beginSetup()
                            1 -> chooseRoute(mapping.host)
                            2 -> {
                                AppPrefs.deleteMapping(this, mapping.host)
                                toast("Deleted ${mapping.host}")
                            }
                        }
                    }
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun chooseRoute(host: String) {
        val labels = tabs.indices.map { "Tab ${it + 1}: ${tabs[it].title}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Route $host to")
            .setItems(labels) { _, which ->
                AppPrefs.saveRoute(this, host, which)
                toast("Future commands for $host use tab ${which + 1}")
            }
            .show()
    }

    private fun enqueueAutomation(command: AutomationCommand) {
        commandQueue.addLast(command)
        processNextAutomation()
    }

    private fun processNextAutomation() {
        if (automationBusy || commandQueue.isEmpty()) return
        automationBusy = true
        val command = commandQueue.removeFirst()
        executeAutomation(command)
    }

    private fun executeAutomation(command: AutomationCommand) {
        val target = resolveTab(command)
        if (target == null) {
            finishAutomation(command, false, "No target tab is available")
            return
        }
        switchToTab(target.first)
        val tab = target.second
        val mapping = AppPrefs.mappingFor(this, tab.webView.url ?: tab.url)
        if (mapping == null || !mappingIsValid(mapping)) {
            finishAutomation(command, false, "No valid coordinate mapping for ${normalizeHost(tab.url) ?: "this tab"}")
            return
        }
        runAutomationAttempt(command, target.first, tab, mapping, 1)
    }

    private fun runAutomationAttempt(
        command: AutomationCommand,
        tabIndex: Int,
        tab: TabState,
        mapping: CoordinateMapping,
        attempt: Int
    ) {
        statusText.text = "Sending to tab ${tabIndex + 1} (attempt $attempt/$MAX_AUTOMATION_ATTEMPTS)…"
        helper.extractLastAssistantText(tab.webView) { previous ->
            helper.sendMessage(tab.webView, mapping, command.message) { sent, error ->
                if (!sent) {
                    if (attempt < MAX_AUTOMATION_ATTEMPTS) {
                        tab.webView.postDelayed({
                            runAutomationAttempt(command, tabIndex, tab, mapping, attempt + 1)
                        }, 500L)
                    } else {
                        finishAutomation(command, false, error ?: "Input failed after three attempts")
                    }
                    return@sendMessage
                }
                statusText.text = "Waiting for response…"
                helper.waitForResult(tab.webView, previous) { success, response, waitError ->
                    finishAutomation(
                        command,
                        success,
                        if (success) response else (waitError ?: "No response")
                    )
                }
            }
        }
    }

    private fun finishAutomation(command: AutomationCommand, success: Boolean, value: String) {
        val tab = tabs.getOrNull(resolveTab(command)?.first)
        val result = AutomationResult(
            requestId = command.id,
            success = success,
            response = if (success) value else "",
            error = if (success) null else value,
            tabIndex = tabs.indexOfFirst { it === tab }.takeIf { it >= 0 },
            host = normalizeHost(tab?.webView?.url ?: tab?.url)
        )
        BridgeRepository.writeResult(this, command, result)
        statusText.text = if (success) "Done — response in outbox" else "Failed: ${value.take(36)}"
        if (!success) toast(value)
        automationBusy = false
        processNextAutomation()
    }

    private fun resolveTab(command: AutomationCommand): Pair<Int, TabState>? {
        val explicit = command.tabIndex
        if (explicit != null && explicit in tabs.indices) return explicit to tabs[explicit]
        val byHost = command.host?.let { host ->
            val routed = AppPrefs.routeFor(this, host)
            if (routed != null && routed in tabs.indices) routed to tabs[routed]
            else tabs.mapIndexedNotNull { index, tab ->
                if (normalizeHost(tab.webView.url ?: tab.url) == normalizeHost(host)) index to tab else null
            }.firstOrNull()
        }
        return byHost ?: tabs.getOrNull(activeTabIndex)?.let { activeTabIndex to it }
    }

    private fun mappingIsValid(mapping: CoordinateMapping): Boolean =
        listOf(mapping.inputX, mapping.inputY, mapping.sendX, mapping.sendY).all { it in 0f..1f }

    private fun activeWebView(): WebView? = tabs.getOrNull(activeTabIndex)?.webView

    private fun destroyWebView(webView: WebView) {
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.clearHistory()
        webView.removeAllViews()
        webView.destroy()
    }

    private fun scheduleFileFallback() {
        val request = PeriodicWorkRequestBuilder<InboxPollWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "inbox-fallback",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
        }
    }

    private fun smallButton(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text
        setTextSize(11f)
        setPadding(4, 0, 4, 0)
        setOnClickListener { action() }
    }

    private fun weightParams(weight: Float) = LinearLayout.LayoutParams(0, -1, weight)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun percent(value: Float): String = "${(value * 100).toInt()}%"
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
