plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "fr.enry.archivist.crypto"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.tink.android)
    implementation(libs.bouncycastle.bcprov)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}

tasks.withType<Test> {
    // testdata/vectors/ lives at the repo root, one level above android/.
    systemProperty(
        "archivist.vectorsDir",
        rootProject.projectDir.parentFile.resolve("testdata/vectors").absolutePath,
    )
}
