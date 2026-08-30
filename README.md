# SMSpend

Private Android app that turns bank/UPI debit SMS into a **this-week spend by category** view.

## Stack

- Kotlin · Jetpack Compose · Room · DataStore · OkHttp (Gemini)

## Run

1. Open this folder in **Android Studio** (Ladybug/Koala+)
2. Sync Gradle → Run on a **real phone** (SMS inbox; emulator has few bank texts)

CLI:

```bash
export JAVA_HOME="/path/to/jdk-17"   # need JDK 17 (not Studio's JBR 25)
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:installDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

## First launch

1. Grant **SMS** permission  
2. Paste your **Gemini API key** (optional but needed for unknown merchants)  
3. App scans from the **1st of this month**, then rescans incrementally each open / pull-to-refresh  

### Getting a Gemini API key

1. Open <https://aistudio.google.com/app/apikey> and sign in with a Google account
2. Tap **Create API key** — the free tier is enough for personal use
3. Copy the key and paste it into **Settings → Gemini API key** (or during onboarding)

The key is stored in app-private DataStore on the phone and is only ever sent to
Google's Gemini endpoint. The same steps are shown in-app with a tappable link.

## Release build

Signing credentials are read from an untracked `keystore.properties` at the repo root,
or from environment variables (`SMSPEND_KEYSTORE_FILE`, `SMSPEND_KEYSTORE_PASSWORD`,
`SMSPEND_KEY_ALIAS`, `SMSPEND_KEY_PASSWORD`). Without either, `assembleRelease` still
builds but leaves the APK unsigned.

```bash
# once — keep this file safe, updates must reuse the same key
keytool -genkey -v -keystore smspend-release.jks -alias smspend \
  -keyalg RSA -keysize 2048 -validity 10000

cp keystore.properties.example keystore.properties   # then fill it in
./gradlew :app:assembleRelease                       # APK
./gradlew :app:bundleRelease                          # AAB, for Play
```

Release builds run **R8** (minify + resource shrinking). Keep
`app/build/outputs/mapping/release/mapping.txt` for every shipped build — it is what
turns an obfuscated crash stack trace back into real names.

After R8 changes, smoke-test the release APK on a device: launch, pull-to-refresh a
scan, let the background worker run, open Trends and Exports, and check the widget.

### What is intentionally off

- `allowBackup=false` plus `data_extraction_rules.xml` exclude everything from cloud
  backup and phone-to-phone transfer (the DB holds parsed SMS; DataStore holds the key)
- `usesCleartextTraffic=false` — the only network call is HTTPS to Gemini

### Distribution note

`READ_SMS` is a restricted permission on Google Play: listing an app that reads SMS
requires a Permissions Declaration and an approved core use case, and expense tracking
is generally **not** approved. Sideloading the signed APK (see `scripts/serve-apk.sh`)
or a closed track/internal distribution avoids that review.

## Design

See `design/DESIGN.md` and Stitch exports in `design/stitch/screens/`.
