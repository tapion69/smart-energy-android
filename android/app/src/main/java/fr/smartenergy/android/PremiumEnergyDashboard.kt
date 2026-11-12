package fr.smartenergy.android

import android.content.Context
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * Responsive read-only energy cockpit. All values are supplied from verified
 * JK and inverter snapshots; the HTML asset contains no fabricated telemetry.
 * Navigation, credentials, and device writes remain in native Android screens.
 */
class PremiumEnergyDashboard(context: Context) : WebView(context) {
    private var pageReady = false
    private var latestPayload = "{}"
    private var lastMeasuredWidth = 0

    init {
        setBackgroundColor(Color.rgb(5, 16, 31))
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.allowFileAccess = true // bundled android_asset only
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean = true // no external navigation or arbitrary web URLs

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != "file:///android_asset/energy_dashboard.html") return
                pageReady = true
                sendSnapshot()
                post { updateToDocumentHeight() }
            }
        }
        loadUrl("file:///android_asset/energy_dashboard.html")
    }

    /** Called from MainActivity's UI thread with a source-validated snapshot. */
    fun showSnapshot(snapshot: JSONObject) {
        latestPayload = snapshot.toString()
        if (pageReady) sendSnapshot()
    }

    private fun sendSnapshot() {
        // JSONObject escaping prevents input names from turning into JS source.
        evaluateJavascript("window.updateSmartEnergy($latestPayload);", null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && w != lastMeasuredWidth) {
            lastMeasuredWidth = w
            post { updateToDocumentHeight() }
        }
    }

    private fun updateToDocumentHeight() {
        if (!pageReady || width <= 0) return
        evaluateJavascript(
            "Math.ceil(document.documentElement.scrollHeight).toString()"
        ) { result ->
            val cssHeight = result.trim('"').toDoubleOrNull() ?: return@evaluateJavascript
            val pxHeight = (cssHeight * resources.displayMetrics.density).toInt()
                .coerceIn((360 * resources.displayMetrics.density).toInt(),
                          (2500 * resources.displayMetrics.density).toInt())
            val params = layoutParams ?: return@evaluateJavascript
            if (kotlin.math.abs(params.height - pxHeight) > 8) {
                params.height = pxHeight
                layoutParams = params
            }
        }
    }
}
