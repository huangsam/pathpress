plugins {
    kotlin("jvm") version "2.4.10"
    application
    id("com.gradleup.shadow") version "8.3.6"
}

group = "com.pathpress"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))

    // GraphHopper for spatial routing
    implementation("com.graphhopper:graphhopper-core:11.0")

    // PDF rendering (JVM-native)
    implementation("com.openhtmltopdf:openhtmltopdf-core:1.0.10")
    implementation("com.openhtmltopdf:openhtmltopdf-pdfbox:1.0.10")

    // JSON processing
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")

    // CLI argument parsing
    implementation("com.github.ajalt.clikt:clikt:4.2.2")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.pathpress.MainKt")
}
