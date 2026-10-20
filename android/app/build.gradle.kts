plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.evdev.androidcontrol"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.evdev.androidcontrol"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        val envFile = rootProject.file("../.env")
        val envMap = mutableMapOf<String, String>()
        if (envFile.exists()) {
            envFile.forEachLine { raw ->
                val line = raw.trim()
                if (line.isNotEmpty() && !line.startsWith("#") && line.contains("=")) {
                    val parts = line.split("=", limit = 2)
                    envMap[parts[0].trim()] = parts[1].trim().trim('"', '\'')
                }
            }
        }

        fun requireEnv(key: String): String {
            return (project.findProperty(key) as? String)
                ?: System.getenv(key)
                ?: System.getenv("ANDROID_CONTROL_$key")
                ?: envMap[key]
                ?: throw GradleException("Missing required configuration '$key' in .env or environment")
        }

        val fbDbUrl = requireEnv("FIREBASE_DATABASE_URL")
        val fbApiKey = requireEnv("FIREBASE_API_KEY")
        val fbWebClientId = requireEnv("FIREBASE_WEB_CLIENT_ID")

        buildConfigField("String", "FIREBASE_DB_URL", "\"$fbDbUrl\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"$fbApiKey\"")
        buildConfigField("String", "FIREBASE_WEB_CLIENT_ID", "\"$fbWebClientId\"")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose Material 3
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // WebRTC
    implementation("io.getstream:stream-webrtc-android:1.3.10")

    // Serialization & Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.json:json:20240303")
}

