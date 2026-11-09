package com.example.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.google.android.gms.auth.api.identity.BeginSignInRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.SignInClient
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google authentication pipeline used by the Android WebView.
 *
 * Order is intentionally the same as the proven Skyverse flow:
 * 1) One Tap native bottom sheet
 * 2) Legacy Google account picker
 * 3) Chrome Custom Tab with the original OAuth URL
 */
object GoogleAuthManager {
    private const val TAG = "GoogleAuthManager"
    private const val REQUEST_ONE_TAP = 7101
    private const val REQUEST_LEGACY_PICKER = 7102

    private data class PendingAuth(
        val activity: Activity,
        val webClientId: String,
        val fallbackAuthUrl: String?,
        val onSuccess: (idToken: String) -> Unit,
        val onError: (message: String) -> Unit
    )

    private var pendingAuth: PendingAuth? = null
    private var oneTapClient: SignInClient? = null
    private var resolvingClientId = false

    /** Launch the OAuth URL in Chrome Custom Tab without changing the WebView's PKCE state. */
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
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            } catch (fallbackError: Exception) {
                Log.e(TAG, "Fallback browser failed: ${fallbackError.message}", fallbackError)
            }
        }
    }

    /**
     * Starts native One Tap first. The client ID is read from the Supabase
     * Google OAuth URL because this is the Web/server audience required for the
     * ID token. The Android client ID remains tied to package + signing SHA.
     */
    fun signInWithGoogle(
        activity: Activity,
        serverClientId: String,
        fallbackAuthUrl: String? = null,
        onSuccess: (idToken: String) -> Unit,
        onError: (message: String) -> Unit
    ) {
        if (pendingAuth != null) {
            Log.w(TAG, "Google sign-in already in progress")
            return
        }

        if (resolvingClientId) {
            Log.w(TAG, "Google client ID resolution already in progress")
            return
        }

        val directClientId = fallbackAuthUrl?.let { authUrl ->
            runCatching { Uri.parse(authUrl).getQueryParameter("client_id") }.getOrNull()
        }?.takeIf { it.isNotBlank() }
            ?: serverClientId.takeIf { it.isNotBlank() }
            ?: com.example.config.AppConfig.GOOGLE_WEB_CLIENT_ID.takeIf { it.isNotBlank() }

        if (directClientId != null) {
            startNativePipeline(activity, directClientId, fallbackAuthUrl, onSuccess, onError)
            return
        }

        // Supabase's first /auth/v1/authorize URL usually has no client_id yet;
        // it is added by the Google redirect. Resolve that redirect without
        // opening a browser so native One Tap still gets the first opportunity.
        val authUrl = fallbackAuthUrl
        if (authUrl.isNullOrBlank()) {
            onError("Google OAuth URL is missing")
            return
        }

        resolvingClientId = true
        Thread {
            val resolvedClientId = resolveGoogleClientId(authUrl)
            activity.runOnUiThread {
                resolvingClientId = false
                if (!resolvedClientId.isNullOrBlank()) {
                    startNativePipeline(activity, resolvedClientId, fallbackAuthUrl, onSuccess, onError)
                } else {
                    // No browser is opened before the native pipeline. If the
                    // provider cannot expose its audience, use the existing
                    // WebView/Custom Tab callback as the final safe fallback.
                    Log.w(TAG, "Could not resolve Google Web client ID from OAuth redirect")
                    onError("Google Web client ID could not be resolved")
                    openChromeCustomTab(activity, authUrl)
                }
            }
        }.start()
    }

    private fun startNativePipeline(
        activity: Activity,
        webClientId: String,
        fallbackAuthUrl: String?,
        onSuccess: (idToken: String) -> Unit,
        onError: (message: String) -> Unit
    ) {
        pendingAuth = PendingAuth(activity, webClientId, fallbackAuthUrl, onSuccess, onError)
        oneTapClient = Identity.getSignInClient(activity)

        val request = BeginSignInRequest.builder()
            .setGoogleIdTokenRequestOptions(
                BeginSignInRequest.GoogleIdTokenRequestOptions.builder()
                    .setSupported(true)
                    .setServerClientId(webClientId)
                    .setFilterByAuthorizedAccounts(false)
                    .build()
            )
            // Explicitly false: show the premium account chooser bottom sheet.
            .setAutoSelectEnabled(false)
            .build()

        // Match Skyverse: clear a previous One Tap session so the chooser is
        // shown instead of silently selecting an old account.
        runCatching { oneTapClient?.signOut() }

        oneTapClient?.beginSignIn(request)
            ?.addOnSuccessListener { result ->
                try {
                    activity.startIntentSenderForResult(
                        result.pendingIntent.intentSender,
                        REQUEST_ONE_TAP,
                        null,
                        0,
                        0,
                        0
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Could not launch One Tap bottom sheet", e)
                    launchLegacyPicker()
                }
            }
            ?.addOnFailureListener { error ->
                Log.w(TAG, "One Tap bottom sheet failed: ${error.message}")
                launchLegacyPicker()
            }
            ?: launchLegacyPicker()
    }

    private fun resolveGoogleClientId(authUrl: String): String? {
        var currentUrl = authUrl
        repeat(4) {
            val direct = runCatching {
                Uri.parse(currentUrl).getQueryParameter("client_id")
            }.getOrNull()
            if (!direct.isNullOrBlank()) return direct

            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                useCaches = false
                setRequestProperty("User-Agent", "Mozilla/5.0 Android KissaAI")
            }
            try {
                connection.connect()
                val location = connection.getHeaderField("Location") ?: return null
                currentUrl = URL(URL(currentUrl), location).toString()
            } finally {
                connection.disconnect()
            }
        }
        return runCatching { Uri.parse(currentUrl).getQueryParameter("client_id") }.getOrNull()
    }

    /** Called by MainActivity for the One Tap and legacy picker activity results. */
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        val auth = pendingAuth ?: return false

        when (requestCode) {
            REQUEST_ONE_TAP -> {
                if (resultCode == Activity.RESULT_OK) {
                    try {
                        val credential = oneTapClient?.getSignInCredentialFromIntent(data)
                        val idToken = credential?.googleIdToken
                        if (!idToken.isNullOrBlank()) {
                            finishSuccess(idToken)
                        } else {
                            launchLegacyPicker()
                        }
                    } catch (e: ApiException) {
                        Log.w(TAG, "One Tap result failed: ${e.statusCode}")
                        launchLegacyPicker()
                    } catch (e: Exception) {
                        Log.w(TAG, "One Tap credential parsing failed", e)
                        launchLegacyPicker()
                    }
                } else {
                    Log.w(TAG, "One Tap dismissed; opening legacy account picker")
                    launchLegacyPicker()
                }
                return true
            }

            REQUEST_LEGACY_PICKER -> {
                if (resultCode == Activity.RESULT_OK) {
                    try {
                        val task: Task<GoogleSignInAccount> =
                            GoogleSignIn.getSignedInAccountFromIntent(data)
                        val account = task.getResult(ApiException::class.java)
                        val idToken = account.idToken
                        if (!idToken.isNullOrBlank()) finishSuccess(idToken)
                        else finishFailure("Legacy Google picker returned no ID token")
                    } catch (e: ApiException) {
                        Log.w(TAG, "Legacy Google picker failed: ${e.statusCode}")
                        finishFailure("Legacy Google picker failed: ${e.statusCode}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Legacy Google result parsing failed", e)
                        finishFailure(e.message ?: "Legacy Google picker failed")
                    }
                } else {
                    finishFailure("Legacy Google picker was dismissed")
                }
                return true
            }
        }
        return false
    }

    private fun launchLegacyPicker() {
        val auth = pendingAuth ?: return
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .requestIdToken(auth.webClientId)
            .build()
        val client = GoogleSignIn.getClient(auth.activity, options)
        runCatching {
            client.signOut().addOnCompleteListener {
                try {
                    auth.activity.startActivityForResult(client.signInIntent, REQUEST_LEGACY_PICKER)
                } catch (e: Exception) {
                    finishFailure("Could not launch legacy Google picker: ${e.message}")
                }
            }
        }.onFailure {
            Log.w(TAG, "Legacy sign-out failed; launching picker anyway", it)
            try {
                auth.activity.startActivityForResult(client.signInIntent, REQUEST_LEGACY_PICKER)
            } catch (e: Exception) {
                finishFailure("Could not launch legacy Google picker: ${e.message}")
            }
        }
    }

    private fun finishSuccess(idToken: String) {
        val auth = pendingAuth ?: return
        pendingAuth = null
        oneTapClient = null
        auth.onSuccess(idToken)
    }

    private fun finishFailure(message: String) {
        val auth = pendingAuth ?: return
        pendingAuth = null
        oneTapClient = null
        auth.onError(message)
        auth.fallbackAuthUrl?.let { openChromeCustomTab(auth.activity, it) }
    }

}
