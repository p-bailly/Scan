plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Numéro de version = nombre de commits : le même en local et sur GitHub, et toujours croissant
// (une mise à jour doit avoir un numéro plus grand que la version installée).
val commitCount: Int = runCatching {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)

android {
    namespace = "fr.perso.scan"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.perso.scan"
        minSdk = 28
        targetSdk = 35
        versionCode = commitCount
        versionName = "1.0.$commitCount"
    }

    signingConfigs {
        // Clé personnelle volontairement versionnée (comme pour l'appli NBA) : tous les APK,
        // construits sur GitHub ou en local, ont la même signature et s'installent par-dessus.
        create("release") {
            storeFile = file("scan.keystore")
            storePassword = "scan-perso"
            keyAlias = "scan"
            keyPassword = "scan-perso"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // Tests JVM avec Robolectric : vrai décodage / encodage d'images d'Android (Skia).
        unitTests.isIncludeAndroidResources = true
        // Captures d'écran des tests Compose (rendu matériel simulé de Robolectric).
        unitTests.all { it.systemProperty("robolectric.pixelCopyRenderMode", "hardware") }
    }
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    // Scanner de documents de Google (détection des bords, recadrage, plusieurs pages),
    // fourni par les services Google Play : rien de lourd dans l'APK.
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
}
