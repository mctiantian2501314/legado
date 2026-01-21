package com.eggreader.app.ui.browser

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.MotionEvent
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
import com.eggreader.app.R
import com.eggreader.app.base.VMBaseActivity
import com.eggreader.app.constant.AppConst
import com.eggreader.app.constant.AppConst.imagePathKey
import com.eggreader.app.databinding.ActivityWebViewBinding
import com.eggreader.app.help.http.CookieStore
import com.eggreader.app.help.source.SourceVerificationHelp
import com.eggreader.app.lib.dialogs.SelectItem
import com.eggreader.app.lib.dialogs.alert
import com.eggreader.app.lib.dialogs.selector
import com.eggreader.app.lib.theme.accentColor
import com.eggreader.app.model.Download
import com.eggreader.app.ui.association.OnLineImportActivity
import com.eggreader.app.ui.file.HandleFileContract
import com.eggreader.app.utils.ACache
import com.eggreader.app.utils.gone
import com.eggreader.app.utils.invisible
import com.eggreader.app.utils.keepScreenOn
import com.eggreader.app.utils.longSnackbar
import com.eggreader.app.utils.openUrl
import com.eggreader.app.utils.sendToClip
import com.eggreader.app.utils.startActivity
import com.eggreader.app.utils.toggleSystemBar
import com.eggreader.app.utils.viewbindingdelegate.viewBinding
import com.eggreader.app.utils.visible
import android.webkit.JavascriptInterface
import com.eggreader.app.constant.AppLog
import com.eggreader.app.help.webView.WebJsExtensions
import com.eggreader.app.help.webView.WebJsExtensions.Companion.basicJs
import com.eggreader.app.help.webView.WebJsExtensions.Companion.nameBasic
import com.eggreader.app.help.webView.WebJsExtensions.Companion.nameJava
import androidx.lifecycle.lifecycleScope
import com.eggreader.app.help.webView.PooledWebView
import com.eggreader.app.help.webView.WebViewPool
import com.eggreader.app.help.webView.WebViewPool.BLANK_HTML
import com.eggreader.app.help.webView.WebViewPool.DATA_HTML
import java.lang.ref.WeakReference
import com.eggreader.app.help.http.CookieManager as AppCookieManager
import androidx.core.net.toUri
import com.eggreader.app.help.coroutine.Coroutine
import com.eggreader.app.model.analyzeRule.AnalyzeRule
import com.eggreader.app.model.ReadBook
import com.eggreader.app.help.webView.WebJsExtensions.Companion.JSBridgeResult
import com.eggreader.app.utils.escapeForJs
import com.eggreader.app.utils.NetworkUtils
import com.eggreader.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import android.view.Gravity
import android.view.WindowManager
import android.view.ViewGroup

class WebViewActivity : VMBaseActivity<ActivityWebViewBinding, WebViewModel>() {
    companion object {
        // 是否输出日志
        var sessionShowWebLog = false
        // 半屏模式参数
        const val EXTRA_HALF_SCREEN = "half_screen"
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
    private var isHalfScreen = false
    // 拖动调整大小相关变量
    private var isResizing = false
    private var lastY = 0f
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
        // 初始化半屏模式
        isHalfScreen = intent.getBooleanExtra(EXTRA_HALF_SCREEN, false)
        if (isHalfScreen) {
            setupHalfScreenMode()
        }

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

    // 设置半屏模式
    private fun setupHalfScreenMode() {
        val window = window
        val params = window.attributes
        params.gravity = Gravity.BOTTOM
        params.width = WindowManager.LayoutParams.MATCH_PARENT
        // 设置高度为屏幕高度的一半
        val displayMetrics = resources.displayMetrics
        params.height = (displayMetrics.heightPixels * 0.5f).toInt()
        // 添加动画效果
        params.windowAnimations = android.R.style.Animation_Translucent
        window.attributes = params
        
        // 调整布局
        binding.titleBar.visible()
        binding.webViewContainer.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
        
        // 添加顶部阴影效果，增强视觉层次感
        binding.titleBar.elevation = 8f
        
        // 设置背景透明度，增强半屏效果
        window.decorView.setBackgroundColor(android.graphics.Color.argb(240, 0, 0, 0))
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

    // 处理触摸事件，实现拖动调整大小
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isHalfScreen) return super.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 只有在顶部区域的触摸才触发调整大小
                if (event.y < 100) {
                    isResizing = true
                    lastY = event.rawY
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isResizing) {
                    val deltaY = lastY - event.rawY
                    lastY = event.rawY
                    
                    // 调整窗口大小
                    val window = window
                    val params = window.attributes
                    val newHeight = params.height + deltaY.toInt()
                    
                    // 限制最小高度
                    val minHeight = resources.displayMetrics.heightPixels * 0.3f
                    if (newHeight > minHeight) {
                        params.height = newHeight
                        window.attributes = params
                    }
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                isResizing = false
            }
        }
        return isResizing || super.onTouchEvent(event)
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
