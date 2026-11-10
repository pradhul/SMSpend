# SMSpend R8 rules.
#
# Most entry points (Activity, receivers, services, WorkManager workers, Glance
# receiver) are referenced from the manifest or by AndroidX's own consumer rules,
# so R8 keeps them automatically. The rules below cover what R8 can't see.

# Keep line numbers for readable crash stacks; hide the original file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# WorkManager instantiates workers reflectively by class name (stored in its DB),
# so the name must survive across releases.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Room: generated _Impl classes are created reflectively from the @Database class.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class app.smspend.data.**_Impl { *; }

# Glance widget receiver + widget are instantiated by the launcher / Glance by name.
-keep class app.smspend.widget.SpendWidgetReceiver { <init>(); }
-keep class app.smspend.widget.SpendWidget { <init>(); }

# Receivers / services declared in the manifest (explicit, belt and braces).
-keep class app.smspend.receive.SmsReceivedReceiver { <init>(); }
-keep class app.smspend.notify.UpiNotificationListener { <init>(); }

# Gemini requests/responses are built with org.json (platform), so there are
# no reflective serialization models to keep. OkHttp ships its own rules.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
