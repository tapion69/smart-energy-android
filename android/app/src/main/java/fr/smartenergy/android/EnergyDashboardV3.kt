package fr.smartenergy.android

import android.content.Context
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/** Inverter screen only. The pre-existing JK cockpit is NOT modified. */
class EnergyDashboardV3(context:Context) : WebView(context) {
    var onNavigate:((String)->Unit)?=null
    var onStyleChanged:((String)->Unit)?=null
    private var ready=false
    private var latest="{}"
    private var requestedSection="home"
    private val bridge=object {
        @JavascriptInterface fun navigate(destination:String) {
            post {onNavigate?.invoke(destination)}
        }
        @JavascriptInterface fun style(style:String) {
            if(style=="modern"||style=="sunsynk")post {onStyleChanged?.invoke(style)}
        }
    }
    init {
        setBackgroundColor(Color.rgb(7,20,30))
        isVerticalScrollBarEnabled=false
        overScrollMode=OVER_SCROLL_NEVER
        settings.javaScriptEnabled=true
        settings.domStorageEnabled=false
        settings.allowFileAccess=true
        settings.allowContentAccess=false
        settings.blockNetworkLoads=true
        settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
        addJavascriptInterface(bridge,"SmartEnergyApp")
        webViewClient=object:WebViewClient() {
            override fun shouldOverrideUrlLoading(view:WebView?,request:WebResourceRequest?)=true
            override fun onPageFinished(view:WebView?,url:String?) {
                if(url!="file:///android_asset/energy_dashboard_v3.html")return
                ready=true
                publish()
                applyRequestedSection()
            }
        }
        loadUrl("file:///android_asset/energy_dashboard_v3.html")
    }
    /** Native navigation is the only visible menu; HTML retains its inner tabs. */
    fun showSection(section:String) {
        requestedSection=if(section=="inverters")"inverters" else "home"
        if(ready)applyRequestedSection()
    }
    private fun applyRequestedSection() {
        val target=JSONObject.quote(requestedSection)
        evaluateJavascript("window.smartEnergySetPage($target);") {
            post {fitHeight()}
        }
    }
    fun showSnapshot(snapshot:JSONObject) {
        latest=snapshot.toString()
        if(ready)publish()
    }
    private fun publish() {
        evaluateJavascript("window.updateSmartEnergy($latest);") {
            post {fitHeight()}
        }
    }
    private fun fitHeight() {
        if(!ready||width<=0)return
        evaluateJavascript("Math.ceil(document.documentElement.scrollHeight).toString()") {result ->
            val h=result.trim('"').toDoubleOrNull()?:return@evaluateJavascript
            val px=(h*resources.displayMetrics.density).toInt().coerceIn(
                (360*resources.displayMetrics.density).toInt(),
                (3600*resources.displayMetrics.density).toInt())
            val p=layoutParams?:return@evaluateJavascript
            if(kotlin.math.abs(p.height-px)>8) {
                p.height=px
                layoutParams=p
            }
        }
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        if(w>0)post {fitHeight()}
    }
}
