package com.tgg.zcodelink

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.View
import android.webkit.CookieManager
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

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
        // 让登录态/会话 cookie 落盘，重启后不必重新配对
        CookieManager.getInstance().flush()
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
                // 新窗口请求（OAuth 等）改为在当前 WebView 打开，避免弹出无法操作的空白窗
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val popup = WebView(this@MainActivity)
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val uri = request?.url ?: return false
                        if (uri.scheme == "http" || uri.scheme == "https") {
                            binding.webView.loadUrl(uri.toString())
                            return true
                        }
                        return false
                    }
                }
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }
    }

    /** @return true 表示已由本方法处理（不再交给 WebView 自行加载） */
    private fun handleUrl(uri: Uri): Boolean {
        val scheme = uri.scheme.orEmpty()
        if (scheme == "http" || scheme == "https") return false // 站内一律留在 WebView
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: ActivityNotFoundException) {
            true
        }
    }

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
