/*
 * Copyright (c) 2022-2024 Tipz Team
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
package tipz.viola.webviewui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import tipz.viola.R
import tipz.viola.utils.CommonUtils
import tipz.viola.utils.UrlUtils
import tipz.viola.webview.VWebViewActivity

class CustomTabsActivity : VWebViewActivity() {
    private lateinit var title: AppCompatTextView
    private lateinit var host: AppCompatTextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_tab)
        appbar = findViewById(R.id.appbar)

        /* Back button */
        val actionBarBack : AppCompatImageView = findViewById(R.id.close)
        actionBarBack.setOnClickListener { finish() }

        /* Title and Host */
        title = findViewById(R.id.title)
        host = findViewById(R.id.host)

        /* Share */
        val share = findViewById<AppCompatImageView>(R.id.share)
        share.setOnClickListener { CommonUtils.shareUrl(this, webview.url) }

        /* Open in Browser */
        val openBrowser = findViewById<AppCompatImageView>(R.id.open_browser)
        openBrowser.setOnClickListener {
            val url = webview.url
            val intent = Intent(this, BrowserActivity::class.java)
            intent.data = Uri.parse(UrlUtils.patchUrlForCVEMitigation(url))
            startActivity(intent)
            finish()
        }

        /* Progress Bar */
        progressBar = findViewById(R.id.webviewProgressBar)

        /* Swipe Refresh Layout */
        swipeRefreshLayout = findViewById(R.id.layout_webview)

        // Setup Web App Mode
        if (intent.getBooleanExtra(EXTRA_LAUNCH_AS_WEBAPP, false))
            appbar.visibility = View.GONE

        /* WebView */
        webview = swipeRefreshLayout.findViewById(R.id.webview)
        webview.setUpdateHistory(false)

        // Finally, load webpge
        super.onStart()
        val dataUri = intent.data
        if (dataUri != null) webview.loadUrl(dataUri.toString())
    }

    override fun onUrlUpdated(url: String?) {
        host.text = Uri.parse(UrlUtils.patchUrlForCVEMitigation(url!!)).host
    }

    override fun onTitleUpdated(title: String?) {
        super.onTitleUpdated(title)
        this.title.text = title
    }

    companion object {
        const val EXTRA_LAUNCH_AS_WEBAPP = "launchAsWebApp"
    }
}
