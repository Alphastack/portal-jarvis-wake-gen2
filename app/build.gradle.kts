plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.german.portaljarviswake"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.german.portaljarviswake"
        minSdk = 28
        targetSdk = 29
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            // Public preview builds are intentionally debug-signed so GitHub Releases always
            // contain an installable APK. Replace this with a stable private release key before
            // publishing to an app catalog.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    buildFeatures { buildConfig = true }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*") }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    testImplementation("junit:junit:4.13.2")
}
