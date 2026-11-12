import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/** Keystore used when no BLOODLINE_STORE_FILE / keystore.properties entry says otherwise. */
val DEFAULT_KEYSTORE_FILE = "bloodline-release.jks"

/**
 * Key committed on purpose, so that every build — on any machine, on any CI
 * run — carries the same signature and installs over the previous one instead
 * of demanding an uninstall.
 *
 * It is public by design, exactly like Android's own debug keystore, and is
 * replaced automatically by the real release key as soon as its credentials
 * are supplied. See `tools/make-signing-key.py` and README "Signing".
 */
val PROJECT_KEYSTORE_FILE = "bloodline-signing.p12"

/** Not a secret: this key exists so builds stay interchangeable, not to prove identity. */
val PROJECT_KEYSTORE_PASSWORD = "bloodline"

val PROJECT_KEY_ALIAS = "bloodline"

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

    // Native code is built with the NDK: the probe that proves a bundled
    // executable can run inside the APK (see app/src/main/cpp).
    ndkVersion = "27.0.12077973"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

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
            val store = file(signingProperty("storeFile") ?: DEFAULT_KEYSTORE_FILE)
            val password = signingProperty("storePassword")

            if (store.exists() && !password.isNullOrEmpty()) {
                // The real release key, supplied through keystore.properties
                // locally or through BLOODLINE_* variables in CI.
                storeFile = store
                storePassword = password
                keyAlias = signingProperty("keyAlias").orEmpty()
                keyPassword = signingProperty("keyPassword").orEmpty()
            } else {
                // No release credentials here. Fall back to the committed
                // project key rather than building an APK that cannot replace
                // the one already installed.
                val projectStore = file(PROJECT_KEYSTORE_FILE)
                if (projectStore.exists()) {
                    storeFile = projectStore
                    storeType = "pkcs12"
                    storePassword = PROJECT_KEYSTORE_PASSWORD
                    keyAlias = PROJECT_KEY_ALIAS
                    keyPassword = PROJECT_KEYSTORE_PASSWORD
                }
            }
        }
    }

    // One signing config, checked once: both build types sign with the same
    // key so a debug install and a release install can replace each other.
    val releaseSigning = signingConfigs.getByName("release")
    val signingReady = releaseSigning.storeFile?.exists() == true &&
        !releaseSigning.storePassword.isNullOrEmpty() &&
        !releaseSigning.keyAlias.isNullOrEmpty()

    buildTypes {
        debug {
            versionNameSuffix = "-dev"
            signingConfig = releaseSigning.takeIf { signingReady }
        }
        release {
            isMinifyEnabled = false
            signingConfig = releaseSigning.takeIf { signingReady }
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

        // targetSdk is 28 on purpose, and lint is right to object, so it is
        // answered here rather than silenced silently.
        //
        // The check exists for the Play Store, which requires a recent
        // targetSdk. This app cannot be on the Play Store at any targetSdk:
        // since Android 10 the platform mounts an app's data directory
        // noexec unless it targets below 29, and this runtime has to exec Wine
        // from that directory — as does Wine itself for wineserver. The
        // reference implementation makes the same choice for the same reason.
        //
        // Full reasoning is on `targetSdk` in gradle/libs.versions.toml. It is
        // disabled with the reason recorded here, rather than worked around,
        // so that the constraint stays visible in the build instead of living
        // in whatever happens to be set that week.
        disable += "ExpiredTargetSdkVersion"

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

    // Unpacking the runtime packages downloaded at first run, which arrive as
    // tar.xz or tar.zst archives behind a private ".wcp" extension.
    implementation(libs.xz)
    implementation(libs.zstd.jni)

    testImplementation(libs.junit)
}
