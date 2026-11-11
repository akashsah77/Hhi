package com.example.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

/**
 * Native Google authentication for the WebView.
 *
 * Primary path follows the working Business OS pattern:
 * native Google account chooser -> ID token -> WebView -> Supabase.
 * Browser fallback is intentionally disabled temporarily while the native flow
 * is being verified. The previous behavior is preserved in the backup branch.
 */
object GoogleAuthManager {
    private const val TAG = "GoogleAuthManager"
    private const val REQUEST_NATIVE_GOOGLE = 7102

    private data class PendingAuth(
        val activity: Activity,
        val serverClientId: String,
        val fallbackAuthUrl: String?,
        val onSuccess: (idToken: String) -> Unit,
        val onError: (message: String) -> Unit
    )

    private var pendingAuth: PendingAuth? = null

    /** Temporarily disabled. Kept as a no-op so old WebView calls remain safe. */
    fun openChromeCustomTab(context: Context, url: String) {
        Log.w(TAG, "Custom Chrome fallback temporarily disabled")
    }

    /** Native account chooser first. No browser is opened by this method. */
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

        val clientId = serverClientId.ifBlank { com.example.config.AppConfig.GOOGLE_WEB_CLIENT_ID }
        if (clientId.isBlank()) {
            onError("Google server client ID is missing")
            return
        }

        pendingAuth = PendingAuth(activity, clientId, fallbackAuthUrl, onSuccess, onError)
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .requestIdToken(clientId)
            .build()
        val client = GoogleSignIn.getClient(activity, options)

        // Clear the cached native session so the account chooser is visible.
        client.signOut().addOnCompleteListener {
            try {
                activity.startActivityForResult(client.signInIntent, REQUEST_NATIVE_GOOGLE)
            } catch (e: Exception) {
                    finishFailure("Could not launch native Google account chooser: ${e.message}")
            }
        }
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_NATIVE_GOOGLE) return false
        val auth = pendingAuth ?: return true
        if (resultCode != Activity.RESULT_OK) {
            finishFailure("Native Google account chooser was dismissed")
            return true
        }

        try {
            val account: GoogleSignInAccount =
                GoogleSignIn.getSignedInAccountFromIntent(data)
                    .getResult(ApiException::class.java)
            val token = account.idToken
            if (token.isNullOrBlank()) {
                finishFailure("Native Google returned no ID token")
            } else {
                pendingAuth = null
                Log.d(TAG, "Native Google token received for ${account.email}")
                auth.onSuccess(token)
            }
        } catch (e: ApiException) {
            finishFailure("Native Google failed with status ${e.statusCode}")
        } catch (e: Exception) {
            finishFailure("Native Google result could not be read: ${e.message}")
        }
        return true
    }

    private fun finishFailure(message: String) {
        val auth = pendingAuth ?: return
        pendingAuth = null
        Log.w(TAG, message)
        auth.onError(message)
    }
}
