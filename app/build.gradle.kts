plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.bloodline.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.bloodline.android"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = libs.versions.versionCode.get().toInt()
        versionName = libs.versions.versionName.get()

        // The real game is never bundled: it is detected on the device storage.
        buildConfigField("String", "LAUNCHER_STAGE", "\"foundation\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        // Layout bindings instead of findViewById.
        viewBinding = true
        // Needed for BuildConfig.VERSION_NAME, shown in the launcher footer.
        buildConfig = true
    }

    buildTypes {
        debug {
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // A launcher that ships no translations yet should not fail the build.
        disable += setOf("MissingTranslation", "ExtraTranslation")
        // Never block a debug build on release-only checks.
        checkReleaseBuilds = false
        abortOnError = true
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        // Print the name and reason of every failing test to the console so CI
        // can show it directly instead of hiding it in an HTML report.
        events("failed")
        showExceptions = true
        showCauses = true
        showStackTraces = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
}
