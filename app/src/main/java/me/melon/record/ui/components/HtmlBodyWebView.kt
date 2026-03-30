package me.melon.record.ui.components

import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun HtmlBodyWebView(
    htmlBody: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceColorCss = remember(onSurfaceColor) {
        "#%06X".format(0xFFFFFF and onSurfaceColor.toArgb())
    }
    var contentHeightDp by remember(htmlBody) { mutableStateOf(1.dp) }

    val wrappedHtml = remember(htmlBody) {
        """
        <html>
          <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
            <style>
              body { margin: 0; padding: 0; color: $onSurfaceColorCss; word-break: break-word; overflow-wrap: break-word; }
              img, video { max-width: 100%; height: auto; }
              pre, code { white-space: pre-wrap; word-break: break-word; }
            </style>
          </head>
          <body>$htmlBody</body>
        </html>
        """.trimIndent()
    }

    key(wrappedHtml) {
        AndroidView(
            modifier = modifier
                .fillMaxWidth()
                .height(contentHeightDp),
            factory = {
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    overScrollMode = WebView.OVER_SCROLL_NEVER
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    isNestedScrollingEnabled = false
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.loadsImagesAutomatically = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.evaluateJavascript(
                                "(function(){return Math.max(document.body.scrollHeight, document.documentElement.scrollHeight);})();"
                            ) { value ->
                                val cssHeight = value.trim('"').toFloatOrNull() ?: return@evaluateJavascript
                                val newHeightDp = cssHeight.dp
                                if (newHeightDp > 1.dp) {
                                    contentHeightDp = newHeightDp
                                }
                            }
                            view?.postDelayed({
                                view.evaluateJavascript(
                                    "(function(){return Math.max(document.body.scrollHeight, document.documentElement.scrollHeight);})();"
                                ) { value ->
                                    val cssHeight = value.trim('"').toFloatOrNull() ?: return@evaluateJavascript
                                    val newHeightDp = cssHeight.dp
                                    if (newHeightDp > 1.dp) {
                                        contentHeightDp = newHeightDp
                                    }
                                }
                            }, 200)
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            return true
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?
                        ): Boolean {
                            return true
                        }
                    }
                    setOnTouchListener { _, _ ->
                        parent?.requestDisallowInterceptTouchEvent(false)
                        false
                    }
                    loadDataWithBaseURL(
                        "https://localhost/",
                        wrappedHtml,
                        "text/html",
                        "utf-8",
                        null
                    )
                }
            },
            update = { webView ->
                webView.post {
                    webView.evaluateJavascript(
                        "(function(){return Math.max(document.body.scrollHeight, document.documentElement.scrollHeight);})();"
                    ) { value ->
                        val cssHeight = value.trim('"').toFloatOrNull() ?: return@evaluateJavascript
                        val newHeightDp = cssHeight.dp
                        if (newHeightDp > 1.dp) {
                            contentHeightDp = newHeightDp
                        }
                    }
                }
            }
        )
    }
}
