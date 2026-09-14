plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.vynox.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.vynox.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = libs.versions.appVersionCode.get().toInt()
        versionName = libs.versions.appVersionName.get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += setOf("en")
    }

    /**
     * Release signing is driven entirely by the CI environment:
     *   VYNOX_KEYSTORE_FILE, VYNOX_KEYSTORE_PASSWORD, VYNOX_KEY_ALIAS, VYNOX_KEY_PASSWORD
     * No credential is ever stored in this repository. When the variables are
     * absent (local debug builds) the release variant simply stays unsigned.
     */
    signingConfigs {
        create("release") {
            val keystoreFile = System.getenv("VYNOX_KEYSTORE_FILE")
            if (!keystoreFile.isNullOrBlank() && file(keystoreFile).exists()) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("VYNOX_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VYNOX_KEY_ALIAS")
                keyPassword = System.getenv("VYNOX_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val release = signingConfigs.findByName("release")
            if (release?.storeFile != null) {
                signingConfig = release
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // Exposes BuildConfig.VERSION_NAME to the About and Settings screens.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/*.kotlin_module"
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = false
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)

    implementation(project(":core:json"))
    implementation(project(":core:math"))
    implementation(project(":core:animation"))
    implementation(project(":core:model"))
    implementation(project(":core:effects"))
    implementation(project(":core:composition"))
    implementation(project(":core:timeline"))
    implementation(project(":core:vnx"))

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}
