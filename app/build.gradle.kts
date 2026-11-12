import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

/**
 * Release signing comes from a gitignored `keystore.properties` (path set by
 * SMSPEND_KEYSTORE_PROPERTIES or at the repo root) or from environment variables.
 * Without either, the release build is left unsigned. Never commit a keystore.
 */
val releaseSigning: Map<String, String>? = run {
    val propsFile = (System.getenv("SMSPEND_KEYSTORE_PROPERTIES")?.let { file(it) })
        ?: rootProject.file("keystore.properties")
    if (propsFile.isFile) {
        val p = Properties().apply { propsFile.inputStream().use { load(it) } }
        val storeFile = p.getProperty("storeFile")?.let { path ->
            val f = file(path)
            if (f.isAbsolute) f else File(propsFile.parentFile, path)
        }
        if (storeFile != null && storeFile.isFile) {
            return@run mapOf(
                "storeFile" to storeFile.absolutePath,
                "storePassword" to p.getProperty("storePassword").orEmpty(),
                "keyAlias" to p.getProperty("keyAlias").orEmpty(),
                "keyPassword" to (p.getProperty("keyPassword") ?: p.getProperty("storePassword")).orEmpty(),
            )
        }
    }
    val envStore = System.getenv("SMSPEND_KEYSTORE")
    if (!envStore.isNullOrBlank() && file(envStore).isFile) {
        val storePass = System.getenv("SMSPEND_KEYSTORE_PASSWORD").orEmpty()
        return@run mapOf(
            "storeFile" to file(envStore).absolutePath,
            "storePassword" to storePass,
            "keyAlias" to System.getenv("SMSPEND_KEY_ALIAS").orEmpty(),
            "keyPassword" to (System.getenv("SMSPEND_KEY_PASSWORD") ?: storePass),
        )
    }
    null
}

android {
    namespace = "app.smspend"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.smspend"
        minSdk = 26
        targetSdk = 36
        versionCode = 131
        versionName = "1.3.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getValue("storeFile"))
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigning != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    sourceSets {
        // Exported Room schemas double as migration-test fixtures. Robolectric unit tests
        // read assets from the debug variant, so they ship in debug builds only.
        getByName("debug").assets.directories.add("$projectDir/schemas")
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.datastore:datastore-preferences:1.3.0")

    val room = "2.8.5"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.glance:glance-appwidget:1.2.0")
    implementation("androidx.glance:glance-material3:1.2.0")
    implementation("androidx.biometric:biometric:1.1.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.room:room-testing:$room")
    testImplementation("androidx.work:work-testing:2.12.0")
    androidTestImplementation("androidx.room:room-testing:$room")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
