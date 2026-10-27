import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader

plugins {
    alias(libs.plugins.android.application)
}

val appVersionName = "v2.2.2"
val appVersionCode = 20202
val apkBaseName = "testtube" + appVersionName.removePrefix("v")

base {
    archivesName.set(apkBaseName)
}

// Release signing comes from BUILD.sh through environment variables; no secrets in the repo.
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
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        ndk {
            // ARM only, to keep the APK small. Add "x86_64" for emulators and Intel Chromebooks.
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
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
        // Only the app's own languages; the libraries ship dozens more.
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
            excludes += "META-INF/services/javax.script.ScriptEngineFactory"
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
    implementation(libs.picasso)
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
        // Keep only the renamed copy in dist/.
        source.delete()
        logger.lifecycle("APK: ${target.absolutePath}")
    }
}

androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val fileName = if (variant.buildType == "release") {
            "$apkBaseName.apk"
        } else {
            "$apkBaseName-${variant.name}.apk"
        }
        val exportTask = tasks.register<ExportApkTask>("export${variantName}Apk") {
            apkFolder.set(variant.artifacts.get(SingleArtifact.APK))
            artifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
            outputApk.set(rootProject.layout.projectDirectory.file("dist/$fileName"))
        }
        tasks.matching { it.name == "assemble$variantName" }.configureEach {
            finalizedBy(exportTask)
        }
    }
}
