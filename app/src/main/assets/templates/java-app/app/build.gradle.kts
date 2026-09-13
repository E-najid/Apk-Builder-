plugins {
    id("com.android.application")
}

android {
    namespace = "{{PACKAGE_NAME}}"
    compileSdk = {{COMPILE_SDK}}

    defaultConfig {
        applicationId = "{{PACKAGE_NAME}}"
        minSdk = {{MIN_SDK}}
        targetSdk = {{TARGET_SDK}}
        versionCode = 1
        versionName = "1.0"
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
}
