import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Register your own GitHub OAuth app (with the Device Flow enabled) and either
// set GITHUB_CLIENT_ID in ~/.gradle/gradle.properties or pass
// -PGITHUB_CLIENT_ID=xxx when building. See README.md -> "OAuth setup".
// Note: client IDs are case-sensitive and are used verbatim.
val githubClientId: String = (
    providers.gradleProperty("GITHUB_CLIENT_ID").orNull
        ?: "REPLACE_WITH_YOUR_OAUTH_APP_CLIENT_ID"
    ).trim()

android {
    namespace = "com.enajid.apkbuilder"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.enajid.apkbuilder"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "GITHUB_CLIENT_ID", "\"$githubClientId\"")

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
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

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // GitHub REST API (no backend server — the app talks to GitHub directly)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)

    // QR codes for sharing the build page
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
