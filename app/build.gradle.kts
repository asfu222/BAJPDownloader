plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.asfu222.bajpdl"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.asfu222.bajpdl"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        signingConfig = if (System.getenv("KEYSTORE_PASSWORD")?.isNotBlank() == true &&
            System.getenv("KEY_ALIAS")?.isNotBlank() == true &&
            System.getenv("KEY_PASSWORD")?.isNotBlank() == true) {
            signingConfigs.create("release") {
                storeFile = file("keystore.jks")
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        } else {
            println("No keystore found, signing disabled. This build will not work with mitm.")
            null
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation(libs.lz4.java)
    implementation(libs.json)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.okhttp)
    implementation(libs.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
