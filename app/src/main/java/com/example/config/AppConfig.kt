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

    /** Android OAuth client registered for com.kissa.app + the release/debug SHA-1. */
    const val GOOGLE_ANDROID_CLIENT_ID: String =
        "1067121980936-cidkjm5kiue7l610t9tf1tl8nvs1vvlq.apps.googleusercontent.com"

    /**
     * Web OAuth client used as the ID-token audience.
     * Supabase Google provider must use this same Web client ID.
     */
    const val GOOGLE_WEB_CLIENT_ID: String =
        "1067121980936-7f8inodrvm5otg83nv3dvudq9kch2sju.apps.googleusercontent.com"
}
