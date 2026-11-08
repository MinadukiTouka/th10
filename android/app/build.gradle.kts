// TH10 Android app module (package com.zun.th10).
//
// The shape mirrors the TH08 port's android/ project so the same Windows
// toolchain (JDK 17 + Gradle 9.8.2 + AGP 9.4.1 + NDK r27b) drives it:
//
//   . .\tools\env.ps1
//   gradle.bat -p android assembleRelease -Pth10.versionName=1.0.0 -Pth10.versionCode=1
//
// Unlike TH08 there is no Rust step: the native side is CMake (SDL2 + game +
// Android host). Properties:
//   th10.abis        arm64-v8a (default) or x86_64 (emulator debug builds)
//   th10.minSdk      26 (default)
//   th10.channel     release (default) | dev | debug; adds an applicationIdSuffix
//   th10.versionName / th10.versionCode
//   th10.keystore    keystore.properties for release signing
//   th10.debugKey    debug keystore used when th10.keystore is absent
//   th10.minify      false (default) / true: R8 is the RAM-hungry step
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
}

val repoDir: File = rootDir.parentFile          // .../th10-port
val portDir: File = repoDir.parentFile          // workspace root
val thirdPartyDir: File = File(portDir, "third_party")

fun prop(name: String): String? = (findProperty(name) as String?)?.takeIf { it.isNotBlank() }

val abiList = (prop("th10.abis") ?: "arm64-v8a").split(',').map { it.trim() }.filter { it.isNotEmpty() }
val minSdkLevel = (prop("th10.minSdk") ?: "26").toInt()
val appChannel = (prop("th10.channel") ?: "release").also {
    if (it !in listOf("release", "dev", "debug")) throw GradleException("th10.channel $it: release, dev or debug")
}
val appVersion = prop("th10.versionName") ?: "1.0.0"
val doMinify = (prop("th10.minify") ?: "false").toBoolean()

// ---------------------------------------------------------------- bundled assets
// The APK can carry game files inside assets/; the launcher unpacks them into the
// app's external files folder on first "start game".
//   th10.bundleFont     (default true)  -> msgothic.ttc  (public build: font only)
//   th10.bundleGameData (default false) -> th10.dat + thbgm.dat ("-full" build)
//   th10.data           source folder, default <workspace>/game-data/th10
fun bundleSourceDir(): File = File(prop("th10.data") ?: File(portDir, "game-data/th10").path)

fun bundledAssetFiles(): List<File> {
    val dir = bundleSourceDir()
    val out = mutableListOf<File>()
    fun add(name: String) {
        val f = File(dir, name)
        if (f.isFile) out.add(f) else throw GradleException("th10.data: $name not found in $dir")
    }
    if ((prop("th10.bundleFont") ?: "true").toBoolean()) add("msgothic.ttc")
    if ((prop("th10.bundleGameData") ?: "false").toBoolean()) {
        add("th10.dat")
        add("thbgm.dat")
    }
    return out
}

abstract class BundledAssets : DefaultTask() {
    @get:InputFiles abstract val files: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        files.files.forEach { it.copyTo(File(out, it.name.lowercase()), overwrite = true) }
    }
}

// Release keystore from keystore.properties (values stay in Gradle memory), else the throwaway debug key.
fun keyConfig(): Map<String, Any> {
    prop("th10.keystore")?.let { path ->
        val f = File(path).absoluteFile
        if (!f.isFile) throw GradleException("no keystore properties file $path")
        val p = Properties().apply { f.inputStream().use { load(it) } }
        fun v(k: String) = p.getProperty(k)?.trim() ?: throw GradleException("$path: missing $k")
        val store = File(v("storeFile")).let { if (it.isAbsolute) it else File(f.parentFile, it.path) }
        if (!store.isFile) throw GradleException("keystore named by $path not found")
        return mapOf("file" to store, "storePass" to v("storePassword"), "alias" to v("keyAlias"), "keyPass" to v("keyPassword"))
    }
    val ks = File(prop("th10.debugKey") ?: File(portDir, "secrets/debug.keystore").path).absoluteFile
    if (!ks.isFile) {
        ks.parentFile.mkdirs()
        val p = ProcessBuilder("keytool", "-genkeypair", "-keystore", ks.path, "-storepass", "android", "-keypass",
            "android", "-alias", "th10debug", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
            "-dname", "CN=th10 debug").redirectErrorStream(true).start()
        p.inputStream.readBytes()
        if (p.waitFor() != 0) throw GradleException("keytool failed for $ks")
    }
    return mapOf("file" to ks, "storePass" to "android", "alias" to "th10debug", "keyPass" to "android")
}

android {
    namespace = "com.zun.th10"
    compileSdk = 36
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "com.zun.th10"
        minSdk = minSdkLevel
        targetSdk = 36
        versionCode = (prop("th10.versionCode") ?: "1").toInt()
        versionName = appVersion
        ndk { abiFilters += abiList }

        externalNativeBuild {
            // ndk-build, not CMake: CMake configure crashes on this host whenever
            // both C and CXX are enabled (see jni/Android.mk header).
            ndkBuild { }
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    signingConfigs {
        create("th10") {
            val k = keyConfig()
            storeFile = k["file"] as File
            storePassword = k["storePass"] as String
            keyAlias = k["alias"] as String
            keyPassword = k["keyPass"] as String
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = doMinify
            isShrinkResources = doMinify
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("th10")
            if (appChannel != "release") applicationIdSuffix = ".$appChannel"
            resValue("string", "app_name",
                if (appChannel == "release") "東方風神録 ～ Mountain of Faith" else "東方風神録 ～ Mountain of Faith $appChannel")
            buildConfigField("String", "CHANNEL", "\"$appChannel\"")
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("th10")
            resValue("string", "app_name", "東方風神録 ～ Mountain of Faith debug")
            buildConfigField("String", "CHANNEL", "\"debug\"")
        }
    }

    buildFeatures {
        resValues = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            // SDL2's Java glue (org.libsdl.app) is used from the fetched sources
            // instead of being copied into this tree.
            java.srcDir(File(thirdPartyDir, "SDL2/android-project/app/src/main/java"))
        }
    }

    // Stored .so (extractNativeLibs=false); game data / fonts stay uncompressed so
    // the first-launch unpack is a straight copy and openFd() can report sizes.
    packaging { jniLibs { useLegacyPackaging = false } }
    androidResources { noCompress += listOf("dat", "ttc") }

    lint { checkReleaseBuilds = false; abortOnError = false }
}

androidComponents {
    onVariants { v ->
        val cap = v.name.replaceFirstChar { it.uppercase() }
        val assets = tasks.register<BundledAssets>("bundledAssets$cap") {
            files.from(bundledAssetFiles())
        }
        v.sources.assets?.addGeneratedSourceDirectory(assets, BundledAssets::outputDir)
    }
}

dependencies { }
