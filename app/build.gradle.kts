import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun localProp(key: String, default: String = ""): String =
    (localProperties.getProperty(key) ?: default)
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

android {
    namespace = "com.bookreader.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bookreader.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "DOUBAO_API_KEY", "\"${localProp("DOUBAO_API_KEY")}\"")
        buildConfigField("String", "DOUBAO_MODEL_ID", "\"${localProp("DOUBAO_MODEL_ID")}\"")
        buildConfigField("String", "VOLC_ASR_APP_ID", "\"${localProp("VOLC_ASR_APP_ID")}\"")
        buildConfigField(
            "String",
            "VOLC_ASR_ACCESS_TOKEN",
            "\"${localProp("VOLC_ASR_ACCESS_TOKEN")}\""
        )
        buildConfigField("String", "AZURE_SPEECH_KEY", "\"${localProp("AZURE_SPEECH_KEY")}\"")
        buildConfigField(
            "String",
            "AZURE_SPEECH_REGION",
            "\"${localProp("AZURE_SPEECH_REGION")}\""
        )
        buildConfigField(
            "String",
            "AZURE_SPEECH_ENDPOINT",
            "\"${localProp("AZURE_SPEECH_ENDPOINT")}\""
        )
        buildConfigField(
            "String",
            "AZURE_TTS_VOICE_EN",
            "\"${localProp("AZURE_TTS_VOICE_EN")}\""
        )
        buildConfigField(
            "String",
            "AZURE_TTS_VOICE_ZH",
            "\"${localProp("AZURE_TTS_VOICE_ZH")}\""
        )
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    val cameraX = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
