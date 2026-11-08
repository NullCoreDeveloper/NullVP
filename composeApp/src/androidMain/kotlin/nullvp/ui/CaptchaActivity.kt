package nullvp.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import nullvp.core.AndroidCoreBridge
import java.io.File

class CaptchaActivity : Activity() {

    private var isSolved = false
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    class CaptchaInterface(private val onToken: (String) -> Unit) {
        @JavascriptInterface
        fun onCaptchaSuccess(token: String) {
            onToken(token)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0E17"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Cyberpunk Header Bar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val backButton = TextView(this).apply {
            text = "✕"
            textSize = 20f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, dpToPx(16), 0)
            setOnClickListener { finish() }
        }

        val titleView = TextView(this).apply {
            text = "Проверка VK"
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val reloadButton = TextView(this).apply {
            text = "↻"
            textSize = 22f
            setTextColor(Color.parseColor("#00E5FF"))
            setPadding(dpToPx(8), 0, 0, 0)
            setOnClickListener { webView.reload() }
        }

        header.addView(backButton)
        header.addView(titleView)
        header.addView(reloadButton)

        // 2. Loading progress bar
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.VISIBLE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(3)
            )
        }

        // 3. WebView
        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0A0E17"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        rootLayout.addView(header)
        rootLayout.addView(progressBar)
        rootLayout.addView(webView)
        setContentView(rootLayout)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            databaseEnabled = true
            setSupportZoom(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.addJavascriptInterface(CaptchaInterface { token ->
            runOnUiThread {
                deliverToken(token)
            }
        }, "AndroidCaptcha")

        val jsHook = """
            (function() {
                if (window.__captchaHooked) return;
                window.__captchaHooked = true;

                function report(token) {
                    if (!token || typeof token !== 'string') return;
                    try {
                        if (window.AndroidCaptcha) {
                            window.AndroidCaptcha.onCaptchaSuccess(token);
                        }
                    } catch(e) {}
                }

                function checkObj(obj) {
                    if (!obj) return;
                    if (typeof obj === 'string') {
                        try {
                            checkObj(JSON.parse(obj));
                            return;
                        } catch(e) {
                            if (obj.indexOf('success_token=') !== -1) {
                                var m = obj.match(/success_token=([A-Za-z0-9_\-\.]+)/);
                                if (m && m[1]) report(m[1]);
                            }
                        }
                        return;
                    }
                    if (obj.success_token) report(obj.success_token);
                    if (obj.response && obj.response.success_token) report(obj.response.success_token);
                    if (obj.data && obj.data.success_token) report(obj.data.success_token);
                    if (obj.successToken) report(obj.successToken);
                }

                var origFetch = window.fetch;
                if (origFetch) {
                    window.fetch = function() {
                        var p = origFetch.apply(this, arguments);
                        p.then(function(r) {
                            try {
                                r.clone().text().then(checkObj);
                            } catch(e) {}
                        }).catch(function() {});
                        return p;
                    };
                }

                var origSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.send = function() {
                    this.addEventListener('load', function() {
                        checkObj(this.responseText);
                    });
                    return origSend.apply(this, arguments);
                };

                window.addEventListener('message', function(event) {
                    if (event && event.data) {
                        checkObj(event.data);
                    }
                });

                document.addEventListener('submit', function(e) {
                    try {
                        var form = e.target;
                        var el = form.querySelector('input[name="success_token"]') || form.querySelector('input[name="token"]');
                        if (el && el.value) report(el.value);
                    } catch(e) {}
                }, true);

                setInterval(function() {
                    var el = document.querySelector('input[name="success_token"]') || document.querySelector('[data-success-token]');
                    if (el) {
                        var val = el.value || el.getAttribute('data-success-token');
                        if (val) report(val);
                    }
                }, 400);
            })();
        """.trimIndent()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                checkUrlForToken(url)
                view?.evaluateJavascript(jsHook, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                checkUrlForToken(url)
                view?.evaluateJavascript(jsHook, null)
            }

            override fun onLoadResource(view: WebView?, url: String?) {
                super.onLoadResource(view, url)
                checkUrlForToken(url)
                view?.evaluateJavascript(jsHook, null)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (checkUrlForToken(url)) {
                    return true
                }
                return false
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress >= 100) {
                    progressBar.visibility = View.GONE
                } else {
                    progressBar.visibility = View.VISIBLE
                }
            }

            override fun onCloseWindow(window: WebView?) {
                finish()
            }
        }

        val url = intent.getStringExtra("url")
        if (url.isNullOrEmpty()) {
            finish()
            return
        }
        AndroidCoreBridge.addLog("[CAPTCHA_UI] Загрузка капчи в приложении: $url")
        webView.loadUrl(url)
    }

    private fun checkUrlForToken(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        try {
            val uri = Uri.parse(url)
            val token = uri.getQueryParameter("success_token")
                ?: uri.getQueryParameter("token")
                ?: uri.fragment?.let { frag ->
                    if (frag.contains("success_token=")) {
                        Uri.parse("https://dummy/?$frag").getQueryParameter("success_token")
                    } else null
                }
            if (!token.isNullOrEmpty()) {
                deliverToken(token)
                return true
            }
        } catch (e: Exception) {
            AndroidCoreBridge.addLog("[CAPTCHA_UI] Ошибка разбора URL: ${e.message}")
        }
        return false
    }

    private fun deliverToken(token: String) {
        if (isSolved) return
        isSolved = true
        AndroidCoreBridge.addLog("[CAPTCHA_UI] Капча успешно решена в приложении! Доставка токена (${token.take(15)}...)")

        // 1. Submit to bridge and native core
        AndroidCoreBridge.submitCaptchaToken(token)

        // 2. Direct submit via libv2ray
        runCatching {
            libv2ray.Libv2ray.submitCaptchaToken(token)
        }

        // 3. Shared internal storage file for cross-process daemon fallback
        runCatching {
            val file = File(filesDir, "vk_captcha_token.tmp")
            file.writeText(token)
            AndroidCoreBridge.addLog("[CAPTCHA_UI] Токен сохранен во временный файл: ${file.absolutePath}")
        }

        Toast.makeText(applicationContext, "Капча VK пройдена! Подключение...", Toast.LENGTH_SHORT).show()
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 500)
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    companion object {
        fun start(context: Context, url: String) {
            val intent = Intent(context, CaptchaActivity::class.java).apply {
                putExtra("url", url)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(intent)
        }
    }
}

