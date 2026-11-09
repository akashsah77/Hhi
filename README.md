# Kissa AI — Android Application

Official Android Application for **Kissa AI** (`https://kissaai.pages.dev/`).

---

## 📌 Application Specifications

- **App Name:** Kissa AI
- **Application ID / Package Name:** `com.kissa.app`
- **Default Web Platform URL:** `https://kissaai.pages.dev/`

---

## 🔐 Production Signing Keystore Details (Valid until year 2226)

This repository includes the generated 100-200 year production keystore file (`my-upload-key.jks`) for automatic builds and Google Play signing.

- **Keystore File:** `my-upload-key.jks`
- **Key Alias:** `upload`
- **Keystore Password:** `kissaai2026`
- **Key Password:** `kissaai2026`
- **Validity:** `73,050 days` (Valid from November 5, 2026 until **November 7, 2226**)
- **Algorithm:** 2048-bit RSA with SHA384withRSA

### 🔑 Production Fingerprints (Google Play / Firebase / Google Sign-In)

- **SHA-1 Fingerprint:**
  ```text
  12:01:F5:1E:1A:BC:A1:BF:F2:C2:8C:88:E5:70:4A:2A:C0:67:42:86
  ```

- **SHA-256 Fingerprint:**
  ```text
  CD:83:25:A8:FD:9B:B2:33:77:13:C3:88:DF:A8:43:42:8B:E2:0D:BD:B6:09:04:29:76:FA:13:41:1E:D2:57:F7
  ```

---

## 🛠️ Debug Keystore Fingerprints (For Local Development)

- **Keystore File:** `debug.keystore`
- **Key Alias:** `androiddebugkey`
- **Password:** `android`
- **SHA-1 Fingerprint:**
  ```text
  51:31:32:50:FA:B0:E3:85:26:85:28:75:39:BF:DF:5B:A8:5B:FF:AC
  ```
- **SHA-256 Fingerprint:**
  ```text
  47:9B:D1:A5:E2:0C:C1:42:D9:A5:E8:B2:CB:2A:94:DF:6D:9F:C2:2A:37:F1:AE:2B:1A:30:4B:93:AD:0C:83:FE
  ```

---

## 🌐 Future Custom Domain Change (Future me URL kaise badle)

Agar future me aap custom domain (e.g., `https://kissaai.com`) buy karte hain:

1. Open file: `app/src/main/java/com/example/config/AppConfig.kt`
2. Change `BASE_URL`:
   ```kotlin
   const val BASE_URL: String = "https://your-custom-domain.com/"
   ```
3. Save the file and rebuild. The app will immediately point to your new custom domain!

---

## 🚀 Automatic GitHub Actions Build (CI/CD)

The GitHub workflow is pre-configured in `.github/workflows/build.yml`.
When you push to GitHub or trigger the workflow:
1. It automatically builds the signed **Release APK** (`Kissa-AI-Release-APK`).
2. It automatically builds the **Release AAB** (`Kissa-AI-Release-AAB`) for Google Play Store.
3. No secrets setup required; it runs automatically out of the box!
