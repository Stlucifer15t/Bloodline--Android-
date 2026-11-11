plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing credentials.
//
// They are never stored in the repository: they come from a gitignored
// keystore.properties file, or from environment variables (which is how CI
// injects repository secrets). See README "Signing a release APK".
val keystoreProperties: Properties by lazy {
    Properties().apply {
        val propertiesFile = rootProject.file("keystore.properties")
        if (propertiesFile.exists()) {
            propertiesFile.inputStream().use { load(it) }
        }
    }
}

fun signingProperty(name: String): String? =
    keystoreProperties.getProperty(name)
        ?: System.getenv("BLOODLINE_${name.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()}")

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

    signingConfigs {
        create("release") {
            storeFile = file(signingProperty("storeFile") ?: "bloodline-release.jks")
            storePassword = signingProperty("storePassword").orEmpty()
            keyAlias = signingProperty("keyAlias").orEmpty()
            keyPassword = signingProperty("keyPassword").orEmpty()
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = false
            // Sign only when a keystore and its credentials were actually
            // supplied; otherwise build an unsigned APK instead of failing.
            signingConfig = signingConfigs.getByName("release").takeIf { config ->
                config.storeFile?.exists() == true && !config.storePassword.isNullOrEmpty()
            }
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
    // Navigating the game folder the user picks with the Storage Access Framework.
    implementation(libs.androidx.documentfile)

    testImplementation(libs.junit)
}
