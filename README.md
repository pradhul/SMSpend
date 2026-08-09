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

## Design

See `design/DESIGN.md` and Stitch exports in `design/stitch/screens/`.
