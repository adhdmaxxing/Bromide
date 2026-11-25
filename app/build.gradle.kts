plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Every commit is a new build: version 0.5.<build>, so the app, its photos' EXIF and the
// GitHub release all say which build it is. Bump the base for a new version.
// The public history starts from one squashed commit of 30 earlier private ones: the offset
// keeps build numbers (and Play version codes) rising past the test builds made before it.
val versionBase = "0.5"
val historyOffset = 30
val buildNumber = historyOffset + runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)

android {
    namespace = "com.bromide.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bromide.app"
        minSdk = 29
        targetSdk = 36
        versionCode = buildNumber
        versionName = "$versionBase.$buildNumber"

        // Early access: everyone who opens a build with this on is a founder, and Bromide stays
        // free for them for good (Roll.kt). Set it to false for the release that starts the
        // free roll + unlock for new users; founders keep their free Bromide.
        buildConfigField("boolean", "EARLY_ACCESS", "true")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        // A fixed debug key (not a secret) so each CI build can update the previous install.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // The Play upload key never lives in the repo: CI writes it from secrets (see
        // .github/workflows/release.yml); locally, set the same BROMIDE_* environment variables.
        val uploadKey = System.getenv("BROMIDE_KEYSTORE")
        if (uploadKey != null) {
            create("upload") {
                storeFile = file(uploadKey)
                storePassword = System.getenv("BROMIDE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BROMIDE_KEY_ALIAS")
                keyPassword = System.getenv("BROMIDE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload")
            ndk.debugSymbolLevel = "SYMBOL_TABLE"  // readable native crash reports in Play Console
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("com.android.billingclient:billing:8.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
