package com.example.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.identity.BeginSignInRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.SignInClient
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

/**
 * Native Google authentication for the WebView.
 *
 * Order matches Skyverse:
 * 1) Google One Tap premium account chooser bottom sheet
 * 2) Legacy Google account picker
 * 3) No browser fallback for now; Custom Chrome is intentionally disabled.
 */
object GoogleAuthManager {
    private const val TAG = "GoogleAuthManager"
    private const val REQUEST_ONE_TAP = 7101
    private const val REQUEST_LEGACY_GOOGLE = 7102

    private data class PendingAuth(
        val activity: Activity,
        val serverClientId: String,
        val fallbackAuthUrl: String?,
        val onSuccess: (idToken: String) -> Unit,
        val onError: (message: String) -> Unit
    )

    private var pendingAuth: PendingAuth? = null
    private var oneTapClient: SignInClient? = null

    /** Kept as a no-op while Custom Chrome is disabled for native-flow testing. */
    fun openChromeCustomTab(context: Context, url: String) {
        Log.w(TAG, "Custom Chrome fallback is temporarily disabled")
    }

    /** Starts the premium One Tap bottom sheet first. */
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
        oneTapClient = Identity.getSignInClient(activity)

        val request = BeginSignInRequest.builder()
            .setGoogleIdTokenRequestOptions(
                BeginSignInRequest.GoogleIdTokenRequestOptions.builder()
                    .setSupported(true)
                    .setServerClientId(clientId)
                    .setFilterByAuthorizedAccounts(false)
                    .build()
            )
            .setAutoSelectEnabled(false)
            .build()

        // Clear One Tap state so the account chooser sheet is visible, then launch it.
        runCatching {
            oneTapClient?.signOut()?.addOnCompleteListener { beginOneTap(request) }
                ?: beginOneTap(request)
        }.onFailure {
            Log.w(TAG, "One Tap signOut failed; launching chooser anyway", it)
            beginOneTap(request)
        }
    }

    private fun beginOneTap(request: BeginSignInRequest) {
        val auth = pendingAuth ?: return
        oneTapClient?.beginSignIn(request)
            ?.addOnSuccessListener { result ->
                try {
                    auth.activity.startIntentSenderForResult(
                        result.pendingIntent.intentSender,
                        REQUEST_ONE_TAP,
                        null,
                        0,
                        0,
                        0
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Premium One Tap sheet could not launch", e)
                    launchLegacyPicker()
                }
            }
            ?.addOnFailureListener { error ->
                Log.w(TAG, "Premium One Tap unavailable: ${error.message}")
                launchLegacyPicker()
            }
            ?: launchLegacyPicker()
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_ONE_TAP && requestCode != REQUEST_LEGACY_GOOGLE) return false
        val auth = pendingAuth ?: return true

        if (requestCode == REQUEST_ONE_TAP) {
            if (resultCode == Activity.RESULT_OK) {
                try {
                    val credential = oneTapClient?.getSignInCredentialFromIntent(data)
                    val token = credential?.googleIdToken
                    if (!token.isNullOrBlank()) {
                        Log.d(TAG, "Premium One Tap token received")
                        finishSuccess(token)
                    } else {
                        Log.w(TAG, "Premium One Tap returned no ID token")
                        launchLegacyPicker()
                    }
                } catch (e: ApiException) {
                    Log.w(TAG, "Premium One Tap result failed: ${e.statusCode}")
                    launchLegacyPicker()
                } catch (e: Exception) {
                    Log.w(TAG, "Premium One Tap credential parsing failed", e)
                    launchLegacyPicker()
                }
            } else {
                Log.d(TAG, "Premium One Tap dismissed; opening legacy picker")
                launchLegacyPicker()
            }
            return true
        }

        if (resultCode != Activity.RESULT_OK) {
            finishFailure("Legacy Google account picker was dismissed")
            return true
        }

        try {
            val account: GoogleSignInAccount =
                GoogleSignIn.getSignedInAccountFromIntent(data)
                    .getResult(ApiException::class.java)
            val token = account.idToken
            if (token.isNullOrBlank()) {
                finishFailure("Legacy Google returned no ID token")
            } else {
                Log.d(TAG, "Legacy Google token received for ${account.email}")
                finishSuccess(token)
            }
        } catch (e: ApiException) {
            finishFailure("Legacy Google failed with status ${e.statusCode}")
        } catch (e: Exception) {
            finishFailure("Legacy Google result could not be read: ${e.message}")
        }
        return true
    }

    private fun launchLegacyPicker() {
        val auth = pendingAuth ?: return
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .requestIdToken(auth.serverClientId)
            .build()
        val client = GoogleSignIn.getClient(auth.activity, options)
        runCatching {
            client.signOut().addOnCompleteListener {
                try {
                    auth.activity.startActivityForResult(client.signInIntent, REQUEST_LEGACY_GOOGLE)
                } catch (e: Exception) {
                    finishFailure("Could not launch legacy Google picker: ${e.message}")
                }
            }
        }.onFailure {
            Log.w(TAG, "Legacy signOut failed; launching picker anyway", it)
            try {
                auth.activity.startActivityForResult(client.signInIntent, REQUEST_LEGACY_GOOGLE)
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
        Log.w(TAG, message)
        auth.onError(message)
    }
}
