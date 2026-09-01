plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "fr.enry.archivist"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.enry.archivist"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // androidx.exifinterface's ExifInterface calls android.util.Log.isLoggable
            // in a static initializer; the bare android.jar stub throws for every
            // unmocked framework method by default, which would otherwise force
            // Robolectric just to construct the class. This AGP flag (not
            // Robolectric) makes unmocked stub methods return their default value
            // instead -- see ExifExtractorTest and android/AGENTS.md.
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            // BouncyCastle (via :core:crypto) and jspecify both ship this OSGi manifest.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

ksp {
    // Room schema JSON, one file per version — plan step 2.6's "migrations are
    // exported". Nothing to diff yet at version 1; this is what a future bump diffs
    // against.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core:crypto"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization.converter)
    implementation(libs.okhttp.core)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // AsyncImage + a Coil3 Fetcher hook for decrypting thumbnails on the fly — see
    // crypto/EncryptedImageFetcher.kt. Transitively pulls in coil-android, which is
    // where SingletonImageLoader.Factory (ArchivistApplication's hook) lives.
    implementation(libs.coil.compose)

    implementation(libs.androidx.exifinterface)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.androidx.work.testing)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)
    // DAO tests run as plain JVM unit tests against an in-memory database driven by
    // BundledSQLiteDriver, rather than Robolectric or an instrumented test — no
    // emulator/device in this build environment. See TestDatabase.kt for why a mocked
    // Context (mockito-kotlin) is enough to satisfy Room's builder here.
    testImplementation(libs.androidx.sqlite.bundled)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.androidx.paging.testing)

    // Real-device instrumented tests — 2.4a's since-deleted KeystoreSpike started this,
    // plan step 2.10 made it permanent (Scanner/Thumbnailer/UploadWorker against a real
    // MediaStore, ImageDecoder and WorkManager). See androidTest/.../TestEntryPoint.kt
    // for how these reach the real app's Hilt-provided singletons without a full
    // hilt-android-testing setup (HiltTestApplication, @UninstallModules) — nothing
    // here needs to *replace* a production binding, only read/seed a few of them, so
    // the lighter EntryPointAccessors pattern ArchivistApplication.kt already uses for
    // its own onTrimMemory is enough.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.hilt.android)
    kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.okhttp.mockwebserver)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
