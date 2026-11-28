plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Unico punto que se modifica al preparar una nueva entrega.
val astraVersionCode = 71
val astraVersionName = "0.5.10"

// Repositorio de GitHub del que se descargan las actualizaciones del launcher.
val updateRepository = providers.gradleProperty("updateRepository").orNull ?: "Koretion/Astra-H-Launcher"
// Repositorio de las releases del firmware GMLAN-USB.
val firmwareUpdateRepository = providers.gradleProperty("firmwareUpdateRepository").orNull ?: "Koretion/GMLAN-USB"

val includeEmulatorAbis = providers.gradleProperty("includeEmulatorAbis")
    .orNull
    ?.toBooleanStrictOrNull()
    ?: true

val ts10DebugKeystore = providers.gradleProperty("ts10Keystore")
    .orNull
    ?.let(::file)
    ?: System.getenv("USERPROFILE")
        ?.let { file("$it/.android/debug.keystore") }

android {
    namespace = "com.ts10.inspector"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ts10.inspector"
        minSdk = 29
        targetSdk = 34
        versionCode = astraVersionCode
        versionName = astraVersionName

        buildConfigField("String", "UPDATE_REPOSITORY", "\"$updateRepository\"")
        buildConfigField("String", "FIRMWARE_UPDATE_REPOSITORY", "\"$firmwareUpdateRepository\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            val targetAbi = providers.gradleProperty("targetAbi").orNull
            abiFilters += targetAbi?.let(::listOf)
                ?: listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        getByName("debug") {
            if (ts10DebugKeystore?.isFile == true) {
                storeFile = ts10DebugKeystore
            }
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = false
            enableV4Signing = false
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            // Android Studio emulators commonly use x86/x86_64. The production TS10 APK
            // is built with -PincludeEmulatorAbis=false and remains ARM-only.
            if (includeEmulatorAbis) {
                ndk {
                    abiFilters += listOf("x86", "x86_64")
                }
            }
        }
        release {
            // Build de entrega: misma clave que las versiones anteriores, R8 y solo ARM.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        // El lint automatico del build release falla con JDK 25 y AGP 8.5 ("Already disposed").
        checkReleaseBuilds = false
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
        aidl = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    androidResources {
        noCompress += "tflite"
        noCompress += "glb"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")

    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.herohan:UVCAndroid:1.0.13")
    implementation("com.google.ai.edge.litert:litert:1.4.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
