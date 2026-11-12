import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
}

val appVersionName = "v3.0.5"
val appVersionCode = 30005
val apkBaseName = "testtube" + appVersionName.removePrefix("v")
val appMinSdk = 26
// ARM only to keep it small; add "x86_64" for emulators and Intel Chromebooks. The Rust core
// (native/testtube-core) is built for the same list.
val shippedAbis = listOf("armeabi-v7a", "arm64-v8a")

// standard = TestTube (YouTube only), plus = TestTube+ (Spotify, Facebook, SoundCloud too). Same id
// and key so either installs over the other. The updater matches these names (Edition.APK_PREFIX);
// testtube<version>.apk has to stay the standard one, old updaters grab the first APK of a release.
val editionApkPrefix = mapOf("standard" to "testtube", "plus" to "testtubeplus")

base {
    archivesName.set(apkBaseName)
}

// release signing comes from BUILD.sh via env vars, no secrets in the repo
val releaseKeystoreFile: String? = System.getenv("TESTTUBE_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.testtube.app"
    compileSdk = 37

    lint {
        disable.add("MissingTranslation")
        disable.add("ExtraTranslation")
        abortOnError = false
        checkReleaseBuilds = false
    }

    defaultConfig {
        applicationId = "com.testtube.app"
        minSdk = appMinSdk
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        ndk {
            abiFilters.addAll(shippedAbis)
        }
    }

    signingConfigs {
        if (releaseKeystoreFile != null) {
            create("release") {
                storeFile = file(releaseKeystoreFile)
                storePassword = System.getenv("TESTTUBE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TESTTUBE_KEY_ALIAS")
                keyPassword = System.getenv("TESTTUBE_KEY_PASSWORD")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    flavorDimensions += "edition"
    productFlavors {
        create("standard") {
            dimension = "edition"
            isDefault = true
        }
        create("plus") {
            dimension = "edition"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
            if (releaseKeystoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    androidResources {
        // only our languages, the libraries ship dozens more
        localeFilters += listOf("en", "es", "fr", "ja", "ko", "ru", "tr", "zh", "zh-rTW")
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            // only for Kotlin reflection and debuggers, we use neither
            excludes += listOf("kotlin/**", "META-INF/*.kotlin_module", "META-INF/*.version", "DebugProbesKt.bin")
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
    implementation(libs.newpipeextractor)
    implementation(libs.activity)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.glide)
    implementation(libs.glide.okhttp)
    annotationProcessor(libs.glide.compiler)
    implementation(libs.gson)
    implementation(libs.isoparser)
    implementation(libs.lifecycle.livedata)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.material)
    implementation(libs.media)
    implementation(libs.media3.datasource)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.mmkv)
    implementation(libs.okhttp)
    implementation(libs.profileinstaller)
    implementation(libs.recyclerview)
    implementation(libs.swiperefreshlayout)
    implementation(libs.webkit)
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
}

abstract class ExportApkTask : DefaultTask() {
    @get:InputFiles
    abstract val apkFolder: DirectoryProperty

    @get:Internal
    abstract val artifactsLoader: Property<BuiltArtifactsLoader>

    @get:OutputFile
    abstract val outputApk: RegularFileProperty

    @TaskAction
    fun export() {
        val builtArtifacts = artifactsLoader.get().load(apkFolder.get())
            ?: throw GradleException("No APK was produced in ${apkFolder.get().asFile}")
        val apk = builtArtifacts.elements.singleOrNull()
            ?: throw GradleException("Expected exactly one APK, found ${builtArtifacts.elements.size}")
        val target = outputApk.get().asFile
        target.parentFile.mkdirs()
        val source = File(apk.outputFile)
        source.copyTo(target, overwrite = true)
        // only keep the renamed copy in dist/
        source.delete()
        logger.lifecycle("APK: ${target.absolutePath}")
    }
}

/**
 * Builds the Rust core (native/testtube-core) into libtesttube_core.so for the shipped ABIs with
 * cargo-ndk. The extractor runs YouTube's player JavaScript in it (QuickJS) and falls back to
 * Rhino when it's missing, so -Ptesttube.skipRust=true still gives a working, slower, APK.
 * Needs cargo, cargo-ndk, the Android Rust targets and an NDK; BUILD.sh installs all of them.
 */
abstract class BuildRustCoreTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val crateSources: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val crateManifests: ConfigurableFileCollection

    @get:Input
    abstract val abis: ListProperty<String>

    @get:Input
    abstract val minSdk: Property<Int>

    @get:Internal
    abstract val crateDir: DirectoryProperty

    @get:Internal
    abstract val sdkDir: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun build() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val args = mutableListOf(cargo(), "ndk")
        abis.get().forEach { args += listOf("-t", it) }
        args += listOf("--platform", minSdk.get().toString(), "-o", out.absolutePath, "build", "--release", "--locked")
        exec.exec {
            workingDir = crateDir.get().asFile
            commandLine(args)
            // cargo-ndk finds the NDK under the SDK unless ANDROID_NDK_HOME says otherwise
            if (System.getenv("ANDROID_HOME").isNullOrBlank()) environment("ANDROID_HOME", sdkDir.get())
        }
    }

    /** Android Studio's Gradle often doesn't have ~/.cargo/bin on its PATH. */
    private fun cargo(): String {
        val home = System.getProperty("user.home")
        val candidates = listOfNotNull(
            System.getenv("CARGO"),
            System.getenv("CARGO_HOME")?.let { "$it/bin/cargo" },
            "$home/.testtube/cargo/bin/cargo",
            "$home/.cargo/bin/cargo",
        )
        return candidates.firstOrNull { File(it).canExecute() } ?: "cargo"
    }
}

val skipRust = providers.gradleProperty("testtube.skipRust").map { it.toBoolean() }.getOrElse(false)
val rustCrate = rootProject.layout.projectDirectory.dir("native/testtube-core")

androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val edition = variant.productFlavors.firstOrNull { it.first == "edition" }?.second ?: "standard"
        val prefix = editionApkPrefix[edition] ?: throw GradleException("Unknown edition $edition")
        val baseName = prefix + appVersionName.removePrefix("v")
        val fileName = if (variant.buildType == "release") {
            "$baseName.apk"
        } else {
            "$baseName-${variant.buildType}.apk"
        }
        val exportTask = tasks.register<ExportApkTask>("export${variantName}Apk") {
            apkFolder.set(variant.artifacts.get(SingleArtifact.APK))
            artifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
            outputApk.set(rootProject.layout.projectDirectory.file("dist/$fileName"))
        }
        tasks.matching { it.name == "assemble$variantName" }.configureEach {
            finalizedBy(exportTask)
        }
        if (!skipRust) {
            val rustTask = tasks.register<BuildRustCoreTask>("buildRustCore$variantName") {
                group = "build"
                description = "Builds libtesttube_core.so (Rust, QuickJS) for $variantName"
                crateDir.set(rustCrate)
                crateSources.set(rustCrate.dir("src"))
                crateManifests.from(rustCrate.file("Cargo.toml"), rustCrate.file("Cargo.lock"))
                abis.set(shippedAbis)
                minSdk.set(appMinSdk)
                sdkDir.set(androidComponents.sdkComponents.sdkDirectory.map { it.asFile.absolutePath })
            }
            variant.sources.jniLibs?.addGeneratedSourceDirectory(rustTask, BuildRustCoreTask::outputDir)
        }
    }
}
