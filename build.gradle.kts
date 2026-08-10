import java.io.FileInputStream
import java.util.Properties

plugins {
    kotlin("jvm") version "2.4.10"
    id("com.ncorti.ktfmt.gradle") version "0.26.0"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.pathpress"

val versionProps =
    Properties().apply {
        val propFile = file("src/main/resources/version.properties")
        if (propFile.exists()) {
            FileInputStream(propFile).use { load(it) }
        }
    }

version = versionProps.getProperty("version") ?: "SNAPSHOT"

repositories { mavenCentral() }

dependencies {
    implementation(kotlin("stdlib"))

    // GraphHopper for spatial routing
    implementation("com.graphhopper:graphhopper-core:11.0")

    // High performance primitive collections
    implementation("com.carrotsearch:hppc:0.8.1")

    // PDF rendering (JVM-native)
    implementation("com.openhtmltopdf:openhtmltopdf-core:1.0.10")
    implementation("com.openhtmltopdf:openhtmltopdf-pdfbox:1.0.10")
    implementation("com.openhtmltopdf:openhtmltopdf-svg-support:1.0.10")

    // HTML DSL
    implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:0.12.0")

    // QR Code generation
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")

    // JSON processing
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.22.0")

    // CLI argument parsing
    implementation("com.github.ajalt.clikt:clikt:5.1.0")

    // Logging
    implementation("org.slf4j:slf4j-simple:2.0.18")

    // Unit testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.1")
}

kotlin { jvmToolchain(21) }

tasks.register<JavaExec>("run") {
    mainClass.set("com.pathpress.MainKt")
    classpath = sourceSets["main"].runtimeClasspath

    jvmArgs(
        "-XX:MaxRAMPercentage=75.0",
        "-XX:InitialRAMPercentage=25.0",
        "-Djava.awt.headless=true",
        "-Dfile.encoding=UTF-8",
        "-XX:+UseG1GC",
        "-XX:+UseStringDeduplication",
        "-XX:+ExitOnOutOfMemoryError",
    )

    providers
        .gradleProperty("maxHeap")
        .orElse(providers.environmentVariable("PATHPRESS_MAX_HEAP"))
        .orNull
        ?.let { customMax -> maxHeapSize = customMax }

    standardInput = System.`in`
}

ktfmt { kotlinLangStyle() }

tasks.test {
    maxHeapSize = "2g"
    jvmArgs("-Djava.awt.headless=true", "-Dfile.encoding=UTF-8")

    useJUnitPlatform {
        if (providers.environmentVariable("CI").isPresent) {
            excludeTags("network")
        }
    }
}

tasks.shadowJar {
    archiveBaseName.set("pathpress")
    archiveClassifier.set("standalone")
    archiveVersion.set(project.version.toString())

    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    exclude("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES", "license/*")

    manifest { attributes["Main-Class"] = "com.pathpress.MainKt" }

    mergeServiceFiles()
}

val copyUnversionedJar =
    tasks.register<Copy>("copyUnversionedJar") {
        dependsOn(tasks.shadowJar)
        from(tasks.shadowJar.flatMap { it.archiveFile })
        into(layout.buildDirectory.dir("libs"))
        rename { "pathpress-standalone.jar" }
    }

tasks.build { dependsOn(tasks.shadowJar, copyUnversionedJar) }
