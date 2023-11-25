/*
 * Copyright (C) 2020-2023 Tipz Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:Suppress("DEPRECATION")

package tipz.viola.webview

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.util.AttributeSet
import android.view.ContextMenu
import android.view.ContextMenu.ContextMenuInfo
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.CookieSyncManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewRenderProcess
import androidx.webkit.WebViewRenderProcessClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tipz.viola.Application
import tipz.viola.BaseActivity
import tipz.viola.BuildConfig
import tipz.viola.R
import tipz.viola.broha.api.HistoryApi
import tipz.viola.broha.api.HistoryUtils
import tipz.viola.broha.database.Broha
import tipz.viola.settings.SettingsKeys
import tipz.viola.settings.SettingsUtils
import tipz.viola.utils.CommonUtils
import tipz.viola.utils.DownloadUtils
import tipz.viola.utils.InternalUrls
import tipz.viola.utils.UrlUtils
import java.io.ByteArrayInputStream
import java.net.MalformedURLException
import java.net.URL
import java.util.Objects
import java.util.Scanner

@SuppressLint("SetJavaScriptEnabled")
class VWebView(private val mContext: Context, attrs: AttributeSet?) : WebView(
    mContext, attrs
) {
    private var mVioWebViewActivity: VWebViewActivity? = null
    private val iconHashClient = (mContext.applicationContext as Application).iconHashClient!!
    private val webSettings = this.settings
    private val mWebViewRenderProcess =
        if (WebViewFeature.isFeatureSupported(WebViewFeature.GET_WEB_VIEW_RENDERER)) WebViewCompat.getWebViewRenderProcess(
            this
        ) else null
    private var currentUrl: String? = null
    private var adServers: String? = null
    private var currentBroha: Broha? = null
    private var updateHistory = true
    private var historyCommitted = false
    private val pref = (mContext.applicationContext as Application).pref!!
    private var mUploadMessage: ValueCallback<Array<Uri>>? = null
    val mFileChooser =
        (mContext as AppCompatActivity).registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (null == mUploadMessage || uri == null) return@registerForActivityResult
            mUploadMessage!!.onReceiveValue(arrayOf(uri))
            mUploadMessage = null
        }
    private val mRequestHeaders = HashMap<String, String>()
    private fun userAgentFull(mode: Double): String {
        val info = WebViewCompat.getCurrentWebViewPackage(mContext)
        val webkitVersion = if (info == null) "534.30" else "537.36"
        return "Mozilla/5.0 (" + "Linux; Device with Viola " + BuildConfig.VERSION_NAME + ") AppleWebKit/" + webkitVersion + " KHTML, like Gecko) Chrome/" + if (info == null) "12.0.742" else info.versionName + if (mode == 0.0) " Mobile " else " Safari/$webkitVersion"
    }

    init {
        /* User agent init code */
        setPrebuiltUAMode(null, 0.0, true)

        /* Start the download manager service */
        setDownloadListener { url: String?, _: String?, contentDisposition: String?, mimeType: String?, _: Long ->
            DownloadUtils.dmDownloadFile(
                mContext, url!!, contentDisposition,
                mimeType, currentUrl
            )
            updateCurrentUrl(originalUrl)
            mVioWebViewActivity!!.onPageLoadProgressChanged(0)
            if (!canGoBack() && originalUrl == null && CommonUtils.isIntStrOne(
                    SettingsUtils.getPrefNum(
                        pref, SettingsKeys.closeAppAfterDownload
                    )
                )
            ) mVioWebViewActivity!!.finish()
        }
        setLayerType(LAYER_TYPE_HARDWARE, null)

        /* zoom related stuff - From SCMPNews project */webSettings.setSupportZoom(true)
        webSettings.builtInZoomControls = true

        // Also increase text size to fill the viewport (this mirrors the behaviour of Firefox,
        // Chrome does this in the current Chrome Dev, but not Chrome release).
        webSettings.layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
        webSettings.displayZoomControls = false
        webSettings.allowFileAccess = false
        webSettings.allowContentAccess = false
        webSettings.allowFileAccessFromFileURLs = false
        webSettings.allowUniversalAccessFromFileURLs = false

        /* HTML5 API flags */webSettings.databaseEnabled = false
        webSettings.domStorageEnabled = true
        this.webViewClient = WebClient()
        this.webChromeClient = ChromeWebClient()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE) && mWebViewRenderProcess != null) WebViewCompat.setWebViewRenderProcessClient(
            this,
            RenderClient()
        )
        removeJavascriptInterface("searchBoxJavaBridge_") /* CVE-2014-1939 */
        removeJavascriptInterface("accessibility") /* CVE-2014-7224 */
        removeJavascriptInterface("accessibilityTraversal") /* CVE-2014-7224 */

        /* Hit Test Menu */setOnCreateContextMenuListener { _: ContextMenu?, _: View?, _: ContextMenuInfo? ->
            val hr = this.hitTestResult
            val url = hr.extra
            val type = hr.type
            if (type == HitTestResult.UNKNOWN_TYPE || type == HitTestResult.EDIT_TEXT_TYPE) return@setOnCreateContextMenuListener
            val webLongPress = MaterialAlertDialogBuilder(mContext)
            webLongPress.setTitle(if (url!!.length > 75) url.substring(0, 74) + "…" else url)
            val arrayAdapter = ArrayAdapter<String>(mContext, R.layout.recycler_list_item_1)
            if (type == HitTestResult.SRC_ANCHOR_TYPE) arrayAdapter.add(resources.getString(R.string.open_in_new_tab))
            if (type == HitTestResult.IMAGE_TYPE || type == HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                arrayAdapter.add(resources.getString(R.string.download_image))
                arrayAdapter.add(resources.getString(R.string.search_image))
            }
            arrayAdapter.add(resources.getString(R.string.copy_url))
            arrayAdapter.add(resources.getString(R.string.share_url))
            webLongPress.setAdapter(arrayAdapter) { _: DialogInterface?, which: Int ->
                when (arrayAdapter.getItem(which)) {
                    resources.getString(R.string.copy_url) -> {
                        CommonUtils.copyClipboard(mContext, url)
                    }

                    resources.getString(R.string.download_image) -> {
                        DownloadUtils.dmDownloadFile(
                            mContext, url,
                            null, null, url
                        )
                    }

                    resources.getString(R.string.search_image) -> {
                        this.loadUrl("http://images.google.com/searchbyimage?image_url=$url")
                    }

                    resources.getString(R.string.open_in_new_tab) -> {
                        val intent = Intent(mContext, BrowserActivity::class.java)
                        intent.putExtra(Intent.EXTRA_TEXT, url)
                            .setAction(Intent.ACTION_SEND).type = UrlUtils.TypeSchemeMatch[1]
                        mContext.startActivity(intent)
                    }

                    resources.getString(R.string.share_url) -> {
                        CommonUtils.shareUrl(mContext, url)
                    }
                }
            }
            webLongPress.show()
        }
    }

    @Suppress("deprecation")
    fun doSettingsCheck() {
        // Dark mode
        val darkMode = BaseActivity.getDarkMode(mContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && WebViewFeature.isFeatureSupported(
                WebViewFeature.ALGORITHMIC_DARKENING
            )
        ) WebSettingsCompat.setAlgorithmicDarkeningAllowed(
            webSettings,
            darkMode
        ) else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) WebSettingsCompat.setForceDark(
            webSettings,
            if (darkMode) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
        )

        // Settings check
        webSettings.javaScriptEnabled =
            CommonUtils.isIntStrOne(
                SettingsUtils.getPrefNum(
                    pref,
                    SettingsKeys.isJavaScriptEnabled
                )
            )
        webSettings.javaScriptCanOpenWindowsAutomatically =
            CommonUtils.isIntStrOne(
                SettingsUtils.getPrefNum(
                    pref,
                    SettingsKeys.isJavaScriptEnabled
                )
            )

        // HTTPS enforce setting
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) webSettings.mixedContentMode =
            if (CommonUtils.isIntStrOne(
                    SettingsUtils.getPrefNum(pref, SettingsKeys.enforceHttps)
                )
            ) WebSettings.MIXED_CONTENT_NEVER_ALLOW else WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // Google's "Safe" Browsing
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) WebSettingsCompat.setSafeBrowsingEnabled(
            webSettings,
            CommonUtils.isIntStrOne(
                SettingsUtils.getPrefNum(
                    pref,
                    SettingsKeys.enableGoogleSafeBrowse
                )
            )
        )

        // Do Not Track request
        mRequestHeaders["DNT"] = SettingsUtils.getPrefNum(pref, SettingsKeys.sendDNT).toString()
    }

    fun notifyViewSetup() {
        mVioWebViewActivity = mContext as VWebViewActivity
        doSettingsCheck()
    }

    fun setUpdateHistory(value: Boolean) {
        updateHistory = value
    }

    override fun loadUrl(url: String) {
        if (url.isEmpty()) return
        val urlIdentify = URLIdentify(url)
        if (urlIdentify == CommonUtils.EMPTY_STRING) return
        val checkedUrl = UrlUtils.toSearchOrValidUrl(mContext, urlIdentify)
        updateCurrentUrl(checkedUrl)

        // Load URL
        super.loadUrl(checkedUrl, mRequestHeaders)
    }

    override fun getUrl(): String? {
        return currentUrl
    }

    override fun goBack() {
        mVioWebViewActivity!!.onDropDownDismissed()
        super.goBack()
    }

    override fun goForward() {
        mVioWebViewActivity!!.onDropDownDismissed()
        super.goForward()
    }

    private fun updateCurrentUrl(url: String?) {
        mVioWebViewActivity!!.onUrlUpdated(url)
        currentUrl = url
    }

    /**
     * WebViewClient
     */
    inner class WebClient : WebViewClientCompat() {
        private fun UrlSet(url: String) {
            if (currentUrl != url && urlShouldSet(url) || currentUrl == null) updateCurrentUrl(url)
        }

        override fun onPageStarted(view: WebView, url: String, icon: Bitmap?) {
            UrlSet(url)
            mVioWebViewActivity!!.onFaviconProgressUpdated(true)
            mVioWebViewActivity!!.onFaviconUpdated(null, false)
            mVioWebViewActivity!!.onDropDownDismissed()
            mVioWebViewActivity!!.onPageLoadProgressChanged(-1)
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (view.originalUrl == null || view.originalUrl == url) doUpdateVisitedHistory(
                view,
                url,
                true
            )
            mVioWebViewActivity!!.onFaviconProgressUpdated(false)
            mVioWebViewActivity!!.onPageLoadProgressChanged(0)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            UrlSet(url)
            if (updateHistory) {
                currentBroha = Broha(title, currentUrl!!)
                historyCommitted = false
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT_WATCH) CookieSyncManager.getInstance()
                .sync() else CookieManager.getInstance().flush()
            mVioWebViewActivity!!.onFaviconUpdated(null, true)
            mVioWebViewActivity!!.onSwipeRefreshLayoutRefreshingUpdated(false)
        }

        override fun onReceivedError(
            view: WebView,
            errorCode: Int,
            description: String,
            failingUrl: String
        ) {
            var returnVal = template
            for (i in 0..5) returnVal = returnVal.replace(
                "$$i",
                mContext.resources.getStringArray(R.array.errMsg)[i]
            )
            returnVal = returnVal.replace("$6", description)
            view.loadDataWithBaseURL(null, returnVal, "text/html", "UTF-8", null)
        }

        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            if (UrlUtils.isUriLaunchable(url)) return false
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(UrlUtils.cve_2017_13274(url)))
                mContext.startActivity(intent)
            } catch (ignored: ActivityNotFoundException) {
                CommonUtils.showMessage(
                    mContext,
                    resources.getString(R.string.toast_no_app_to_handle)
                )
            }
            return true
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            val dialog = MaterialAlertDialogBuilder(mContext)
            var contentSummary = resources.getString(R.string.ssl_certificate_unknown)
            when (error.primaryError) {
                SslError.SSL_DATE_INVALID -> contentSummary =
                    resources.getString(R.string.ssl_certificate_date_invalid)

                SslError.SSL_INVALID -> contentSummary =
                    resources.getString(R.string.ssl_certificate_invalid)

                SslError.SSL_EXPIRED -> contentSummary =
                    resources.getString(R.string.ssl_certificate_expired)

                SslError.SSL_IDMISMATCH -> contentSummary =
                    resources.getString(R.string.ssl_certificate_idmismatch)

                SslError.SSL_NOTYETVALID -> contentSummary =
                    resources.getString(R.string.ssl_certificate_notyetvalid)

                SslError.SSL_UNTRUSTED -> contentSummary =
                    resources.getString(R.string.ssl_certificate_untrusted)
            }
            dialog.setTitle(resources.getString(R.string.ssl_certificate_error_dialog_title))
                .setMessage(
                    resources.getString(
                        R.string.ssl_certificate_error_dialog_content,
                        contentSummary
                    )
                )
                .setPositiveButton(resources.getString(android.R.string.ok)) { _: DialogInterface?, _: Int -> handler.proceed() }
                .setNegativeButton(resources.getString(android.R.string.cancel)) { _: DialogInterface?, _: Int -> handler.cancel() }
                .create().show()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            return false
        }

        override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? {
            if (adServers.isNullOrEmpty()) {
                CoroutineScope(Dispatchers.IO).launch {
                    val result =
                        DownloadUtils.startFileDownload("https://raw.githubusercontent.com/AdAway/adaway.github.io/master/hosts.txt")
                    val scanner = Scanner(result)
                    val builder = StringBuilder()
                    while (scanner.hasNextLine()) {
                        val line = scanner.nextLine()
                        if (line.startsWith("127.0.0.1 ")) builder.append(line)
                            .append(System.lineSeparator())
                    }
                    adServers = builder.toString()
                }
            }
            try {
                if (adServers != null) if (adServers!!.contains(" " + URL(url).host) && SettingsUtils.getPrefNum(
                        pref,
                        SettingsKeys.enableAdBlock
                    ) == 1
                ) return WebResourceResponse(
                    "text/plain",
                    "utf-8",
                    ByteArrayInputStream(CommonUtils.EMPTY_STRING.toByteArray())
                )
            } catch (e: MalformedURLException) {
                e.printStackTrace()
            }
            return super.shouldInterceptRequest(view, url)
        }
    }

    private fun setImmersiveMode(enable: Boolean) {
        val windowInsetsController = WindowCompat.getInsetsController(
            (mContext as AppCompatActivity).window,
            mContext.window.decorView
        )
        WindowCompat.setDecorFitsSystemWindows(mContext.window, !enable)
        if (enable) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * WebChromeClient
     */
    inner class ChromeWebClient : WebChromeClient() {
        private var mCustomView: View? = null
        private var mCustomViewCallback: CustomViewCallback? = null
        override fun onShowCustomView(paramView: View, viewCallback: CustomViewCallback) {
            if (mCustomView != null) {
                onHideCustomView()
                return
            }
            mCustomView = paramView
            (mContext as AppCompatActivity).requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            mCustomViewCallback = viewCallback
            setImmersiveMode(true)
            (mContext.window.decorView as FrameLayout).addView(
                mCustomView,
                FrameLayout.LayoutParams(-1, -1)
            )
            mContext.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        override fun onHideCustomView() {
            (mContext as AppCompatActivity).window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ((mContext as Activity).window.decorView as FrameLayout).removeView(mCustomView)
            mCustomView = null
            setImmersiveMode(false)
            mContext.requestedOrientation = resources.configuration.orientation
            mCustomViewCallback!!.onCustomViewHidden()
            mCustomViewCallback = null
        }

        override fun onProgressChanged(view: WebView, progress: Int) {
            mVioWebViewActivity!!.onPageLoadProgressChanged(progress)
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap) {
            mVioWebViewActivity!!.onFaviconUpdated(icon, false)
            val currentTitle = title
            if (!historyCommitted && updateHistory) {
                CoroutineScope(Dispatchers.IO).launch {
                    currentBroha!!.iconHash = iconHashClient.save(icon)
                    currentBroha!!.title = currentTitle // For making sure title is up to date
                    if (HistoryUtils.lastUrl(mContext) != currentUrl) {
                        HistoryApi.historyBroha(mContext)!!.insertAll(currentBroha!!)
                    }
                }
                historyCommitted = true
            }
        }

        override fun onReceivedTitle(view: WebView, title: String) {
            mVioWebViewActivity!!.onTitleUpdated(title)
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback
        ) {
            if (ContextCompat.checkSelfPermission(
                    mContext,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(
                    mContext,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) callback.invoke(origin, true, false)
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            if (mUploadMessage != null) mUploadMessage!!.onReceiveValue(null)
            mUploadMessage = filePathCallback
            mFileChooser.launch("*/*")
            return true
        }

        override fun onJsAlert(
            view: WebView,
            url: String,
            message: String,
            result: JsResult
        ): Boolean {
            jsDialog(url, message, null, result, R.string.js_page_says)
            return true
        }

        override fun onJsBeforeUnload(
            view: WebView,
            url: String,
            message: String,
            result: JsResult
        ): Boolean {
            jsDialog(url, message, null, result, R.string.js_leave_page_prompt)
            return true
        }

        override fun onJsConfirm(
            view: WebView,
            url: String,
            message: String,
            result: JsResult
        ): Boolean {
            jsDialog(url, message, null, result, R.string.js_page_says)
            return true
        }

        override fun onJsPrompt(
            view: WebView,
            url: String,
            message: String,
            defaultValue: String,
            result: JsPromptResult
        ): Boolean {
            jsDialog(url, message, defaultValue, result, R.string.js_page_says)
            return true
        }
    }

    /**
     * WebViewRenderProcessClient
     */
    inner class RenderClient : WebViewRenderProcessClient() {
        private var dialog = MaterialAlertDialogBuilder(mContext)
            .setTitle(R.string.dialog_page_unresponsive_title)
            .setMessage(R.string.dialog_page_unresponsive_message)
            .setPositiveButton(R.string.dialog_page_unresponsive_wait, null)
            .setNegativeButton(R.string.dialog_page_unresponsive_terminate) { _: DialogInterface?, _: Int -> mWebViewRenderProcess!!.terminate() }
            .create()

        override fun onRenderProcessUnresponsive(view: WebView, renderer: WebViewRenderProcess?) {
            dialog.show()
        }

        override fun onRenderProcessResponsive(view: WebView, renderer: WebViewRenderProcess?) {
            dialog.dismiss()
        }
    }

    private fun jsDialog(
        url: String,
        message: String,
        defaultValue: String?,
        result: JsResult,
        titleResId: Int
    ) {
        val layoutInflater = LayoutInflater.from(mContext)
        @SuppressLint("InflateParams") val root =
            layoutInflater.inflate(R.layout.dialog_edittext, null)
        val jsMessage = root.findViewById<AppCompatEditText>(R.id.edittext)
        val dialog = MaterialAlertDialogBuilder(mContext)
        dialog.setTitle(mContext.resources.getString(titleResId, url))
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                if (defaultValue == null) result.confirm() else (result as JsPromptResult).confirm(
                    Objects.requireNonNull(jsMessage.text).toString()
                )
            }
            .setNegativeButton(android.R.string.cancel) { _: DialogInterface?, _: Int ->
                result.cancel()
                mVioWebViewActivity!!.onFaviconProgressUpdated(false)
                mVioWebViewActivity!!.onPageLoadProgressChanged(0)
            }
        if (defaultValue != null) dialog.setView(root)
        dialog.create().show()
    }


    private fun urlShouldSet(url: String): Boolean {
        return !(url == "about:blank" || url.startsWith(InternalUrls.prefix))
    }

    fun setUA(
        view: AppCompatImageView?,
        enableDesktop: Boolean,
        ua: String?,
        image: Int?,
        noReload: Boolean
    ) {
        webSettings.userAgentString = ua
        webSettings.loadWithOverviewMode = enableDesktop
        webSettings.useWideViewPort = enableDesktop
        super.setScrollBarStyle(if (enableDesktop) SCROLLBARS_OUTSIDE_OVERLAY else SCROLLBARS_INSIDE_OVERLAY)
        if (view != null) {
            view.setImageResource(image!!)
            view.tag = image
        }
        if (!noReload) webViewReload()
    }

    fun setPrebuiltUAMode(view: AppCompatImageView?, mode: Double, noReload: Boolean) {
        setUA(
            view,
            mode == 1.0,
            userAgentFull(mode),
            if (mode == 0.0) R.drawable.smartphone else R.drawable.desktop,
            noReload
        )
    }

    fun webViewReload() {
        if (currentUrl.isNullOrBlank()) return
        super.loadUrl(currentUrl!!)
    }

    /**
     * URL identify module
     *
     *
     * This module/function identifies a supplied
     * URL to check for it's nature.
     *
     * @param url is supplied for the url to check
     * @return url to load
     */
    private fun URLIdentify(url: String): String {
        if (url == InternalUrls.licenseUrl || url == InternalUrls.realLicenseUrl) return InternalUrls.realLicenseUrl
        if (url == InternalUrls.reloadUrl) {
            webViewReload()
            return CommonUtils.EMPTY_STRING
        }
        val startPageLayout = mVioWebViewActivity!!.startPageLayout
        if (url == InternalUrls.startUrl) {
            this.visibility = GONE
            startPageLayout.visibility = VISIBLE
            return CommonUtils.EMPTY_STRING
        }
        if (this.visibility == GONE) {
            this.visibility = VISIBLE
            startPageLayout.visibility = GONE
        }
        return url
    }

    companion object {
        private const val template =
            "<html>\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<head>\n<title>$0</title>\n</head>\n<body>\n<div style=\"padding-left: 8vw; padding-top: 12vh;\">\n<div>\n<svg xmlns=\"http://www.w3.org/2000/svg\" height=\"96\" viewBox=\"0 -960 960 960\" width=\"96\" fill=\"currentColor\">\n<path d=\"M480.134-120q-74.673 0-140.41-28.339-65.737-28.34-114.365-76.922-48.627-48.582-76.993-114.257Q120-405.194 120-479.866q0-74.673 28.339-140.41 28.34-65.737 76.922-114.365 48.582-48.627 114.257-76.993Q405.194-840 479.866-840q74.673 0 140.41 28.339 65.737 28.34 114.365 76.922 48.627 48.582 76.993 114.257Q840-554.806 840-480.134q0 74.673-28.339 140.41-28.34 65.737-76.922 114.365-48.582 48.627-114.257 76.993Q554.806-120 480.134-120ZM440-162v-78q-33 0-56.5-23.5T360-320v-40L168-552q-3 18-5.5 36t-2.5 36q0 121 79.5 212T440-162Zm276-102q20-22 36-47.5t26.5-53q10.5-27.5 16-56.5t5.5-59q0-98.58-54.115-180.059Q691.769-741.538 600-777.538V-760q0 33-23.5 56.5T520-680h-80v80q0 17-11.5 28.5T400-560h-80v80h240q17 0 28.5 11.5T600-440v120h40q26 0 47 15.5t29 40.5Z\"/>\n</svg>\n</div>\n<div>\n<p style=\"font-family:sans-serif; font-weight: bold; font-size: 24px; margin-top: 24px; margin-bottom: 8px;\">$1</p>\n<p style=\"font-family:sans-serif; font-size: 16px; margin-top: 8px; margin-bottom: 24px;\">$2</p>\n<p style=\"font-family:sans-serif; font-weight: bold; font-size: 16px; margin-bottom: 8px;\">$3</p>\n<ul style=\"font-family:sans-serif; font-size: 16px; margin-top: 0px; margin-bottom: 0px;\">\n<li>$4</li>\n<li>$5</li>\n</ul>\n<p style=\"font-family:sans-serif; font-size: 12px; margin-bottom: 8px; color: #808080;\">$6</p>\n</div>\n</div>\n</body>\n</html>"
    }
}