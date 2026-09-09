import java.util.Properties

plugins {
    alias(libs.plugins.aboutLibraries)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.jetbrains.kotlin.serialization)
    alias(libs.plugins.ksp)
}

dependencies {
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.aboutlibraries.core)
    implementation(libs.accompanist.permissions)
    implementation(libs.activity.compose)
    implementation(libs.activity.ktx)
    implementation(libs.android.material)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.navigation.runtime.ktx)
    implementation(libs.bcrypt)
    implementation(libs.camera.camera2)
    implementation(libs.camera.core)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.constraintlayout)
    implementation(libs.core.ktx)
    implementation(libs.dagger)
    implementation(libs.documentfile)
    implementation(libs.gson)
    implementation(libs.guava)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lingala.zip4j)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.okhttp)
    implementation(libs.preference.ktx)
    implementation(libs.viewpager2)
    implementation(libs.work.runtime.ktx)
    implementation(libs.zhanghai.compose.preference)
    implementation(libs.zxing.core)
    ksp(libs.dagger.compiler)
}

// Load local.properties once
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun propOrEnv(key: String): String? =
    localProps.getProperty(key) ?: System.getenv(key)

val keystorePath = propOrEnv("KEYSTORE_PATH")
val keystorePassword = propOrEnv("KEYSTORE_PASSWORD")
val keyAlias = propOrEnv("KEY_ALIAS")
val keyPassword = propOrEnv("KEY_PASSWORD")

android {
    compileSdk = libs.versions.compile.sdk.get().toInt()
    buildToolsVersion = "37.0.0"
    namespace = "com.micnubinub.syncthing"
    ndkVersion = libs.versions.ndk.version.get()

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/libSyncthingNative.mk")
        }
    }

    buildFeatures {
        compose = true
    }

    defaultConfig {
        applicationId = "com.micnubinub.syncthing"
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.target.sdk.get().toInt()
        versionCode = libs.versions.version.code.get().toInt()
        versionName = libs.versions.version.name.get()
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                // If any credential is missing, fail fast here rather than producing
                // a confusing signing error deep inside the release build.
                val missing = buildList {
                    if (keystorePassword == null) add("KEYSTORE_PASSWORD")
                    if (keyAlias == null) add("KEY_ALIAS")
                    if (keyPassword == null) add("KEY_PASSWORD")
                }
                check(missing.isEmpty()) {
                    "Release keystore configured (KEYSTORE_PATH) but missing: ${
                        missing.joinToString(
                            ", "
                        )
                    }. " +
                            "Set them in local.properties or environment before signing."
                }
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = keyAlias
                keyPassword = keyPassword
            }
        }
        // When KEYSTORE_PATH is unset, the release config stays empty and the
        // release build is deliberately left unsigned (AGP's default debug signing
        // is used only for debug variants).
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isJniDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
                .takeIf { it.storeFile != null }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    bundle {
        language { enableSplit = false }
        density { enableSplit = true }
        abi { enableSplit = true }
    }

    splits {
        abi {
            // Enable ABI splits explicitly via -PenableAbiSplits=true (or the
            // equivalent flag in CI). AGP has no per-variant split toggle, so an
            // explicit property is clearer and more predictable than inspecting
            // the requested task names.
            // AppBundle tasks usually contain "bundle" in their name
            val isBuildingBundle =
                gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
            isEnable = !isBuildingBundle

            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            // Required so libsyncthing.so lands correctly in bundle installs
            useLegacyPackaging = true
        }
    }

    // TODO setup linting later
    lint {
        checkReleaseBuilds = false
        abortOnError = false
        targetSdk = libs.versions.target.sdk.get().toInt()
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_reports")
    metricsDestination = layout.buildDirectory.dir("compose_metrics")
}

abstract class ValidateAppVersionCode : DefaultTask() {
    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val versionCode: Property<Int>

    @TaskAction
    fun validate() {
        val name = versionName.get()
        val code = versionCode.get()

        val parts = name.split(".").map {
            it.toIntOrNull() ?: throw GradleException(
                "Invalid versionName '$name': segment '$it' is not a number."
            )
        }
        if (parts.size != 4) {
            throw GradleException(
                "Invalid versionName format: '$name'. Expected 'major.minor.patch.wrapper'."
            )
        }

        val calculated =
            parts[0] * 1_000_000 + parts[1] * 10_000 + parts[2] * 100 + parts[3]

        if (calculated != code) {
            throw GradleException(
                "Version mismatch: calculated versionCode ($calculated) != declared ($code). Check 'gradle/libs.versions.toml'."
            )
        }
    }
}

val validateAppVersionCode =
    tasks.register<ValidateAppVersionCode>("validateAppVersionCode") {
        versionName.set(libs.versions.version.name)
        versionCode.set(libs.versions.version.code.map { it.toInt() })
    }

tasks.matching { it.name.startsWith("assemble") || it.name.startsWith("bundle") }
    .configureEach { dependsOn(validateAppVersionCode) }

tasks
    .matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(":syncthing:buildNative") }
