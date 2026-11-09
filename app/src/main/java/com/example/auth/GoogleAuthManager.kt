package com.example.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object GoogleAuthManager {
    private const val TAG = "GoogleAuthManager"

    /**
     * Launch Google OAuth or any login URL inside Chrome Custom Tabs.
     * This provides the seamless official Google login without 403 disallowed_useragent errors.
     */
    fun openChromeCustomTab(context: Context, url: String) {
        try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(false)
                .build()
            customTabsIntent.intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            customTabsIntent.launchUrl(context, Uri.parse(url))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Chrome Custom Tab: ${e.message}", e)
            // Fallback to default external browser
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(browserIntent)
            } catch (fallbackError: Exception) {
                Log.e(TAG, "Fallback browser failed: ${fallbackError.message}")
            }
        }
    }

    /**
     * Native Jetpack Credential Manager Google Sign-In.
     * Always requests the official Google account chooser bottom sheet first.
     * If the native request cannot complete, falls back to Chrome Custom Tabs.
     */
    fun signInWithGoogle(
        activity: Activity,
        serverClientId: String,
        fallbackAuthUrl: String? = null,
        onSuccess: (idToken: String) -> Unit,
        onError: (message: String) -> Unit
    ) {
        // Credential Manager's serverClientId is the OAuth audience for the ID
        // token. It must be the Web/server client ID, not the Android package
        // client ID. Supabase includes the correct audience in this OAuth URL.
        val urlClientId = fallbackAuthUrl?.let { authUrl ->
            runCatching { Uri.parse(authUrl).getQueryParameter("client_id") }.getOrNull()
        }
        val effectiveClientId = urlClientId?.takeIf { it.isNotBlank() }
            ?: serverClientId.takeIf { it.isNotBlank() }
            ?: com.example.config.AppConfig.GOOGLE_WEB_CLIENT_ID

        fun openFallback() {
            fallbackAuthUrl?.let { openChromeCustomTab(activity, it) }
        }

        if (effectiveClientId.isBlank()) {
            // If client id not yet configured, immediately fallback to Chrome Custom Tab
            openFallback()
            onError("Google Server Client ID is not configured yet. Fallback to Custom Tab.")
            return
        }

        val credentialManager = CredentialManager.create(activity)

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(effectiveClientId)
            // Keep this false so the native account chooser is actually shown,
            // matching the premium bottom-sheet UX instead of silently signing
            // in with one previously authorized account.
            .setAutoSelectEnabled(false)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = credentialManager.getCredential(
                    request = request,
                    context = activity
                )
                val credential = result.credential
                if (credential is GoogleIdTokenCredential) {
                    val idToken = credential.idToken
                    withContext(Dispatchers.Main) {
                        onSuccess(idToken)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onError("Unsupported credential type received.")
                        openFallback()
                    }
                }
            } catch (e: GetCredentialCancellationException) {
                withContext(Dispatchers.Main) {
                    Log.d(TAG, "Native Google chooser was dismissed; opening Custom Tab fallback")
                    onError("Google chooser was dismissed")
                    openFallback()
                }
            } catch (e: GetCredentialException) {
                Log.e(TAG, "CredentialManager error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Google Sign-in failed")
                    openFallback()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected sign-in error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Authentication failed")
                    openFallback()
                }
            }
        }
    }
}
