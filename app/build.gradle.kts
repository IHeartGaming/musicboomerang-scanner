plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "nl.iheartgaming.musicboomerangscanner"
    compileSdk = 36

    signingConfigs {
        create("release") {
            // Only configure if environment variables are set
            System.getenv("KEYSTORE_PATH")?.let { storeFile = file(it) }
            System.getenv("KEYSTORE_PASSWORD")?.let { storePassword = it }
            System.getenv("KEY_PASSWORD")?.let { keyPassword = it }
            System.getenv("KEY_ALIAS")?.let { keyAlias = it }
        }
    }

    defaultConfig {
        applicationId = "nl.iheartgaming.musicboomerangscanner"
        minSdk = 21
        targetSdk = 36
        val gitTag: String = System.getenv("GIT_TAG") ?: "0.0.0"
        versionName = gitTag
        versionCode = gitTag.replace(Regex("[^0-9]"), "").toIntOrNull()?.takeIf { it > 0 } ?: 1

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Only assign signing config if keystore path exists
            System.getenv("KEYSTORE_PATH")?.let {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.ui)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.camera:camera-extensions:1.4.2")
    implementation("androidx.compose.material:material-icons-extended")
}