import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ggumtak.readeraplus"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ggumtak.readeraplus"
        minSdk = 26
        targetSdk = 34
        // CI passes its run number, so every build is a higher version than the one installed.
        val buildNumber = System.getenv("BUILD_NUMBER")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    // Release signing comes from the environment (CI: the repository secrets, or the key kept in the Actions
    // cache); keys never live in the repo. Local builds without it use the debug key. Every CI build must use
    // the same key, or the APK cannot be installed over the previous one.
    val keystorePath = System.getenv("SIGNING_KEYSTORE_FILE")
    signingConfigs {
        if (keystorePath != null && file(keystorePath).exists()) {
            create("personal") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }
    val releaseSigning = signingConfigs.findByName("personal") ?: signingConfigs.getByName("debug")

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = releaseSigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        // Fonts are memory-mapped straight from the APK when stored uncompressed.
        noCompress += listOf("ttf", "otf")
    }

    packaging {
        resources.excludes += listOf("META-INF/*.kotlin_module", "DebugProbesKt.bin", "kotlin/**")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    // Real (pure-Java) framework classes for JVM tests, matching tools/unittest.sh; org.json explicitly too.
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:android-all:15-robolectric-12650502")
}
