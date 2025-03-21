import java.io.StringReader
import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val envUrl = "https://raw.githubusercontent.com/asfu222/BACNLocalizationResources/refs/heads/main/ba.env"
val envText = URI.create(envUrl).toURL().readText(Charsets.UTF_8)
val envProps = Properties().apply {
    load(StringReader(envText))
}

android {
    namespace = "com.YostarJP.BlueArchive"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.YostarJP.BlueArchive"
        minSdk = 26
        targetSdk = 35
        versionCode = envProps.getProperty("BA_VERSION_CODE").toInt()
        versionName = envProps.getProperty("BA_VERSION_NAME")
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
            println("No keystore found, signing disabled. This mitmserver build will not work.")
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
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}