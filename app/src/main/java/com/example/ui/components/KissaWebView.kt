package com.example.ui.components

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.auth.GoogleAuthManager
import com.example.config.AppConfig
import kotlinx.coroutines.delay

private const val TAG = "KissaWebView"

fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    val capabilities = cm.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/**
 * Native JavaScript Interface exposed to web pages:
 * window.AndroidNative.signInWithGoogle(clientId)
 * window.AndroidNative.openCustomTab(url)
 */
class AndroidNativeInterface(
    private val activity: Activity,
    private val webViewProvider: () -> WebView?
) {
    @JavascriptInterface
    fun signInWithGoogle(serverClientId: String?) {
        activity.runOnUiThread {
            val effectiveClientId = if (!serverClientId.isNullOrBlank()) {
                serverClientId
            } else {
                AppConfig.GOOGLE_WEB_CLIENT_ID
            }

            GoogleAuthManager.signInWithGoogle(
                activity = activity,
                serverClientId = effectiveClientId,
                fallbackAuthUrl = null,
                onSuccess = { idToken ->
                    webViewProvider()?.evaluateJavascript(
                        "if (window.onGoogleSignInSuccess) { window.onGoogleSignInSuccess('$idToken'); } else if (window.handleCredentialResponse) { window.handleCredentialResponse({credential: '$idToken'}); }",
                        null
                    )
                },
                onError = { error ->
                    Log.w(TAG, "Native Google Sign-In error: $error")
                }
            )
        }
    }

    @JavascriptInterface
    fun openCustomTab(url: String?) {
        if (!url.isNullOrBlank()) {
            activity.runOnUiThread {
                GoogleAuthManager.openChromeCustomTab(activity, url)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KissaWebView(
    url: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var loadingProgress by remember { mutableFloatStateOf(0f) }
    var isLoading by remember { mutableStateOf(true) }

    // Offline & Connection States
    var hasLoadedSuccessfully by remember { mutableStateOf(false) }
    var isFullScreenOffline by remember { mutableStateOf(false) }
    var isOfflineBannerVisible by remember { mutableStateOf(false) }
    var isReconnectedBannerVisible by remember { mutableStateOf(false) }
    var lastFailedUrl by remember { mutableStateOf<String?>(null) }

    // File chooser callback for <input type="file">
    var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val dataIntent = result.data
            val results: Array<Uri>? = when {
                dataIntent?.dataString != null -> arrayOf(Uri.parse(dataIntent.dataString))
                dataIntent?.clipData != null -> {
                    val clipData = dataIntent.clipData!!
                    Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                }
                else -> null
            }
            filePathCallback?.onReceiveValue(results)
        } else {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    // Dedicated Activity Result Launcher for UPI Payments (PhonePe, BHIM, GPay, Paytm, CRED, etc.)
    val upiPaymentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val response = result.data?.getStringExtra("response") ?: ""
        Log.d(TAG, "UPI Payment returned: resultCode=${result.resultCode}, response=$response")
        webViewInstance?.evaluateJavascript(
            "window.focus(); if (window.onUpiPaymentCompleted) { window.onUpiPaymentCompleted('$response'); }",
            null
        )
    }

    // Android back navigation handler
    BackHandler(enabled = canGoBack) {
        webViewInstance?.let { webView ->
            if (webView.canGoBack()) {
                webView.goBack()
            }
        }
    }

    // Live Network Monitoring with Auto-Reconnect (like WhatsApp / YouTube)
    DisposableEffect(context) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Handler(Looper.getMainLooper()).post {
                    Log.d(TAG, "Network is available - auto reconnecting...")
                    if (isFullScreenOffline) {
                        // Automatically reconnect without user needing to touch anything!
                        isFullScreenOffline = false
                        val target = lastFailedUrl ?: url
                        webViewInstance?.loadUrl(target)
                    } else if (isOfflineBannerVisible) {
                        isOfflineBannerVisible = false
                        isReconnectedBannerVisible = true
                    }
                }
            }

            override fun onLost(network: Network) {
                Handler(Looper.getMainLooper()).post {
                    Log.d(TAG, "Network connection lost.")
                    if (!hasLoadedSuccessfully) {
                        // Initial launch with no internet: show full screen offline view
                        isFullScreenOffline = true
                    } else {
                        // Already loaded page stays visible smoothly; only show subtle floating offline pill!
                        isOfflineBannerVisible = true
                    }
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)

        onDispose {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }
    }

    // Auto-hide the "Back Online" green banner after 2.5 seconds
    LaunchedEffect(isReconnectedBannerVisible) {
        if (isReconnectedBannerVisible) {
            delay(2500)
            isReconnectedBannerVisible = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (isFullScreenOffline) {
            // Full screen offline view with logo and auto-reconnecting pulse
            OfflineView(
                isWaitingForConnection = true,
                errorMessage = "Waiting for network connection... Kissa AI will automatically reconnect as soon as you're online.",
                onRetry = {
                    if (isNetworkAvailable(context)) {
                        isFullScreenOffline = false
                        val target = lastFailedUrl ?: url
                        webViewInstance?.loadUrl(target)
                    } else {
                        Toast.makeText(context, "Waiting for network connection...", Toast.LENGTH_SHORT).show()
                    }
                },
                onOpenInBrowser = {
                    try {
                        GoogleAuthManager.openChromeCustomTab(context, lastFailedUrl ?: url)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Could not open browser: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        } else {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        // Clean Chrome Mobile User-Agent so Razorpay unlocks UPI option
                        val rawUa = settings.userAgentString
                        val cleanChromeUa = rawUa
                            .replace("; wv", "")
                            .replace("Version/4.0 ", "")

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            setSupportZoom(true)
                            builtInZoomControls = false
                            displayZoomControls = false
                            allowFileAccess = true
                            allowContentAccess = true
                            mediaPlaybackRequiresUserGesture = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            // Smooth caching: load from cache when offline so page stays accessible!
                            cacheMode = if (isNetworkAvailable(ctx)) {
                                WebSettings.LOAD_DEFAULT
                            } else {
                                WebSettings.LOAD_CACHE_ELSE_NETWORK
                            }
                            userAgentString = cleanChromeUa
                            javaScriptCanOpenWindowsAutomatically = true
                            setSupportMultipleWindows(true)
                        }

                        // Enable Cookies & Third-Party Cookies
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        // Attach native JS bridge for Google Auth & Custom Tabs
                        if (activity != null) {
                            addJavascriptInterface(
                                AndroidNativeInterface(activity) { webViewInstance },
                                "AndroidNative"
                            )
                        }

                        // Handle downloads
                        setDownloadListener(DownloadListener { downloadUrl, userAgent, _, mimetype, _ ->
                            try {
                                val downloadReq = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                                    setMimeType(mimetype)
                                    addRequestHeader("User-Agent", userAgent)
                                    setDescription("Downloading file from Kissa AI...")
                                    setTitle(Uri.parse(downloadUrl).lastPathSegment ?: "kissa_ai_download")
                                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                    setDestinationInExternalPublicDir(
                                        Environment.DIRECTORY_DOWNLOADS,
                                        Uri.parse(downloadUrl).lastPathSegment ?: "kissa_ai_download"
                                    )
                                }
                                val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                dm.enqueue(downloadReq)
                                Toast.makeText(ctx, "Download started...", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                                    ctx.startActivity(intent)
                                } catch (_: Exception) {
                                    Toast.makeText(ctx, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        })

                        // WebChromeClient
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loadingProgress = newProgress / 100f
                                isLoading = newProgress < 100
                            }

                            override fun onShowFileChooser(
                                webView: WebView?,
                                filePathCallbackLocal: ValueCallback<Array<Uri>>?,
                                fileChooserParams: FileChooserParams?
                            ): Boolean {
                                filePathCallback?.onReceiveValue(null)
                                filePathCallback = filePathCallbackLocal

                                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                    type = "*/*"
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                }

                                try {
                                    fileChooserLauncher.launch(intent)
                                } catch (e: Exception) {
                                    filePathCallback = null
                                    return false
                                }
                                return true
                            }

                            override fun onPermissionRequest(request: PermissionRequest?) {
                                request?.grant(request.resources)
                            }

                            override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: Message?
                            ): Boolean {
                                val newWebView = WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.userAgentString = cleanChromeUa
                                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                                            val popupUrl = req?.url?.toString() ?: return false
                                            return handlePaymentAndAuthSchemes(
                                                ctx = ctx,
                                                activity = activity,
                                                view = view,
                                                url = popupUrl,
                                                launchUpiIntent = { intent -> upiPaymentLauncher.launch(intent) }
                                            )
                                        }
                                    }
                                }
                                val transport = resultMsg?.obj as? WebView.WebViewTransport
                                transport?.webView = newWebView
                                resultMsg?.sendToTarget()
                                return true
                            }
                        }

                        // WebViewClient with smart offline suppression
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isLoading = true
                                canGoBack = view?.canGoBack() == true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                canGoBack = view?.canGoBack() == true
                                if (!isFullScreenOffline) {
                                    hasLoadedSuccessfully = true
                                    isOfflineBannerVisible = !isNetworkAvailable(ctx)
                                }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request?.isForMainFrame == true) {
                                    val errorCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        error?.errorCode ?: 0
                                    } else 0

                                    // Only show full-screen offline if network failure prevents loading a new page
                                    val isNetworkError = when (errorCode) {
                                        ERROR_HOST_LOOKUP,
                                        ERROR_CONNECT,
                                        ERROR_TIMEOUT,
                                        ERROR_FAILED_SSL_HANDSHAKE -> true
                                        else -> !isNetworkAvailable(ctx)
                                    }

                                    if (isNetworkError) {
                                        lastFailedUrl = request.url?.toString()
                                        if (!hasLoadedSuccessfully) {
                                            // Initial page could not load: show full screen offline
                                            isFullScreenOffline = true
                                        } else {
                                            // Already browsing: only show full screen if page completely failed to render
                                            isFullScreenOffline = true
                                        }
                                    }
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val requestUrl = request?.url?.toString() ?: return false
                                return handlePaymentAndAuthSchemes(
                                    ctx = ctx,
                                    activity = activity,
                                    view = view,
                                    url = requestUrl,
                                    launchUpiIntent = { intent -> upiPaymentLauncher.launch(intent) }
                                )
                            }
                        }

                        loadUrl(url)
                        webViewInstance = this
                    }
                },
                update = { webView ->
                    webViewInstance = webView
                },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("kissa_main_webview")
            )
        }

        // Sleek top progress indicator when loading
        AnimatedVisibility(
            visible = isLoading && !isFullScreenOffline,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
        ) {
            LinearProgressIndicator(
                progress = { loadingProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = Color(0xFFEC0303),
                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        }

        // Floating Non-intrusive Offline Pill (when browsing while connection drops, like WhatsApp/YouTube)
        AnimatedVisibility(
            visible = isOfflineBannerVisible && !isFullScreenOffline,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF262626)),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                modifier = Modifier
                    .clickable {
                        if (isNetworkAvailable(context)) {
                            isOfflineBannerVisible = false
                            webViewInstance?.reload()
                        } else {
                            Toast.makeText(context, "Still offline. Will auto-reconnect when network returns.", Toast.LENGTH_SHORT).show()
                        }
                    }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.WifiOff,
                        contentDescription = null,
                        tint = Color(0xFFFFB4AB),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Offline Mode • Auto-reconnecting",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = Color.White
                    )
                }
            }
        }

        // "Back Online" temporary confirmation banner
        AnimatedVisibility(
            visible = isReconnectedBannerVisible && !isFullScreenOffline,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20)),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFFA5D6A7),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Back Online",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Keep WebView alive during background payment navigation;
        }
    }
}

