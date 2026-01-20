package io.legado.app.ui.browser

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.core.view.size
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.databinding.ActivityWebViewBinding
import io.legado.app.help.http.CookieStore
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.model.Download
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.ACache
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.keepScreenOn
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.startActivity
import io.legado.app.utils.toggleSystemBar
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import android.webkit.JavascriptInterface
import io.legado.app.constant.AppLog
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.basicJs
import io.legado.app.help.webView.WebJsExtensions.Companion.nameBasic
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import androidx.lifecycle.lifecycleScope
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebViewPool
import io.legado.app.help.webView.WebViewPool.BLANK_HTML
import io.legado.app.help.webView.WebViewPool.DATA_HTML
import java.lang.ref.WeakReference
import io.legado.app.help.http.CookieManager as AppCookieManager
import androidx.core.net.toUri
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.ReadBook
import io.legado.app.help.webView.WebJsExtensions.Companion.JSBridgeResult
import io.legado.app.utils.escapeForJs
import io.legado.app.utils.NetworkUtils
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext

class WebViewActivity : VMBaseActivity<ActivityWebViewBinding, WebViewModel>() {
    companion object {
        // 是否输出日志
        var sessionShowWebLog = false
    }

    private lateinit var pooledWebView: PooledWebView
    private lateinit var currentWebView: WebView

