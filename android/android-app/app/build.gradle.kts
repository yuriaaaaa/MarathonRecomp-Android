plugins {
    id("com.android.application")
}

android {
    namespace = "com.marathonrecomp.android"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.marathonrecomp.android"
        minSdk = 29
        targetSdk = 35
        versionCode = System.getenv("MARATHON_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("MARATHON_VERSION_NAME") ?: "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // Two apps from one source: Marathon Recompiled itself, and Soleanna Hub (Sonic's town as a
    // launcher for other Android games), which installs next to it under its own id.
    flavorDimensions += "app"
    productFlavors {
        create("marathon") {
            dimension = "app"
            buildConfigField("boolean", "HUB", "false")
        }
        create("soleanna") {
            dimension = "app"
            applicationId = "io.github.david.soleannahub"
            versionCode = System.getenv("HUB_VERSION_CODE")?.toIntOrNull() ?: 1
            versionName = System.getenv("HUB_VERSION_NAME") ?: "0.1"
            buildConfigField("boolean", "HUB", "true")
        }
    }

    // Release signing comes only from the environment; no key or password is kept in the
    // repository. Set MARATHON_KEYSTORE (path), MARATHON_STORE_PASSWORD, MARATHON_KEY_ALIAS and
    // MARATHON_KEY_PASSWORD to sign with your own key. Without them the release APK is signed with
    // the local debug key.
    val releaseKeystore = System.getenv("MARATHON_KEYSTORE")?.let { file(it) }
    val releaseStorePassword = System.getenv("MARATHON_STORE_PASSWORD")
    val hasReleaseKey = releaseKeystore != null && releaseKeystore.isFile && !releaseStorePassword.isNullOrEmpty()

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = System.getenv("MARATHON_KEY_ALIAS") ?: "release"
                keyPassword = System.getenv("MARATHON_KEY_PASSWORD") ?: releaseStorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        jniLibs {
            // The native libraries are already stripped.
            keepDebugSymbols += listOf("**/*.so")
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // unlub (ShadowLAG's build of unluac 1.2.2, MIT): the Lua 5.0 decompiler the Sonic '06 Mod Manager
    // uses for patches that edit game scripts.
    implementation(files("libs/unlub.jar"))
}
