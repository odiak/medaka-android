package net.odiak.medaka

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.work.WorkManager
import net.odiak.medaka.theme.MedakaTheme

class LoginActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cookieManager = CookieManager.getInstance()
        val webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)

                if (url.startsWith("https://mdtlogin-ocl.medtronic.com/mmcl/auth")) {
                    view.evaluateJavascript(
                        """
                        (() => {
                            const e = document.createElement('style');
                            e.textContent = 'html,body { height: unset !important; }';
                            document.head.appendChild(e);
                        })();
                    """, null
                    )
                }

                val cookie = cookieManager.getCookie(url) ?: return
                val cookieParts = cookie.split(";")
                val cookieMap = cookieParts.mapNotNull { part ->
                    val kv = part.split("=", ignoreCase = false, limit = 2)
                    if (kv.size != 2) return@mapNotNull null
                    kv[0].trim() to kv[1].trim()
                }.toMap()

                val token = cookieMap["auth_tmp_token"]
                val tokenValidToStr = cookieMap["c_token_valid_to"]
                if (token.isNullOrBlank() || tokenValidToStr.isNullOrBlank()) return

                val tokenValidTo = tokenValidToStr.parseTokenValidTo()

                if (tokenValidTo < System.currentTimeMillis()) return

                Worker.setToken(this@LoginActivity, token, tokenValidTo)

                cookieManager.setCookie("https://carelink.minimed.eu/", "auth_tmp_token=")
                cookieManager.setCookie("https://carelink.minimed.eu/", "c_token_valid_to=")

                val workManager = WorkManager.getInstance(this@LoginActivity)
                Worker.enqueue(workManager)

                finish()
            }
        }

        setContent {
            val settings = settingsDataStore.data.collectAsState(null)

            MedakaTheme {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(),
                    factory = ::WebView,
                    update = { webView ->
                        webView.webViewClient = webViewClient
                        webView.settings.javaScriptEnabled = true
                        webView.settings.loadsImagesAutomatically = true
                        webView.settings.domStorageEnabled = true
                        webView.settings.userAgentString = "Test"
                        cookieManager.setAcceptThirdPartyCookies(webView, true)

                        val s = settings.value
                        val url = if (s?.isValid() == true) {
                            "https://carelink.minimed.eu/app/login?country=${s.country}&language=${s.language}"
                        } else {
                            "https://carelink.minimed.eu/"
                        }
                        webView.loadUrl(url)
                    })
            }
        }
    }
}