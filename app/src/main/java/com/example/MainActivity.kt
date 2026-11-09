package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.config.AppConfig
import com.example.ui.components.KissaWebView
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private data class DeepLinkRequest(val url: String, val nonce: Long)

    private var deepLinkRequest by mutableStateOf<DeepLinkRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setDeepLink(intent?.data)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                    ) {
                        KissaWebView(
                            url = AppConfig.BASE_URL,
                            incomingUrl = deepLinkRequest?.url,
                            incomingUrlNonce = deepLinkRequest?.nonce ?: 0L
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setDeepLink(intent.data)
    }

    private fun setDeepLink(uri: Uri?) {
        resolveDeepLink(uri)?.let { target ->
            deepLinkRequest = DeepLinkRequest(target, System.nanoTime())
        }
    }

    private fun resolveDeepLink(uri: Uri?): String? {
        if (uri == null) return null

        if (uri.scheme.equals("https", ignoreCase = true) && uri.host == "kissaai.pages.dev") {
            return uri.toString()
        }

        if (!uri.scheme.equals("kissaai", ignoreCase = true)) return null

        val host = uri.host.orEmpty().trim('/').trim()
        val path = uri.encodedPath.orEmpty().trim('/').trim()
        val routeParts = when {
            host == "auth" -> listOf("auth", path)
            host.isBlank() -> listOf(path)
            else -> listOf(host, path)
        }.filter { it.isNotBlank() }

        if (routeParts.isEmpty()) return AppConfig.BASE_URL

        return buildString {
            append(AppConfig.BASE_URL.trimEnd('/'))
            append('/')
            append(routeParts.joinToString("/"))
            uri.encodedQuery?.let { append('?').append(it) }
            uri.encodedFragment?.let { append('#').append(it) }
        }
    }
}
