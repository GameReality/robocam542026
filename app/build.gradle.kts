plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.robocam.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.robocam.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 4
        versionName = "1.3"

        multiDexEnabled = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // This is needed for 16KB page size support
        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
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
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    
    // For 16KB page size support
    androidResources {
        generateLocaleConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

base {
    archivesName.set("robocam")
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    
    // NanoHTTPD (Old server, keeping for compatibility if needed)
    implementation("org.nanohttpd:nanohttpd:2.3.1") {
        exclude(group = "org.nanohttpd", module = "nanohttpd-websocket")
    }
    
    // CameraX
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Ktor
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)

    // Others
    implementation("com.google.android.gms:play-services-ads:25.1.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}