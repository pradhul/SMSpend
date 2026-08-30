# R8 rules for release builds.
# Components declared in AndroidManifest.xml (MainActivity, SmSpendApp,
# UpiNotificationListener, SpendWidgetReceiver) are kept automatically by AGP.

# WorkManager's default factory instantiates workers by class name via reflection,
# so the periodic scan worker must keep its name and its two-argument constructor.
-keep class app.smspend.work.SmsScanWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Room loads its generated implementations reflectively as "<Name>_Impl".
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class app.smspend.data.**_Impl { <init>(...); }
-dontwarn androidx.room.paging.**

# Glance builds widget layouts through generated code referenced from XML metadata.
-keep class app.smspend.widget.** { *; }

# OkHttp / Okio ship their own consumer rules but reference optional TLS providers
# that are not on Android.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Keep our own crash-relevant line numbers readable in stack traces while still
# obfuscating names. mapping.txt in app/build/outputs/mapping/release/ deobfuscates.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
