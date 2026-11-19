# Kissa AI — Android app

The Android application in this repository loads the Kissa web platform at `https://kissaai.pages.dev/`. The web source is in [`akashsah77/story-weaver-ai`](https://github.com/akashsah77/story-weaver-ai); the deployed site currently uses Kissa branding even though the repository is named Story Weaver AI.

## Android/web sign-in connection

The Android app keeps the web session and uses this sign-in order:

1. AndroidX Credential Manager requests a Google ID token using the **Web OAuth client ID**.
2. The web page passes that token to Supabase through `signInWithIdToken`.
3. If Credential Manager fails, the app opens the prepared Supabase OAuth URL in Chrome Custom Tabs. Google returns to `https://kissaai.pages.dev/auth/callback`, which is associated with the Android app by the App Links declaration and `public/.well-known/assetlinks.json` in the web repository.

The Web client ID used as the Credential Manager server/client audience and the Web OAuth client configured for the Supabase Google provider must belong to the same Google Cloud project. Never put a Google client secret in the Android app.

## Google Cloud and Supabase checklist

- In Google Cloud, configure the **Android OAuth client** with package name `com.kissa.app` and the SHA-1 fingerprint for every installed signing certificate: debug, direct-release/upload as applicable, and the **Play app-signing certificate** for Play-distributed installs.
- Configure the **Web OAuth client** in that same Google Cloud project. Its client ID must match `GOOGLE_WEB_CLIENT_ID` in `AppConfig.kt` and the client ID used by Supabase's Google provider.
- In Google Cloud, add the exact Supabase provider callback `https://<your-supabase-project-ref>.supabase.co/auth/v1/callback` to the Web OAuth client's authorized redirect URIs.
- In Supabase Auth, enable Google with that same Web client ID and its client secret. Allow `https://kissaai.pages.dev/auth/callback` as an application redirect URL.
- In the Story Weaver web repository, update `public/.well-known/assetlinks.json` with the SHA-256 fingerprint of the certificate that signs the installed app. For Play distribution, use the Play **app-signing** certificate fingerprint, not only the upload-key fingerprint.
- To find the actual Play signing fingerprints, open Play Console → **Release → Setup → App integrity**. For a locally signed APK, use `./gradlew signingReport` or `keytool -printcert -jarfile <app.apk>`.

Google Cloud OAuth credentials cannot be validated from this repository alone. If the settings above already match and native sign-in still fails, capture the redacted Android error/status from the app and check the Google Cloud OAuth client and Supabase provider configuration against the package and certificate actually used to install that APK.

## Release signing security

A release keystore and its password were committed to this **public** repository. Treat that key and password as compromised; do not reuse them for a production release. A normal cleanup commit does not remove them from Git history. Rotate the upload key in Play Console if the app is already enrolled in Play App Signing. If the app is distributed directly, plan key rotation carefully because Android does not allow an installed app to update to a differently signed package.

Store the replacement signing material only in GitHub Actions secrets. Configure these repository secrets before triggering a signed release:

- `ANDROID_KEYSTORE_BASE64` — base64-encoded replacement keystore
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_PASSWORD`
- `ANDROID_KEY_ALIAS`

The workflow decodes the keystore into the runner's temporary directory, signs the release APK/AAB, and does not check the keystore into the repository. Pull requests run tests and build a debug APK without access to release-signing secrets.

The exposed key/password also remain in prior public Git commits until the repository history is rewritten. After rotating the key, arrange a coordinated history purge and force-push; this can affect other clones and contributors, so it is deliberately not performed automatically.

## Automatic app versions

GitHub Actions supplies its monotonically increasing workflow run number to Gradle. Release artifacts use `versionName` `1.0.0.<run-number>` and a correspondingly increasing `versionCode`; the APK and AAB from one run share the same version. The local default is `1.0.0.2`; for a local build, set the number explicitly, for example `./gradlew assembleDebug -PappBuildNumber=3`.
