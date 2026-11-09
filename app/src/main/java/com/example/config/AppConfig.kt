package com.example.config

/**
 * Kissa AI Configuration
 * 
 * To change to a custom domain in the future (e.g., https://kissaai.com or https://www.kissaai.in):
 * Simply change [BASE_URL] below to your new domain!
 */
object AppConfig {
    /**
     * The primary URL loaded by Kissa AI.
     * Future custom domain can be changed here.
     */
    const val BASE_URL: String = "https://kissaai.pages.dev/"

    /**
     * Application title
     */
    const val APP_NAME: String = "Kissa AI"

    /**
     * Optional custom User-Agent suffix for web server identification
     */
    const val USER_AGENT_SUFFIX: String = " KissaAI-Android"

    /**
     * Allowed hostnames to stay inside the app WebView.
     * If you change to a custom domain, add your domain here.
     */
    val ALLOWED_HOSTS: List<String> = listOf(
        "kissaai.pages.dev",
        "pages.dev"
    )

    /**
     * Google Web Client ID for native Jetpack Credential Manager 2.0.
     * If empty, the app will automatically extract client_id from the website's Google Sign-In request.
     */
    var GOOGLE_WEB_CLIENT_ID: String = ""
}
