// Cœur de l'appli sans Android (traitement des images, écriture du PDF) : testé sur la JVM.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Relit les PDF produits, comme le ferait un vrai lecteur PDF.
    testImplementation("org.apache.pdfbox:pdfbox:3.0.3")
}
