plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val prebuiltNative = (findProperty("prebuiltNative") as String?)?.let { file(it) }
val prebuiltAbi = (findProperty("prebuiltAbi") as String?) ?: "arm64-v8a"
val prebuiltJni = layout.buildDirectory.dir("prebuilt-jni").get().asFile

android {
    namespace = "apk.harness"
    compileSdk = 36
    // The revision terminal-library pins, so AGP consumes one NDK for both.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        // Overridable so a throwaway id can be built without editing this file.
        // Relocating a Termux bootstrap needs a prefix no longer than the one
        // compiled into its binaries -- 31 bytes, which an eleven-character id
        // affords by naming the directory below it in four.
        applicationId = (findProperty("harnessAppId") as String?) ?: "apk.harness"
        // The first level with all-files access, which is what lets a folder on
        // shared storage be opened by path.
        minSdk = 30
        // At this target `execve` of a file in the app's own data directory is
        // refused, and mapping one executable is not. Every program there is
        // started through a loader: `linker64` for bionic, the packaged musl
        // loader for the agent.
        targetSdk = 36
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
        // The channel that ships from GitHub releases. It carries the plain
        // application id.
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
        // The channel Google Play distributes, as a bundle. Play refuses a
        // debuggable build. Outside CI it carries the debug key, so a local build
        // installs; CI (which sets `CI`) leaves it unsigned, as it does `release`.
        create("play") {
            initWith(getByName("release"))
            isDebuggable = false
            if (System.getenv("CI") == null) signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
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
    if (prebuiltNative == null) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    } else {
        // Every native library comes from the prebuilt directory, so the
        // src/main/jniLibs tree is left out rather than packaged twice, and a
        // dependency's libraries are packaged for that one ABI only.
        sourceSets.getByName("main").jniLibs.setSrcDirs(listOf(prebuiltJni))
        defaultConfig.ndk.abiFilters.apply { clear(); add(prebuiltAbi) }
    }
}

// -PprebuiltNative=<dir> packages the lib*.so files of <dir> -- one ABI's, as an
// installed app's nativeLibraryDir holds them -- instead of building any. The
// ABI is -PprebuiltAbi, arm64-v8a by default. No CMake target, no loader build
// and no proot build runs, here or in terminal-library, so a Kotlin change
// builds where none of the toolchains does.
if (prebuiltNative != null) {
    val sync = tasks.register<Sync>("syncPrebuiltNative") {
        description = "Copy the prebuilt native libraries into a jniLibs tree"
        from(prebuiltNative) { include("*.so") }
        into(prebuiltJni.resolve(prebuiltAbi))
    }
    tasks.named("preBuild") { dependsOn(sync) }
}

// A build type carries a suffix, not an application id of its own, and no
// channel's id is a suffix of another's, so each one is named here instead.
// `release` keeps the id from `defaultConfig`. Left alone when an id is given on
// the command line, so a throwaway build still gets the one it asked for.
//
// Every id is eleven characters, which is what the bootstrap relocation's byte
// budget affords.
androidComponents {
    val ids = mapOf("debug" to "dev.harness", "stable" to "stg.harness", "play" to "btl.harness")
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
    // The router and the musl patch are compiled into the loader.
    inputs.dir("src/main/cpp/harness-exec")
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

// proot, its talloc and its loader, one set per ABI, built from pinned sources
// by the NDK. Like the musl loader it goes through WSL on Windows.
// -PskipProotBuild consumes whatever is already in jniLibs.
tasks.register<Exec>("buildProot") {
    description = "Build the proot files the APK packages, one set per ABI"
    group = "build"

    workingDir = rootProject.projectDir
    inputs.file(rootProject.file("scripts/build-proot.sh"))
    inputs.dir("src/main/cpp/proot")
    for (abi in listOf("arm64-v8a", "x86_64")) {
        for (name in listOf("libproot.so", "libproot-talloc.so", "libproot-loader.so")) {
            outputs.file("src/main/jniLibs/$abi/$name")
        }
    }

    val script = "scripts/build-proot.sh"
    commandLine(
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            listOf("wsl", "bash", script)
        } else {
            listOf("bash", script)
        }
    )
}

tasks.named("preBuild") {
    if (!project.hasProperty("skipLoaderBuild") && prebuiltNative == null) dependsOn("buildMuslLoader")
    if (!project.hasProperty("skipProotBuild") && prebuiltNative == null) dependsOn("buildProot")
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
