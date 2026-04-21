# DocScanner Android

Android document scanner app that uses the phone camera to scan multiple pages, automatically crops and enhances them, and saves the result as a PDF to a configurable OneDrive folder.

## Features

- **Multi-page scanning** — continuous capture with ML Kit Document Scanner (no extra taps per page)
- **Auto-crop** — document edge detection built into ML Kit
- **Image enhancement** — lighting and contrast correction for readable PDFs
- **Best-effort OCR** — text recognition via ML Kit
- **OneDrive upload** — authenticated via Microsoft MSAL, configurable target folder
- **Review screen** — thumbnail preview with per-page delete before upload
- **Custom filename** — set the PDF name before uploading

## Tech Stack

| Component | Library |
|-----------|---------|
| Camera / Scanning | ML Kit Document Scanner (`play-services-mlkit-document-scanner`) |
| OCR | ML Kit Text Recognition |
| Image enhancement | Android Bitmap + Canvas (adaptive thresholding) |
| PDF generation | Android `PdfDocument` API |
| Authentication | MSAL for Android 5.x |
| OneDrive upload | Microsoft Graph API via OkHttp |
| DI | Hilt |
| Architecture | MVVM + Repository + UseCase |
| Image loading | Coil |

## Requirements

- Android 8.0+ (API 26+), optimized for Android 16 / OneUI 8.0
- Google Play Services (required for ML Kit)
- Non-rooted device
- Microsoft personal account or Entra ID (work/school) account with OneDrive

## Setup

### 1. Clone and configure

```bash
git clone <repo-url>
cd doc-scanner-android
cp local.properties.template local.properties
```

Edit `local.properties`:
```
sdk.dir=C:\\path\\to\\Android\\Sdk
msal.clientId=YOUR_AZURE_CLIENT_ID
```

### 2. Azure AD App Registration

1. Go to [portal.azure.com](https://portal.azure.com) → **App registrations** → **New registration**
2. Name: `DocScanner` (or any name)
3. Supported account types: **Personal Microsoft accounts** (for OneDrive personal)
4. After creation, note the **Application (client) ID**
5. Go to **Authentication** → **Add a platform** → **Android**
   - Package name: `com.nabla.docscan`
   - Signature hash: your keystore signature hash (see below)
6. Under **API permissions**, ensure:
   - `Files.ReadWrite` (Microsoft Graph, Delegated)
   - `User.Read` (Microsoft Graph, Delegated)
   - `offline_access` (Microsoft Graph, Delegated)

### 3. Get your signature hash

**Debug builds:**
```bash
keytool -exportcert -alias androiddebugkey \
  -keystore ~/.android/debug.keystore \
  -storepass android | openssl sha1 -binary | openssl base64
```

**Release builds:** use your release keystore instead.

Update `app/src/main/res/raw/msal_config.json` with your client ID and signature hash.

### 4. Build

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Or open in Android Studio and run directly.

## Usage

1. Open the app → ML Kit scanner launches automatically
2. Scan pages (camera shutter only — no extra taps)
3. Tap **Finished** in the scanner
4. Back in the main screen:
   - Set the PDF filename (default: `YYYY-MM-DD_scan`)
   - Tap **+ Add Pages** to add more pages to the same document
   - Tap **Review** to preview thumbnails and delete individual pages
   - Tap **Finished** → PDF is generated and uploaded to OneDrive
5. After upload, the UI resets for the next scan

## OneDrive Folder Configuration

Go to **Settings** (gear icon) to configure the target OneDrive folder. Default is the OneDrive root.

---

## Security Notes

### Client ID in source

The Azure AD **client ID** (`msal_config.json`) is committed to the repository. This is a known tradeoff:

- The client ID alone is **not a secret** — it only identifies the app registration
- An attacker could extract it from the APK and initiate OAuth flows using your client ID
- However, they **cannot complete authentication** unless the redirect URI matches — and the redirect URI includes your APK's **signature hash**, which is tied to your keystore

**For Play Store distribution:**
- Sign the release APK with your own keystore
- Register the release redirect URI in Azure AD with the hash from your release keystore:
  `msauth://com.nabla.docscan/<your-release-hash>`
- This ensures only your signed APK can complete the OAuth flow, even if someone copies your client ID

### Threat model (MVP / personal use)

This app is designed for personal use with a single Microsoft account. The following known limitations apply:

| Issue | Impact | Status |
|-------|--------|--------|
| SINGLE account mode | Only one MS account per device | Acceptable for personal use |
| Files.ReadWrite scope | Full OneDrive access (needed for folder selection) | Acceptable |
| No broker integration | No Conditional Access / Intune support | Out of scope for MVP |
| PDFs not auto-deleted post-upload | Files accumulate in cache | Out of scope for MVP |

For **enterprise / multi-user** deployment, the following changes are required:
- Switch to `MULTIPLE` account mode in `msal_config.json`
- Change audience to `AzureADMyOrg` with your tenant ID
- Implement per-user file isolation
- Enable broker integration (Microsoft Authenticator)

### Permissions

| Permission | Why |
|------------|-----|
| `CAMERA` | Document scanning |
| `INTERNET` | OneDrive upload via Graph API |
| `READ_MEDIA_IMAGES` (API 33+) | Access scanned images |

No `READ_EXTERNAL_STORAGE`, no `WRITE_EXTERNAL_STORAGE` for modern Android versions.

---

## Project Structure

```
app/src/main/
├── kotlin/com/docscanner/app/
│   ├── di/                    # Hilt modules
│   ├── model/                 # Data models (ScanPage, ScanSession, etc.)
│   ├── repository/            # OneDriveRepository, PreferencesRepository
│   ├── usecase/               # EnhanceImageUseCase, OcrUseCase, GeneratePdfUseCase
│   ├── viewmodel/             # ScanViewModel, SettingsViewModel
│   └── ui/
│       ├── scan/              # ScanActivity (main screen)
│       ├── review/            # ReviewActivity (thumbnail preview)
│       └── settings/          # SettingsActivity
├── res/
│   ├── layout/                # XML layouts
│   ├── raw/msal_config.json   # MSAL configuration
│   └── xml/file_paths.xml     # FileProvider paths
└── AndroidManifest.xml
```

## License

Private / personal use.
