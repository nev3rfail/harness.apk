plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "apk.harness"
    compileSdk = 35
    // The revision terminal-library pins, so AGP consumes one NDK for both.
    ndkVersion = "29.0.14206865"

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
        // A release's identity is its tag, passed in by the workflow that
        // builds it. A build given neither property keeps these values.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.1.0"

        ndk {
            // An ABI with no renderer dies loading its native library, and the
            // renderer is built for these two.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        // The channel that ships. It carries the plain application id, which is
        // the one the loader's compiled-in paths are built against.
        release {
            // Unminified until R8 has been taught about the reflection in
            // `org.json`, osmdroid, ktoml, Java-WebSocket and Compose.
            isMinifyEnabled = false
            // `run-as` is the only route into app-private storage on an
            // unrooted device, and a bootstrap that fails on a pristine install
            // is invisible without it. Debuggability is not part of the update
            // compatibility check, so turning it off is an ordinary in-place
            // update that keeps the app's data.
            isDebuggable = true
        }
        debug {
            isDebuggable = true
        }
        // The channel that keeps working while the other one is being broken.
        // It is debuggable and carries the debug key, so its data directory can
        // be reached with `run-as`.
        create("stable") {
            initWith(getByName("debug"))
            versionNameSuffix = "-stg"
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

    androidResources {
        // The packaging step drops any asset whose name begins with a dot, and
        // every file the agent reads lives under `.claude`. This is the default
        // pattern with that one rule taken out.
        ignoreAssetsPattern =
            "!.svn:!.git:!.ds_store:!*.scc:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~"
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

    // The prefix relocator. It is built here rather than beside the Zig
    // libraries, because that build is skipped whenever -PskipNativeBuild is
    // passed and the ordinary loop always passes it.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

// A build type carries a suffix, not an application id of its own, and no
// channel's id is a suffix of another's, so each one is named here instead.
// `release` keeps the id from `defaultConfig`. Left alone when an id is given on
// the command line, so a throwaway build still gets the one it asked for.
//
// Every id is eleven characters, which is what `retargetLoader` requires: the
// loader's compiled-in paths are rewritten in place, so the name that replaces
// one must be the same length.
androidComponents {
    val ids = mapOf("debug" to "dev.harness", "stable" to "stg.harness")
    for ((buildType, id) in ids) {
        onVariants(selector().withBuildType(buildType)) { variant ->
            if (findProperty("harnessAppId") == null) variant.applicationId.set(id)
        }
    }
}

// The musl loader Claude Code's build asks for, one per ABI. Built rather than
// committed: it is a build output, and two copies checked in once disagreed with
// each other about whether they carried the /etc prefix.
//
// The toolchain is a Linux one -- musl's own build, plus a cross compiler for the
// other architecture -- so on Windows this goes through WSL. -PskipLoaderBuild
// consumes whatever is already in jniLibs, the same escape the renderer has.
tasks.register<Exec>("buildMuslLoader") {
    description = "Build the musl loaders the APK packages, one per ABI"
    group = "build"

    workingDir = rootProject.projectDir
    inputs.file(rootProject.file("scripts/build-loaders.sh"))
    inputs.file(rootProject.file("scripts/stage-claude.sh"))
    outputs.dir("src/main/jniLibs")

    val script = "scripts/build-loaders.sh"
    commandLine(
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            listOf("wsl", "bash", script)
        } else {
            listOf("bash", script)
        }
    )
}

tasks.named("preBuild") {
    if (!project.hasProperty("skipLoaderBuild")) dependsOn("buildMuslLoader")
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

    // TOML in a cell body. Pinned at 0.7.0: 0.7.1 declares kotlin-stdlib 2.2.0,
    // whose metadata this compiler cannot read.
    implementation("com.akuleshov7:ktoml-core:0.7.0")

    // Maps without Play services or an API key.
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    testImplementation("junit:junit:4.13.2")
    // A real implementation on the test classpath. The one in android.jar is a
    // stub that throws, so a manifest parsed in production would be untested.
    testImplementation("org.json:json:20250517")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
