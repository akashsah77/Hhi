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
     * Native Jetpack Credential Manager 2.0 Google Sign-In.
     * Displays native 1-tap Google bottom sheet on Android.
     * If native fails or webClientId is pending, falls back smoothly to Chrome Custom Tabs.
     */
    fun signInWithGoogle(
        activity: Activity,
        serverClientId: String,
        fallbackAuthUrl: String? = null,
        onSuccess: (idToken: String) -> Unit,
        onError: (message: String) -> Unit
    ) {
        val effectiveClientId = if (serverClientId.isNotBlank()) {
            serverClientId
        } else {
            com.example.config.AppConfig.GOOGLE_WEB_CLIENT_ID
        }

        if (effectiveClientId.isBlank()) {
            // If client id not yet configured, immediately fallback to Chrome Custom Tab
            fallbackAuthUrl?.let { openChromeCustomTab(activity, it) }
            onError("Google Server Client ID is not configured yet. Fallback to Custom Tab.")
            return
        }

        val credentialManager = CredentialManager.create(activity)

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(effectiveClientId)
            .setAutoSelectEnabled(true)
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
                        fallbackAuthUrl?.let { openChromeCustomTab(activity, it) }
                    }
                }
            } catch (e: GetCredentialCancellationException) {
                withContext(Dispatchers.Main) {
                    Log.d(TAG, "Google Sign-In was cancelled by user")
                }
            } catch (e: GetCredentialException) {
                Log.e(TAG, "CredentialManager error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Google Sign-in failed")
                    fallbackAuthUrl?.let { openChromeCustomTab(activity, it) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected sign-in error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Authentication failed")
                    fallbackAuthUrl?.let { openChromeCustomTab(activity, it) }
                }
            }
        }
    }
}
