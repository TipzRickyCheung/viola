/*
 * Copyright (c) 2020-2024 Tipz Team
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
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.util.AttributeSet
import android.view.ContextMenu
import android.view.ContextMenu.ContextMenuInfo
import android.view.View
import android.webkit.CookieManager
import android.webkit.CookieSyncManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tipz.viola.Application
import tipz.viola.BuildConfig
import tipz.viola.R
import tipz.viola.broha.api.HistoryApi
import tipz.viola.broha.api.HistoryUtils
import tipz.viola.broha.database.Broha
import tipz.viola.search.SearchEngineEntries
import tipz.viola.settings.SettingsKeys
import tipz.viola.utils.CommonUtils
import tipz.viola.utils.DownloadUtils
import tipz.viola.utils.InternalUrls
import tipz.viola.utils.UrlUtils
import tipz.viola.webviewui.BaseActivity

@SuppressLint("SetJavaScriptEnabled")
class VWebView(private val mContext: Context, attrs: AttributeSet?) : WebView(
    mContext, attrs
) {
    private var mVioWebViewActivity: VWebViewActivity? = null
    private val iconHashClient = (mContext.applicationContext as Application).iconHashClient!!
    private val webSettings = this.settings
    private var currentBroha: Broha? = null
    private var updateHistory = true
    private var historyCommitted = false
    private val settingsPreference =
        (mContext.applicationContext as Application).settingsPreference!!
    internal var adServersHandler: AdServersHandler

    private val mRequestHeaders = HashMap<String, String>()

    private val titleHandler = Handler { message ->
        val webLongPress = HitTestAlertDialog(mContext)
        if (!webLongPress.setupDialogForShowing(this, message.data)) return@Handler false
        webLongPress.show()

        return@Handler true
    }

    enum class PageLoadState {
        PAGE_STARTED, PAGE_FINISHED, UPDATE_HISTORY, UPDATE_FAVICON, UPDATE_TITLE, UNKNOWN
    }

    init {
        /* User agent init code */
        setUserAgent(UserAgentMode.MOBILE, UserAgentBundle())

        /* Start the download manager service */
        setDownloadListener { url: String?, _: String?, contentDisposition: String?, mimeType: String?, _: Long ->
            if (ContextCompat.checkSelfPermission(
                    mVioWebViewActivity!!,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_DENIED
            )
                ActivityCompat.requestPermissions(
                    mVioWebViewActivity!!,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 0
                )

            DownloadUtils.dmDownloadFile(
                mContext, url!!, contentDisposition,
                mimeType, getUrl()
            )
            onPageInformationUpdated(PageLoadState.UNKNOWN, originalUrl!!, null)
            mVioWebViewActivity!!.onPageLoadProgressChanged(0)
            if (!canGoBack() && originalUrl == null && settingsPreference.getIntBool(SettingsKeys.closeAppAfterDownload))
                mVioWebViewActivity!!.finish()
        }
        setLayerType(LAYER_TYPE_HARDWARE, null)

        // Zoom controls
        webSettings.setSupportZoom(true)
        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false

        // Also increase text size to fill the viewport (this mirrors the behaviour of Firefox,
        // Chrome does this in the current Chrome Dev, but not Chrome release).
        webSettings.layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING

        // Disable file access
        // Disabled as it no longer functions since Android 11
        webSettings.allowFileAccess = false
        webSettings.allowContentAccess = false
        webSettings.allowFileAccessFromFileURLs = false
        webSettings.allowUniversalAccessFromFileURLs = false

        // Enable some HTML5 related settings
        webSettings.databaseEnabled = false // Disabled as no-op since Android 15
        webSettings.domStorageEnabled = true

        // Ad Server Hosts
        adServersHandler = AdServersHandler(mContext, settingsPreference)

        this.webViewClient = VWebViewClient(mContext, this, adServersHandler)
        this.webChromeClient = VChromeWebClient(mContext, this)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE))
            WebViewCompat.setWebViewRenderProcessClient(
                this,
                VWebViewRenderProcessClient(mContext, this)
            )

        /* Hit Test Menu */
        setOnCreateContextMenuListener { _: ContextMenu?, _: View?, _: ContextMenuInfo? ->
            val message = titleHandler.obtainMessage()
            this.requestFocusNodeHref(message)
        }
    }

    @Suppress("deprecation")
    fun doSettingsCheck() {
        // Dark mode
        val darkMode = BaseActivity.getDarkMode(mContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && WebViewFeature.isFeatureSupported(
                WebViewFeature.ALGORITHMIC_DARKENING
            )
        )
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webSettings, darkMode)
        else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK))
            WebSettingsCompat.setForceDark(
                webSettings,
                if (darkMode) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
            )

        // Javascript
        webSettings.javaScriptEnabled =
            settingsPreference.getIntBool(SettingsKeys.isJavaScriptEnabled)
        webSettings.javaScriptCanOpenWindowsAutomatically =
            settingsPreference.getIntBool(SettingsKeys.isJavaScriptEnabled)

        // HTTPS enforce setting
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) webSettings.mixedContentMode =
            if (settingsPreference.getIntBool(SettingsKeys.enforceHttps)) WebSettings.MIXED_CONTENT_NEVER_ALLOW else WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // Google's "Safe" Browsing
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE))
            WebSettingsCompat.setSafeBrowsingEnabled(
                webSettings,
                settingsPreference.getIntBool(SettingsKeys.enableGoogleSafeBrowse)
            )

        // Do Not Track request
        mRequestHeaders["DNT"] = settingsPreference.getInt(SettingsKeys.sendDNT).toString()

        // Global Privacy Control
        mRequestHeaders["Sec-GPC"] = settingsPreference.getInt(SettingsKeys.sendSecGPC).toString()

        // Data Saver
        mRequestHeaders["Save-Data"] = settingsPreference.getInt(SettingsKeys.sendSaveData).toString()

        // Ad Servers Hosts
        if (settingsPreference.getIntBool(SettingsKeys.enableAdBlock))
            adServersHandler.importAdServers()
    }

    fun notifyViewSetup() {
        mVioWebViewActivity = mContext as VWebViewActivity
    }

    fun setUpdateHistory(value: Boolean) {
        updateHistory = value
    }

    fun onSslErrorProceed() {
        mVioWebViewActivity?.onSslErrorProceed()
    }

    override fun loadUrl(url: String) {
        if (url.isBlank()) return
        if (url == InternalUrls.aboutBlankUrl) {
            super.loadUrl(url)
            return
        }

        // Check for internal URLs
        if (url == InternalUrls.licenseUrl) {
            super.loadUrl(InternalUrls.realLicenseUrl)
            return
        }

        // Update to start page layout
        val startPageLayout = mVioWebViewActivity?.startPageLayout
        if (url == InternalUrls.startUrl) {
            this.loadUrl(InternalUrls.aboutBlankUrl)
            this.visibility = GONE
            mVioWebViewActivity?.swipeRefreshLayout?.visibility = GONE
            startPageLayout?.visibility = VISIBLE
            mVioWebViewActivity?.onSslCertificateUpdated()
            return
        }
        if (this.visibility == GONE) {
            this.visibility = VISIBLE
            mVioWebViewActivity?.swipeRefreshLayout?.visibility = VISIBLE
            startPageLayout?.visibility = GONE
        }

        val checkedUrl = UrlUtils.toSearchOrValidUrl(mContext, url)
        onPageInformationUpdated(PageLoadState.UNKNOWN, checkedUrl, null)

        // Load URL
        super.loadUrl(checkedUrl, mRequestHeaders)
    }

    override fun reload() {
        loadUrl(getUrl())
    }

    override fun getUrl(): String {
        val superUrl = super.getUrl()
        return if (superUrl.isNullOrBlank()) InternalUrls.aboutBlankUrl else superUrl
    }

    override fun goBack() {
        mVioWebViewActivity!!.onDropDownDismissed()
        super.goBack()
    }

    override fun goForward() {
        mVioWebViewActivity!!.onDropDownDismissed()
        super.goForward()
    }

    fun onPageInformationUpdated(state: PageLoadState, url: String?, favicon: Bitmap?) {
        if (url == InternalUrls.aboutBlankUrl) return
        val currentUrl = getUrl()

        when (state) {
            PageLoadState.PAGE_STARTED -> {
                if (currentUrl.startsWith("view-source:")) return
                mVioWebViewActivity!!.onFaviconProgressUpdated(true)
                mVioWebViewActivity!!.onPageLoadProgressChanged(-1)
            }

            PageLoadState.PAGE_FINISHED -> {
                mVioWebViewActivity!!.onFaviconProgressUpdated(false)
                mVioWebViewActivity!!.onPageLoadProgressChanged(0)
                mVioWebViewActivity!!.onSslCertificateUpdated()
            }

            PageLoadState.UPDATE_HISTORY -> {
                if (updateHistory) {
                    currentBroha = Broha(title, currentUrl)
                    historyCommitted = false
                }
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT_WATCH) CookieSyncManager.getInstance()
                    .sync() else CookieManager.getInstance().flush()
                mVioWebViewActivity!!.onSwipeRefreshLayoutRefreshingUpdated(false)
            }

            PageLoadState.UPDATE_FAVICON -> {
                if (!historyCommitted && updateHistory) {
                    CoroutineScope(Dispatchers.IO).launch {
                        currentBroha!!.iconHash = iconHashClient.save(favicon!!)
                        if (HistoryUtils.lastUrl(mContext) != currentUrl) {
                            HistoryApi.historyBroha(mContext)!!.insertAll(currentBroha!!)
                        }
                    }
                    historyCommitted = true
                }
            }

            PageLoadState.UPDATE_TITLE -> {
                mVioWebViewActivity!!.onTitleUpdated(
                    if (this.visibility == View.GONE) resources.getString(
                        R.string.start_page
                    ) else title
                )
            }

            PageLoadState.UNKNOWN -> {
            }
        }

        if (url != null) mVioWebViewActivity!!.onUrlUpdated(url)
        mVioWebViewActivity!!.onFaviconUpdated(favicon, false)
        mVioWebViewActivity!!.onDropDownDismissed()
    }

    fun onPageLoadProgressChanged(progress: Int) {
        mVioWebViewActivity!!.onPageLoadProgressChanged(progress)
    }

    fun setUserAgent(agentMode: UserAgentMode, dataBundle: UserAgentBundle) {
        if (agentMode == UserAgentMode.CUSTOM && dataBundle.userAgentString.isBlank()) return

        val targetResId = {
            when (agentMode) {
                UserAgentMode.MOBILE -> R.drawable.smartphone
                UserAgentMode.DESKTOP -> R.drawable.desktop
                UserAgentMode.CUSTOM -> R.drawable.custom
            }
        }
        val mobile = if (agentMode == UserAgentMode.MOBILE) "Mobile" else CommonUtils.EMPTY_STRING
        val userAgentHolder = if (agentMode == UserAgentMode.MOBILE || agentMode == UserAgentMode.DESKTOP) {
            "Mozilla/5.0 (Linux) AppleWebKit/537.36 KHTML, like Gecko) Chrome/${
                    WebViewCompat.getCurrentWebViewPackage(
                        mContext
                    )?.versionName
                } $mobile Safari/537.36 Viola/${BuildConfig.VERSION_NAME + BuildConfig.VERSION_NAME_EXTRA}"
        } else if (agentMode == UserAgentMode.CUSTOM) {
            dataBundle.userAgentString
        } else {
            CommonUtils.EMPTY_STRING
        }

        if (agentMode == UserAgentMode.DESKTOP) dataBundle.enableDesktop = true

        webSettings.userAgentString = userAgentHolder
        if (dataBundle.iconView != null) {
            dataBundle.iconView!!.setImageResource(targetResId())
            dataBundle.iconView!!.tag = targetResId()
        }
        webSettings.loadWithOverviewMode = dataBundle.enableDesktop
        webSettings.useWideViewPort = dataBundle.enableDesktop
        super.setScrollBarStyle(if (dataBundle.enableDesktop) SCROLLBARS_OUTSIDE_OVERLAY else SCROLLBARS_INSIDE_OVERLAY)

        if (!dataBundle.noReload) reload()
    }

    enum class UserAgentMode {
        MOBILE, DESKTOP, CUSTOM
    }

    class UserAgentBundle {
        var userAgentString = CommonUtils.EMPTY_STRING
        var iconView: AppCompatImageView? = null
        var enableDesktop = false // Defaults to true with UserAgentMode.DESKTOP
        var noReload = false
    }

    fun loadHomepage(useStartPage : Boolean) {
        if (useStartPage) {
            loadUrl(InternalUrls.startUrl)
        } else {
            loadUrl(
                SearchEngineEntries.getHomePageUrl(
                settingsPreference, settingsPreference.getInt(SettingsKeys.defaultHomePageId)))
        }

    }

    fun loadViewSourcePage(url: String?) {
        val currentUrl = if (url.isNullOrBlank()) getUrl() else url
        if (currentUrl.isBlank()) return // getUrl() returns empty string when on start page
        loadUrl("view-source:$currentUrl")
    }
}