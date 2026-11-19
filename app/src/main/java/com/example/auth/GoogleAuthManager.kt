package com.example.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.lifecycleScope
import com.example.config.AppConfig
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Native Google authentication for the Android WebView. */
object GoogleAuthManager {
    private const val TAG = "GoogleAuthManager"

    private data class PendingAuth(
        val activity: ComponentActivity,
        val serverClientId: String,
        val fallbackAuthUrl: String?,
        val onSuccess: (idToken: String) -> Unit,
        val onError: (message: String) -> Unit
    )

    // All state changes happen on the main thread because callers enter through
    // the Android WebView JavaScript bridge.
    private var pendingAuth: PendingAuth? = null

    /** Open only HTTPS URLs; use Chrome Custom Tabs, then the system browser. */
    fun openChromeCustomTab(context: Context, url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank()) {
            Log.w(TAG, "Refusing to open a non-HTTPS or invalid browser URL")
            return false
        }

        return try {
            val customTab = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(false)
                .build()
            if (context !is Activity) {
                customTab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            customTab.launchUrl(context, uri)
            true
        } catch (customTabError: Exception) {
            Log.w(TAG, "Chrome Custom Tab could not open; trying the default browser", customTabError)
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, uri)
                if (context !is Activity) browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(browserIntent)
                true
            } catch (browserError: Exception) {
                Log.e(TAG, "No browser could open the HTTPS OAuth URL", browserError)
                false
            }
        }
    }

    /** Credential Manager is primary; Supabase OAuth in Chrome is the fallback. */
    fun signInWithGoogle(
        activity: ComponentActivity,
        serverClientId: String,
        fallbackAuthUrl: String? = null,
        onSuccess: (idToken: String) -> Unit,
        onError: (message: String) -> Unit
    ) {
        activity.runOnUiThread {
            if (pendingAuth != null) {
                Log.w(TAG, "Google sign-in already in progress")
                return@runOnUiThread
            }

            val clientId = serverClientId.ifBlank { AppConfig.GOOGLE_WEB_CLIENT_ID }
            if (clientId.isBlank()) {
                onError("Google Web OAuth client ID is missing")
                return@runOnUiThread
            }

            val auth = PendingAuth(activity, clientId, fallbackAuthUrl, onSuccess, onError)
            pendingAuth = auth

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(clientId)
                .setAutoSelectEnabled(false)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            activity.lifecycleScope.launch {
                try {
                    val credentialManager = CredentialManager.create(activity)
                    // Credential Manager recommends a mutable activity context
                    // to handle activity recreation while its system UI is open.
                    val mutableActivityContext = MutableContextWrapper(activity)
                    val response = credentialManager.getCredential(
                        context = mutableActivityContext,
                        request = request
                    )
                    val credential = response.credential
                    if (credential !is CustomCredential ||
                        credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                    ) {
                        finishFailure("Credential Manager returned an unsupported Google credential")
                        return@launch
                    }

                    val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    if (idToken.isBlank()) {
                        finishFailure("Credential Manager returned an empty Google ID token")
                    } else {
                        finishSuccess(idToken)
                    }
                } catch (e: CancellationException) {
                    if (pendingAuth?.activity === activity) pendingAuth = null
                    throw e
                } catch (e: GetCredentialCancellationException) {
                    // Respect an explicit user cancellation; do not unexpectedly
                    // open another sign-in page after they dismissed the chooser.
                    finishFailure("Google sign-in was cancelled", allowBrowserFallback = false)
                } catch (e: GetCredentialException) {
                    Log.w(TAG, "Credential Manager failed; trying the OAuth browser fallback", e)
                    finishFailure("Credential Manager sign-in failed: ${e.message}")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not complete Credential Manager sign-in", e)
                    finishFailure("Google sign-in failed: ${e.message}")
                }
            }
        }
    }

    private fun finishSuccess(idToken: String) {
        val auth = pendingAuth ?: return
        pendingAuth = null
        auth.onSuccess(idToken)
    }

    private fun finishFailure(message: String, allowBrowserFallback: Boolean = true) {
        val auth = pendingAuth ?: return
        pendingAuth = null
        Log.w(TAG, message)

        val fallbackUrl = auth.fallbackAuthUrl
        if (allowBrowserFallback && !fallbackUrl.isNullOrBlank()) {
            if (openChromeCustomTab(auth.activity, fallbackUrl)) return
            auth.onError("$message. Chrome/browser could not open the OAuth fallback.")
            return
        }

        auth.onError(message)
    }
}
