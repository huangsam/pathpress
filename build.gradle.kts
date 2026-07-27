plugins {
    kotlin("jvm") version "2.4.10"
    application
    id("com.ncorti.ktfmt.gradle") version "0.22.0"
}

group = "com.pathpress"

version = "1.0.0"

repositories { mavenCentral() }

dependencies {
    implementation(kotlin("stdlib"))

    // GraphHopper for spatial routing
    implementation("com.graphhopper:graphhopper-core:11.0")

    // PDF rendering (JVM-native)
    implementation("com.openhtmltopdf:openhtmltopdf-core:1.0.10")
    implementation("com.openhtmltopdf:openhtmltopdf-pdfbox:1.0.10")
    implementation("com.openhtmltopdf:openhtmltopdf-svg-support:1.0.10")

    // HTML DSL
    implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:0.11.0")

    // QR Code generation
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")

    // JSON processing
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")

    // CLI argument parsing
    implementation("com.github.ajalt.clikt:clikt:4.2.2")

    // Logging
    implementation("org.slf4j:slf4j-simple:2.0.13")

    // Unit testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

kotlin { jvmToolchain(21) }

application { mainClass.set("com.pathpress.MainKt") }

ktfmt { kotlinLangStyle() }

tasks.test { useJUnitPlatform() }
