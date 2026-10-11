package com.tgg.zcodelink

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.tgg.zcodelink.databinding.ActivityMainBinding
import com.tgg.zcodelink.databinding.ItemAddressBinding
import com.tgg.zcodelink.databinding.SheetAddressBinding
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 承载 ZCode 手机远控网页客户端的 WebView 外壳。
 *
 * ZCode 的远控客户端本身是一个网页应用（https://zcode.z.ai/remote/v4?...），
 * 所以这里不做协议实现，只负责：加载该地址、管理会变化的连接地址、以及基本的
 * 浏览体验（进度、后退、错误重试）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentUrl: String? = null

    /** 接住 window.open 的临时 WebView；用完即销毁，避免每次弹窗残留一个重对象。 */
    private var popupWebView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 下载内容的解码与写盘放在后台线程，避免卡主线程。 */
    private val downloadExecutor = Executors.newSingleThreadExecutor()

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private companion object {
        /**
         * blob/data 下载的大小上限。这条链路要把内容在内存里过一遍 base64，
         * 峰值约为文件大小的 3 倍，所以上限按最差的 128 MB 堆来定，宁可拒绝也不要把进程撑爆。
         */
        const val MAX_IN_MEMORY_DOWNLOAD_BYTES = 16 * 1024 * 1024

        /** 同上，按 base64 字符数折算的兜底上限。 */
        const val MAX_IN_MEMORY_DOWNLOAD_CHARS = MAX_IN_MEMORY_DOWNLOAD_BYTES / 3 * 4 + 1024
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupWebView()

        binding.emptyAction.setOnClickListener { showAddressSheet() }
        binding.errorReenter.setOnClickListener { showAddressSheet() }
        binding.errorRetry.setOnClickListener { currentUrl?.let { binding.webView.reload() } }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
                }
            }
        )

        val fromIntent = ZcLinks.extractUrl(intent?.let { intentText(it) })
        when {
            fromIntent != null -> connect(fromIntent, persist = true, announce = false)
            else -> {
                val last = ZcLinks.lastUrl(this)
                if (last != null) connect(last, persist = false, announce = false) else showEmptyState()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ZcLinks.extractUrl(intentText(intent))?.let { connect(it, persist = true, announce = false) }
    }

    override fun onPause() {
        super.onPause()
        // 让登录态/会话 cookie 落盘，重启后不必重新配对。
        // flush() 会做磁盘写入，放到后台线程，避免切换前后台时卡主线程。
        val cookies = CookieManager.getInstance()
        Thread { runCatching { cookies.flush() } }.start()
    }

    override fun onDestroy() {
        // 释放 WebView 及其渲染进程，否则进程存活期间会一直占着内存。
        popupWebView?.let { destroyWebView(it) }
        popupWebView = null
        destroyWebView(binding.webView)
        downloadExecutor.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 初始化

    private fun setupToolbar() {
        binding.toolbar.inflateMenu(R.menu.main)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_address -> {
                    showAddressSheet(); true
                }
                R.id.action_reload -> {
                    if (currentUrl == null) {
                        toast(getString(R.string.toast_no_address))
                    } else {
                        binding.errorState.visibility = View.GONE
                        binding.webView.visibility = View.VISIBLE
                        binding.webView.reload()
                    }
                    true
                }
                R.id.action_copy -> {
                    val url = currentUrl
                    if (url == null) {
                        toast(getString(R.string.toast_no_address))
                    } else {
                        copyToClipboard(url)
                        toast(getString(R.string.toast_copied))
                    }
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val web = binding.webView
        with(web.settings) {
            javaScriptEnabled = true               // 网页客户端必需
            domStorageEnabled = true               // 网页客户端必需
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false                // 不暴露本地文件
            allowContentAccess = false
            setGeolocationEnabled(false)
            setSupportMultipleWindows(true)        // window.open / 授权弹窗
            // 保持 WebView 默认 UA：ZCode 客户端面向手机浏览器设计，默认即可正常工作。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        // WebView 自己不会下载：不注册这个回调，点击下载链接会毫无反应。
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }

        // 仅用于把 blob:/data: 的字节从页面取回宿主（见 DownloadBridge 的来源校验）。
        web.addJavascriptInterface(DownloadBridge(), "ZcDownload")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                return handleUrl(uri)
            }

            @Deprecated("minSdk 24 上仍可能被回调")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return url != null && handleUrl(Uri.parse(url))
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progress.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                // 只处理主文档失败；子资源失败不该遮住整个页面
                if (request?.isForMainFrame == true) showErrorState()
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                handler?.cancel() // 证书异常一律拒绝，不提供绕过入口
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progress.progress = newProgress
                binding.progress.visibility =
                    if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                // 新窗口请求（target=_blank / window.open）仍在当前 WebView 打开：
                // 用一个临时 WebView 接住首次导航，转交主 WebView 后立即销毁。
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                popupWebView?.let { destroyWebView(it) }

                val popup = WebView(this@MainActivity)
                popupWebView = popup
                popup.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                    startDownload(url, userAgent, contentDisposition, mimeType)
                }
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView?,
                        request: WebResourceRequest?
                    ): Boolean = handOffPopup(request?.url)

                    @Deprecated("minSdk 24 上仍可能被回调")
                    override fun shouldOverrideUrlLoading(v: WebView?, url: String?): Boolean =
                        url != null && handOffPopup(Uri.parse(url))

                    override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                        // 兜底：个别实现不会为弹窗首帧回调 shouldOverrideUrlLoading
                        if (url != null) handOffPopup(Uri.parse(url))
                    }
                }
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }
    }

    /**
     * 把弹窗里的导航转交主 WebView，并销毁这个临时 WebView。
     * @return true 表示已接管，阻止弹窗自身继续加载
     */
    private fun handOffPopup(uri: Uri?): Boolean {
        val popup = popupWebView ?: return false
        if (uri == null) return false
        popupWebView = null // 立即置空，避免 onPageStarted 兜底把同一地址加载两次
        routeUri(uri)
        // 在弹窗自身的回调栈里直接 destroy 可能抛异常，挪到下一帧执行
        mainHandler.post { destroyWebView(popup) }
        return true
    }

    /** 站内页面留在主 WebView；blob/data 交给下载；其余交给系统。 */
    private fun routeUri(uri: Uri) {
        when (uri.scheme.orEmpty().lowercase()) {
            "http", "https" -> binding.webView.loadUrl(uri.toString())
            "blob", "data" -> startDownload(uri.toString(), null, null, null)
            else -> handleUrl(uri)
        }
    }

    private fun destroyWebView(web: WebView) {
        if (popupWebView === web) popupWebView = null
        runCatching {
            web.stopLoading()
            web.webChromeClient = null
            web.webViewClient = WebViewClient()
            (web.parent as? ViewGroup)?.removeView(web)
            web.removeAllViews()
            web.destroy()
        }
    }

    /** @return true 表示已由本方法处理（不再交给 WebView 自行加载） */
    private fun handleUrl(uri: Uri): Boolean {
        val scheme = uri.scheme.orEmpty().lowercase()
        if (scheme == "http" || scheme == "https") return false // 站内一律留在 WebView
        if (scheme == "blob" || scheme == "data") {
            startDownload(uri.toString(), null, null, null) // 网页用 blob/data 触发的下载
            return true
        }
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: ActivityNotFoundException) {
            true
        }
    }

    // ---------------------------------------------------------------- 下载

    /**
     * WebView 不会自己保存文件，必须由宿主接管下载请求，否则点击下载链接毫无反应。
     * http(s) 交给系统 DownloadManager；blob/data（网页内存里生成的）先取回字节再落盘。
     */
    private fun startDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        when (Uri.parse(url).scheme.orEmpty().lowercase()) {
            "http", "https" -> downloadToSystem(url, userAgent, contentDisposition, mimeType)
            "blob", "data" -> downloadInMemory(url, contentDisposition, mimeType)
            else -> toast(getString(R.string.toast_download_unsupported))
        }
    }

    private fun downloadToSystem(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        val fileName = guessFileName(url, contentDisposition, mimeType)
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setMimeType(mimeType)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+ 写公共「下载」目录不需要任何权限
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                } else {
                    // 旧版本改存应用专属目录，同样免权限
                    setDestinationInExternalFilesDir(
                        this@MainActivity, Environment.DIRECTORY_DOWNLOADS, fileName
                    )
                }
                userAgent?.let { addRequestHeader("User-Agent", it) }
                // 带上会话 cookie，否则需要登录的附件会下成 401/403 页面
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            toast(getString(R.string.toast_downloading, fileName))
        } catch (_: Exception) {
            toast(getString(R.string.toast_download_failed))
        }
    }

    /**
     * blob: / data: 的字节只存在于网页内存里，DownloadManager 拿不到，
     * 因此让页面读成 base64 经 DownloadBridge 交回宿主落盘。
     * 注意 evaluateJavascript 不会等待 Promise，所以结果必须走 JS 接口回传。
     */
    private fun downloadInMemory(url: String, contentDisposition: String?, mimeType: String?) {
        val fileName = guessFileName(url, contentDisposition, mimeType)
        val script = """
            (async () => {
              try {
                const blob = await (await fetch(${quoteJs(url)})).blob();
                // 先在页面里按大小挡掉，避免生成巨型 base64 字符串把应用撑爆
                if (blob.size > ${MAX_IN_MEMORY_DOWNLOAD_BYTES}) {
                  ZcDownload.tooLarge();
                  return;
                }
                const dataUrl = await new Promise((resolve, reject) => {
                  const reader = new FileReader();
                  reader.onload = () => resolve(reader.result);
                  reader.onerror = () => reject(reader.error);
                  reader.readAsDataURL(blob);
                });
                // 只传 base64 正文，省掉宿主侧一次整串 substring 拷贝
                ZcDownload.save(dataUrl.slice(dataUrl.indexOf(',') + 1), ${quoteJs(fileName)});
              } catch (e) {
                ZcDownload.fail(String(e));
              }
            })()
        """.trimIndent()
        binding.webView.evaluateJavascript(script, null)
        toast(getString(R.string.toast_downloading, fileName))
    }

    /** 网页侧回传下载内容的唯一入口；只接受来自 ZCode 页面本身的调用。 */
    private inner class DownloadBridge {

        @JavascriptInterface
        fun save(base64: String, fileName: String) {
            // JS 接口在 WebView 的 bridge 线程被调用，碰 WebView 必须回到主线程
            mainHandler.post {
                if (!isTrustedPage()) return@post
                val name = sanitizeFileName(fileName)
                // 解码与写盘都在后台线程，别卡住主线程
                downloadExecutor.execute {
                    val result = saveBase64(base64, name)
                    mainHandler.post { announceSave(result, name) }
                }
            }
        }

        @JavascriptInterface
        fun tooLarge() {
            mainHandler.post {
                if (!isTrustedPage()) return@post
                announceSave(SaveResult.TOO_LARGE, null)
            }
        }

        @JavascriptInterface
        @Suppress("UNUSED_PARAMETER")
        fun fail(message: String) {
            mainHandler.post {
                if (!isTrustedPage()) return@post
                announceSave(SaveResult.FAILED, null)
            }
        }
    }

    /** JS 接口对所有 frame 可见，用主文档来源兜底，避免被第三方内容调用。 */
    private fun isTrustedPage(): Boolean =
        binding.webView.url?.startsWith("https://zcode.z.ai/") == true

    private enum class SaveResult { OK, TOO_LARGE, FAILED }

    private fun saveBase64(base64: String, fileName: String): SaveResult {
        if (base64.length > MAX_IN_MEMORY_DOWNLOAD_CHARS) return SaveResult.TOO_LARGE
        val bytes = try {
            Base64.decode(base64, Base64.DEFAULT)
        } catch (_: Exception) {
            return SaveResult.FAILED
        }
        return if (writeToDownloads(fileName, bytes)) SaveResult.OK else SaveResult.FAILED
    }

    private fun announceSave(result: SaveResult, fileName: String?) {
        val message = when (result) {
            SaveResult.OK -> getString(R.string.toast_download_saved, fileName.orEmpty())
            SaveResult.TOO_LARGE ->
                getString(R.string.toast_download_too_large, MAX_IN_MEMORY_DOWNLOAD_BYTES / 1024 / 1024)
            SaveResult.FAILED -> getString(R.string.toast_download_failed)
        }
        toast(message)
    }

    private fun writeToDownloads(fileName: String, bytes: ByteArray): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                if (uri == null) {
                    false
                } else {
                    val stream = resolver.openOutputStream(uri)
                    if (stream == null) {
                        false
                    } else {
                        stream.use { it.write(bytes) }
                        values.clear()
                        values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                        true
                    }
                }
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                if (dir == null) {
                    false
                } else {
                    if (!dir.exists()) dir.mkdirs()
                    FileOutputStream(File(dir, fileName)).use { it.write(bytes) }
                    true
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    /** 从 Content-Disposition 或 URL 末段推断文件名，兜底用 mime 扩展名。 */
    private fun guessFileName(url: String, contentDisposition: String?, mimeType: String?): String {
        val fromHeader = contentDisposition?.let {
            Regex("filename\\*?=(?:UTF-8''|\")?([^\";]+)", RegexOption.IGNORE_CASE)
                .find(it)?.groupValues?.get(1)
        }
        if (!fromHeader.isNullOrBlank()) return sanitizeFileName(decodeUrl(fromHeader))

        val last = Uri.parse(url).path?.substringAfterLast('/')
        if (!last.isNullOrBlank()) {
            val name = sanitizeFileName(decodeUrl(last))
            if (name.contains('.')) return name
            return extensionFor(mimeType)?.let { "$name.$it" } ?: name
        }
        return extensionFor(mimeType)?.let { "download.$it" } ?: "download"
    }

    private fun extensionFor(mimeType: String?): String? =
        android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)

    private fun decodeUrl(text: String): String = try {
        URLDecoder.decode(text.trim(), "UTF-8")
    } catch (_: Exception) {
        text.trim()
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_")
            .trim()
            .trimStart('.')
            .take(120)
        return cleaned.ifBlank { "download" }
    }

    /** 把字符串安全地嵌进注入的 JS 单引号字面量里。 */
    private fun quoteJs(text: String): String = "'" +
        text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r") +
        "'"

    // ---------------------------------------------------------------- 状态

    private fun showEmptyState() {
        binding.webView.visibility = View.GONE
        binding.errorState.visibility = View.GONE
        binding.emptyState.visibility = View.VISIBLE
        binding.progress.visibility = View.GONE
        binding.toolbar.subtitle = getString(R.string.subtitle_none)
    }

    private fun showErrorState() {
        binding.progress.visibility = View.GONE
        binding.webView.visibility = View.GONE
        binding.errorState.visibility = View.VISIBLE
    }

    private fun updateSubtitle(url: String) {
        val link = (ZcLinks.parse(url) as? ZcLinks.ParseResult.Ok)?.link
        binding.toolbar.subtitle = when {
            link == null -> getString(R.string.subtitle_none)
            link.name.isNotEmpty() -> getString(R.string.subtitle_connected, link.name)
            else -> link.sid.take(16) + "…"
        }
    }

    // ---------------------------------------------------------------- 连接

    private fun connect(url: String, persist: Boolean, announce: Boolean) {
        currentUrl = url
        if (persist) ZcLinks.add(this, url)
        ZcLinks.setLastUrl(this, url)

        binding.emptyState.visibility = View.GONE
        binding.errorState.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        binding.progress.visibility = View.VISIBLE

        updateSubtitle(url)
        binding.webView.loadUrl(url)
        if (announce) toast(getString(R.string.toast_saved))
    }

    // ---------------------------------------------------------------- 地址管理

    private fun showAddressSheet() {
        val sheet = SheetAddressBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheet.root)

        sheet.urlInput.setText(currentUrl ?: "")

        sheet.pasteButton.setOnClickListener {
            val text = readClipboard()
            if (text.isNullOrBlank()) {
                sheetError(sheet, getString(R.string.err_clipboard_empty))
            } else {
                sheet.urlInput.setText(text)
                sheet.urlInput.setSelection(text.length)
                sheetError(sheet, null)
            }
        }

        sheet.saveButton.setOnClickListener {
            when (val result = ZcLinks.parse(sheet.urlInput.text?.toString().orEmpty())) {
                is ZcLinks.ParseResult.Ok -> {
                    connect(result.link.url, persist = true, announce = true)
                    dialog.dismiss()
                }
                ZcLinks.ParseResult.Empty -> sheetError(sheet, getString(R.string.err_empty))
                ZcLinks.ParseResult.NotZcode -> sheetError(sheet, getString(R.string.err_not_zcode))
                ZcLinks.ParseResult.MissingParams -> sheetError(sheet, getString(R.string.err_missing_params))
            }
        }

        renderSaved(sheet, dialog)
        dialog.show()
    }

    private fun renderSaved(sheet: SheetAddressBinding, dialog: BottomSheetDialog) {
        val list = ZcLinks.load(this)
        sheet.addressList.removeAllViews()
        sheet.listEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        list.forEach { link ->
            val item = ItemAddressBinding.inflate(layoutInflater, sheet.addressList, false)
            val isCurrent = link.url == currentUrl
            item.itemName.text = link.name.ifEmpty { "未命名设备" } + if (isCurrent) "  ·  当前" else ""
            item.itemName.setTextColor(
                getColor(if (isCurrent) R.color.accent else R.color.on_surface)
            )
            val linkTime = link.t
            item.itemSub.text = buildString {
                append(link.sid.take(18))
                if (linkTime != null && linkTime > 0L) {
                    append("  ·  ").append(timeFormat.format(Date(linkTime)))
                }
                append("  ·  保存于 ").append(timeFormat.format(Date(link.savedAt)))
            }
            item.root.setOnClickListener {
                connect(link.url, persist = false, announce = false)
                dialog.dismiss()
            }
            item.itemDelete.setOnClickListener {
                ZcLinks.remove(this, link.url)
                toast(getString(R.string.toast_deleted))
                renderSaved(sheet, dialog)
            }
            sheet.addressList.addView(item.root)
        }
    }

    private fun sheetError(sheet: SheetAddressBinding, message: String?) {
        sheet.sheetError.text = message.orEmpty()
        sheet.sheetError.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------- 工具

    private fun intentText(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
    }

    private fun readClipboard(): String? {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()
    }

    private fun copyToClipboard(text: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        manager.setPrimaryClip(android.content.ClipData.newPlainText("ZCode link", text))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
