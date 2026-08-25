plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "apk.harness"
    compileSdk = 35

    defaultConfig {
        // Overridable so a throwaway id can be built without editing this file.
        // Relocating a Termux bootstrap needs a prefix no longer than the one
        // compiled into its binaries -- 31 bytes, which an eleven-character id
        // affords by naming the directory below it in four.
        applicationId = (findProperty("harnessAppId") as String?) ?: "apk.harness"
        minSdk = 24
        // An app targeting API 29 or later may not execute a file in its own data
        // directory, which is where the agent binary is staged. 28 is the last
        // level that allows it, and is where Termux sits for the same reason.
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isDebuggable = true
        }
        // The channel that keeps working while the other one is being broken.
        // It carries the plain application id, because it is the one a userland
        // has to fit under; the channel that iterates takes the other name.
        create("stable") {
            initWith(getByName("debug"))
            versionNameSuffix = "-stable"
            // The library module has no matching build type.
            matchingFallbacks += "debug"
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Unpack the native libraries into the install directory. Mapping them
            // straight out of the APK leaves that directory empty, and the syscall
            // shim there is a program to execute, not a library to load.
            useLegacyPackaging = true
        }
    }
}

// A build type carries a suffix, not an application id of its own, and the
// channel that iterates is not a suffix of the channel that has to keep
// working. Left alone when an id is given on the command line, so a throwaway
// build still gets the one it asked for.
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        if (findProperty("harnessAppId") == null) variant.applicationId.set("dev.harness")
    }
}

dependencies {
    implementation(project(":terminal-library"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // The socket the agent connects back on.
    implementation("org.java-websocket:Java-WebSocket:1.6.0")

    // Markdown the agent asks the app to render.
    implementation("com.mikepenz:multiplatform-markdown-renderer-android:0.26.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.26.0")

    // Maps without Play services or an API key.
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