    override val binding by viewBinding(ActivityWebViewBinding::inflate)
    override val viewModel by viewModels<WebViewModel>()
    private var customWebViewCallback: WebChromeClient.CustomViewCallback? = null
    private var webPic: String? = null
    private var isCloudflareChallenge = false
    private var isFullScreen = false
    private var isfullscreen = false
    private var needClearHistory = true
    private val saveImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ACache.get().put(imagePathKey, uri.toString())
            viewModel.saveImage(webPic, uri.toString())
        }
    }

    private fun refresh() {
        currentWebView.reload()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        // 使用WebView池获取WebView，优化性能
        pooledWebView = WebViewPool.acquire(this)
        currentWebView = pooledWebView.realWebView
        binding.webViewContainer.addView(currentWebView)

        // 确保清除历史记录
        currentWebView.post {
            currentWebView.clearHistory()
        }

        binding.titleBar.title = intent.getStringExtra("title") ?: getString(R.string.loading)
        binding.titleBar.subtitle = intent.getStringExtra("sourceName")

        viewModel.initData(intent) {
            val url = viewModel.baseUrl
            val headerMap = viewModel.headerMap
            initWebView(url, headerMap)
            val html = viewModel.html

            if (html.isNullOrEmpty()) {
                currentWebView.loadUrl(url, headerMap)
            } else {
                if (viewModel.localHtml) {
                    viewModel.source?.let {
                        val webJsExtensions = WebJsExtensions(it, this, currentWebView)
                        currentWebView.addJavascriptInterface(webJsExtensions, nameJava)
                    }
                }
                currentWebView.loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
            }
        }

        // 清除历史记录
        currentWebView.clearHistory()

        // 设置返回键处理
        onBackPressedDispatcher.addCallback(this) {
            if (binding.customWebView.size > 0) {
                // 网页全屏模式
                customWebViewCallback?.onCustomViewHidden()
                return@addCallback
            }
            if (isFullScreen) {
                // 按钮触发的全屏模式
                toggleFullScreen()
                return@addCallback
            }

            // 智能后退逻辑（来自第二版本）
            if (currentWebView.canGoBack()) {
                val list = currentWebView.copyBackForwardList()
                val size = list.size
                if (size == 1) {
                    finish()
                    return@addCallback
                }

                val currentIndex = list.currentIndex
                val currentItem = list.currentItem
                val currentUrl = currentItem?.originalUrl ?: BLANK_HTML
                val currentTitle = currentItem?.title
                var steps = 1

                for (i in currentIndex - 1 downTo 0) {
                    val item = list.getItemAtIndex(i)
                    val itemUrl = item.originalUrl
                    if (itemUrl == BLANK_HTML) {
                        finish()
                        return@addCallback
                    }
                    if (itemUrl != currentUrl || currentTitle != item.title) {
                        break
                    }
                    if (currentUrl == DATA_HTML) {
                        break
                    }
                    steps++
                }

                if (steps == size) {
                    finish()
                    return@addCallback
                }
                currentWebView.goBackOrForward(-steps)
                return@addCallback
            }
            finish()
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.web_view, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        if (viewModel.sourceOrigin.isNotEmpty()) {
            menu.findItem(R.id.menu_disable_source)?.isVisible = true
            menu.findItem(R.id.menu_delete_source)?.isVisible = true
        }
        menu.findItem(R.id.menu_show_web_log)?.isChecked = sessionShowWebLog
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_web_refresh -> refresh()
            R.id.menu_open_in_browser -> openUrl(viewModel.baseUrl)
            R.id.menu_copy_url -> sendToClip(viewModel.baseUrl)
            R.id.menu_ok -> {
                if (viewModel.sourceVerificationEnable) {
                    viewModel.saveVerificationResult(currentWebView) {
                        finish()
                    }
                } else {
                    finish()
                }
            }
            R.id.menu_full_screen -> toggleFullScreen()
            R.id.menu_show_web_log -> {
                sessionShowWebLog = !sessionShowWebLog
                item.isChecked = sessionShowWebLog
            }
            R.id.menu_disable_source -> {
                viewModel.disableSource {
                    finish()
                }
            }
            R.id.menu_delete_source -> {
                alert(R.string.draw) {
                    setMessage(getString(R.string.sure_del) + "\n" + viewModel.sourceName)
                    noButton()
                    yesButton {
                        viewModel.deleteSource {
                            finish()
                        }
                    }
                }
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    // 切换全屏模式
    private fun toggleFullScreen() {
        isFullScreen = !isFullScreen
        toggleSystemBar(!isFullScreen)
        if (isFullScreen) {
            supportActionBar?.hide()
        } else {
            supportActionBar?.show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView(url: String, headerMap: HashMap<String, String>) {
        binding.progressBar.fontColor = accentColor
        currentWebView.webChromeClient = CustomWebChromeClient()

        // 添加 JavaScript 接口
        currentWebView.addJavascriptInterface(JSInterface(this), nameBasic)
        currentWebView.webViewClient = CustomWebViewClient()

        // 完整的WebSettings配置（来自第一版本）
        currentWebView.settings.apply {
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            javaScriptEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            headerMap[AppConst.UA_NAME]?.let {
                userAgentString = it
            }
        }

        // 应用Cookie到WebView
        AppCookieManager.applyToWebView(url)

        // 长按图片保存功能
        currentWebView.setOnLongClickListener {
            val hitTestResult = currentWebView.hitTestResult
            if (hitTestResult.type == WebView.HitTestResult.IMAGE_TYPE ||
                hitTestResult.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                hitTestResult.extra?.let { webPic ->
                    selector(
                        arrayListOf(
                            SelectItem(getString(R.string.action_save), "save"),
                            SelectItem(getString(R.string.select_folder), "selectFolder")
                        )
                    ) { _, charSequence, _ ->
                        when (charSequence.value) {
                            "save" -> saveImage(webPic)
                            "selectFolder" -> selectSaveFolder()
                        }
                    }
                    return@setOnLongClickListener true
                }
            }
            return@setOnLongClickListener false
        }

        // 下载监听器（来自第一版本）
        currentWebView.setDownloadListener { downloadUrl, _, contentDisposition, _, _ ->
            var fileName = URLUtil.guessFileName(downloadUrl, contentDisposition, null)
            fileName = java.net.URLDecoder.decode(fileName, "UTF-8")
            binding.llView.longSnackbar(fileName, getString(R.string.action_download)) {
                Download.start(this, downloadUrl, fileName)
            }
        }
    }

    // JavaScript接口类（使用WeakReference防止内存泄漏）
    class JSInterface(activity: WebViewActivity) {
        private val activityRef: WeakReference<WebViewActivity> = WeakReference(activity)

        @JavascriptInterface
        fun lockOrientation(orientation: String) {
            val ctx = activityRef.get()
            if (ctx != null && !ctx.isFinishing && !ctx.isDestroyed) {
                ctx.runOnUiThread {
                    if (ctx.isfullscreen) {
                        ctx.requestedOrientation = when (orientation) {
                            "portrait", "portrait-primary" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            "portrait-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                            "landscape", "landscape-primary" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            "landscape-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                            "any", "unspecified" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                    }
                }
            }
        }

        @JavascriptInterface
        fun request(jsCode: String, id: String) {
            val ctx = activityRef.get()
            if (ctx != null && !ctx.isFinishing && !ctx.isDestroyed) {
                Coroutine.async(ctx.lifecycleScope) {
                    AnalyzeRule(ReadBook.book, ctx.viewModel.source).run {
                        setCoroutineContext(coroutineContext)
                        evalJS(jsCode).toString()
                    }
                }.onSuccess { data ->
                    ctx.currentWebView.evaluateJavascript(
                        "window.$JSBridgeResult('$id', '${data.escapeForJs()}', null);",
                        null
                    )
                }.onError {
                    ctx.currentWebView.evaluateJavascript(
                        "window.$JSBridgeResult('$id', null, '${it.localizedMessage?.escapeForJs()}');",
                        null
                    )
                }
            }
        }

        @JavascriptInterface
        fun onCloseRequested() {
            val ctx = activityRef.get()
            if (ctx != null && !ctx.isFinishing && !ctx.isDestroyed) {
                ctx.runOnUiThread {
                    ctx.close()
                }
            }
        }
    }

    private fun saveImage(webPic: String) {
        this.webPic = webPic
        val path = ACache.get().getAsString(imagePathKey)
        if (path.isNullOrEmpty()) {
            selectSaveFolder()
        } else {
            viewModel.saveImage(webPic, path)
        }
    }

    private fun selectSaveFolder() {
        val default = arrayListOf<SelectItem<Int>>()
        val path = ACache.get().getAsString(imagePathKey)
        if (!path.isNullOrEmpty()) {
            default.add(SelectItem(path, -1))
        }
        saveImage.launch {
            otherActions = default
        }
    }

    override fun finish() {
        SourceVerificationHelp.checkResult(viewModel.sourceOrigin)
        super.finish()
    }

    private fun close() {
        if (!isCloudflareChallenge) {
            if (viewModel.sourceVerificationEnable) {
                viewModel.saveVerificationResult(currentWebView) {
                    finish()
                }
            } else {
                finish()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        currentWebView.pauseTimers()
        currentWebView.onPause()
    }

    override fun onResume() {
        super.onResume()
        currentWebView.resumeTimers()
        currentWebView.onResume()
    }

    override fun onDestroy() {
        // 释放WebView到池中
        WebViewPool.release(pooledWebView)
        super.onDestroy()
    }

    inner class CustomWebChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            binding.progressBar.setDurProgress(newProgress)
            binding.progressBar.gone(newProgress == 100)
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            isfullscreen = true
            binding.llView.invisible()
            binding.customWebView.addView(view)
            customWebViewCallback = callback
            keepScreenOn(true)
            toggleSystemBar(false)
        }

        override fun onHideCustomView() {
            isfullscreen = false
            binding.customWebView.removeAllViews()
            binding.llView.visible()
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            keepScreenOn(false)
            toggleSystemBar(true)
        }

        // 覆盖window.close()
        override fun onCloseWindow(window: WebView?) {
            close()
        }

        // 监听网页日志
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            viewModel.source?.let {
                if (sessionShowWebLog) {
                    val consoleException = Exception(
                        "${consoleMessage.messageLevel().name}: \n${consoleMessage.message()}\n" +
                                "-Line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}"
                    )
                    val message = viewModel.sourceName + ": ${consoleMessage.message()}"
                    when (consoleMessage.messageLevel()) {
                        ConsoleMessage.MessageLevel.LOG -> AppLog.put(message)
                        ConsoleMessage.MessageLevel.DEBUG -> AppLog.put(message, consoleException)
                        ConsoleMessage.MessageLevel.WARNING -> AppLog.put(message, consoleException)
                        ConsoleMessage.MessageLevel.ERROR -> AppLog.put(message, consoleException)
                        ConsoleMessage.MessageLevel.TIP -> AppLog.put(message)
                        else -> AppLog.put(message)
                    }
                    return true
                }
            }
            return false
        }
    }

    inner class CustomWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            request?.let {
                return shouldOverrideUrlLoading(it.url)
            }
            return true
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "KotlinRedundantDiagnosticSuppress")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            url?.let {
                return shouldOverrideUrlLoading(it.toUri())
            }
            return true
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (needClearHistory) {
                needClearHistory = false
                currentWebView.clearHistory() // 清除历史记录
            }
            super.onPageStarted(view, url, favicon)
            currentWebView.evaluateJavascript(basicJs, null)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)

            // 确保Cookie污染检测开启（来自第一版本）
            CookieStore.enableContaminationCheck()

            val cookieManager = CookieManager.getInstance()

            url?.let { currentUrl ->
                try {
                    // 获取当前URL的Cookie（使用第一版本的详细逻辑）
                    val webViewCookie = cookieManager.getCookie(currentUrl)

                    if (!webViewCookie.isNullOrBlank()) {
                        // 记录Cookie获取成功
                        AppLog.putDebug("WebView onPageFinished: Cookie retrieved for URL: $currentUrl")
                        AppLog.putDebug("Cookie content length: ${webViewCookie.length}")

                        if (webViewCookie.length > 200) {
                            AppLog.putDebug("Cookie content (first 200 chars): ${webViewCookie.substring(0, 200)}...")
                        } else {
                            AppLog.putDebug("Cookie content: $webViewCookie")
                        }

                        // 提取域名
                        val domain = NetworkUtils.getSubDomain(currentUrl)

                        // 使用replaceCookie确保Cookie被正确保存（合并而不是覆盖）
                        CookieStore.replaceCookie(domain, webViewCookie)

                        // 立即验证保存是否成功
                        val savedCookie = CookieStore.getCookie(domain)
                        AppLog.putDebug("Immediate verification - Saved cookie length: ${savedCookie.length}")

                        if (savedCookie.isNotBlank()) {
                            AppLog.putDebug("Cookie saved successfully for domain: $domain")
                        } else {
                            AppLog.putDebug("WARNING: Cookie save may have failed for domain: $domain")
                        }
                    } else {
                        AppLog.putDebug("WebView onPageFinished: No cookie found for URL: $currentUrl")
                    }
                } catch (e: Exception) {
                    AppLog.put("WebView onPageFinished: Error processing cookie for URL: $currentUrl", e)
                }
            }

            // 更新标题栏
            view?.title?.let { title ->
                if (title != url && title != view.url && title.isNotBlank()) {
                    binding.titleBar.title = title
                } else {
                    binding.titleBar.title = intent.getStringExtra("title")
                }

                // Cloudflare检测
                view.evaluateJavascript("!!window._cf_chl_opt") {
                    try {
                        if (it == "true") {
                            isCloudflareChallenge = true
                            AppLog.putDebug("WebView onPageFinished: Cloudflare challenge detected")
                        } else if (isCloudflareChallenge && viewModel.sourceVerificationEnable) {
                            AppLog.putDebug("WebView onPageFinished: Cloudflare challenge completed, saving verification result")
                            viewModel.saveVerificationResult(currentWebView) {
                                finish()
                            }
                        }
                    } catch (e: Exception) {
                        AppLog.put("WebView onPageFinished: Error handling Cloudflare verification\n$e", e)
                    }
                }
            }
        }

        private fun shouldOverrideUrlLoading(url: Uri): Boolean {
            return when (url.scheme) {
                "http", "https" -> false
                "legado", "yuedu" -> {
                    startActivity<OnLineImportActivity> {
                        data = url
                    }
                    true
                }
                else -> {
                    binding.root.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
                        openUrl(url)
                    }
                    true
                }
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?
        ) {
            handler?.proceed()
        }
    }
}