/**
 * Intercepts payment schemes (UPI, GPay, PhonePe, Paytm, BHIM, CRED, Navi, etc.)
 * and Google OAuth URLs.
 */
private fun handlePaymentAndAuthSchemes(
    ctx: Context,
    activity: Activity?,
    view: WebView?,
    url: String,
    launchUpiIntent: (Intent) -> Unit
): Boolean {
    // 1. Google OAuth Interceptor: Trigger Native Credential Manager 2.0 FIRST!
    if (url.contains("accounts.google.com") || url.contains("apis.google.com/signin")) {
        val uri = Uri.parse(url)
        val extractedClientId = uri.getQueryParameter("client_id")
        val effectiveClientId = if (!extractedClientId.isNullOrBlank()) {
            extractedClientId
        } else {
            AppConfig.GOOGLE_WEB_CLIENT_ID
        }

        if (activity != null && effectiveClientId.isNotBlank()) {
            Log.d(TAG, "Triggering Native Credential Manager 2.0 first for Google Sign-In (Client ID: $effectiveClientId)")
            GoogleAuthManager.signInWithGoogle(
                activity = activity,
                serverClientId = effectiveClientId,
                fallbackAuthUrl = url,
                onSuccess = { idToken ->
                    val redirectUri = uri.getQueryParameter("redirect_uri")
                    if (!redirectUri.isNullOrBlank()) {
                        val finalUrl = if (redirectUri.contains("?")) {
                            "$redirectUri&id_token=$idToken&credential=$idToken"
                        } else {
                            "$redirectUri?id_token=$idToken&credential=$idToken"
                        }
                        view?.loadUrl(finalUrl)
                    } else {
                        view?.evaluateJavascript(
                            "if (window.onGoogleSignInSuccess) { window.onGoogleSignInSuccess('$idToken'); } else if (window.handleCredentialResponse) { window.handleCredentialResponse({credential: '$idToken'}); }",
                            null
                        )
                    }
                },
                onError = { error ->
                    Log.w(TAG, "Native Credential Manager failed ($error). Opening Chrome Custom Tab fallback.")
                    GoogleAuthManager.openChromeCustomTab(ctx, url)
                }
            )
            return true
        } else {
            GoogleAuthManager.openChromeCustomTab(ctx, url)
            return true
        }
    }

    // 2. Comprehensive Indian UPI Payment Apps Interceptor
    val isDirectUpi = url.startsWith("upi://")
    val isIntentUpi = url.startsWith("intent://")
    val isAppCustomUpi = url.startsWith("phonepe://") ||
            url.startsWith("gpay://") ||
            url.startsWith("paytmmp://") ||
            url.startsWith("bhim://") ||
            url.startsWith("credpay://") ||
            url.startsWith("amazonpay://") ||
            url.startsWith("mobikwik://") ||
            url.startsWith("navi://") ||
            url.startsWith("jupiter://") ||
            url.startsWith("fi://") ||
            url.startsWith("slice://") ||
            url.startsWith("tataneu://")

    if (isDirectUpi || isIntentUpi || isAppCustomUpi) {
        try {
            var intent: Intent? = null

            if (isIntentUpi) {
                val parsed = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                parsed.flags = parsed.flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
                intent = parsed
            } else if (isAppCustomUpi) {
                val standardUpiUri = if (url.contains("?")) {
                    "upi://pay?" + url.substringAfter("?")
                } else {
                    url
                }
                val mappedPackage = when {
                    url.startsWith("phonepe://") -> "com.phonepe.app"
                    url.startsWith("gpay://") -> "com.google.android.apps.nbu.paisa.user"
                    url.startsWith("paytmmp://") -> "net.one97.paytm"
                    url.startsWith("bhim://") -> "in.org.npci.upiapp"
                    url.startsWith("credpay://") -> "com.dreamplug.androidapp"
                    url.startsWith("amazonpay://") -> "in.amazon.mShop.android.shopping"
                    url.startsWith("mobikwik://") -> "com.mobikwik_new"
                    url.startsWith("navi://") -> "com.navi.finance"
                    url.startsWith("jupiter://") -> "com.jupiter.money"
                    url.startsWith("fi://") -> "money.fi.app"
                    url.startsWith("slice://") -> "indwin.c3.shareapp"
                    url.startsWith("tataneu://") -> "com.tatadigital.tcp"
                    else -> null
                }

                val directIntent = Intent(Intent.ACTION_VIEW, Uri.parse(standardUpiUri))
                if (mappedPackage != null && isPackageInstalled(ctx, mappedPackage)) {
                    directIntent.setPackage(mappedPackage)
                }
                intent = directIntent
            } else {
                intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }

            if (intent != null) {
                val targetPackage = intent.`package`
                if (targetPackage != null && !isPackageInstalled(ctx, targetPackage)) {
                    intent.setPackage(null)
                }

                val packageManager = ctx.packageManager
                val resolveInfo = packageManager.resolveActivity(intent, 0)

                if (resolveInfo != null) {
                    launchUpiIntent(intent)
                    return true
                } else {
                    val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                    if (!fallbackUrl.isNullOrBlank()) {
                        view?.loadUrl(fallbackUrl)
                        return true
                    }

                    val dataUri = intent.data ?: Uri.parse(url)
                    val genericUpiIntent = Intent(Intent.ACTION_VIEW, dataUri)
                    if (packageManager.resolveActivity(genericUpiIntent, 0) != null) {
                        val chooser = Intent.createChooser(genericUpiIntent, "Pay with UPI")
                        launchUpiIntent(chooser)
                        return true
                    } else {
                        Toast.makeText(ctx, "No UPI payment app found on your phone", Toast.LENGTH_LONG).show()
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling UPI intent for $url: ${e.message}", e)
            Toast.makeText(ctx, "Could not open UPI app: ${e.message}", Toast.LENGTH_SHORT).show()
            return true
        }
    }

    // 3. Other external non-http schemes
    val uri = Uri.parse(url)
    val scheme = uri.scheme ?: ""
    if (scheme != "http" && scheme != "https") {
        return try {
            val externalIntent = Intent(Intent.ACTION_VIEW, uri)
            ctx.startActivity(externalIntent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not open external scheme $scheme: ${e.message}")
            false
        }
    }

    return false
}

private fun isPackageInstalled(context: Context, packageName: String): Boolean {
    return try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: Exception) {
        false
    }
}
