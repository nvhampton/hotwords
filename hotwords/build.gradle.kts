import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    id("io.ktor.plugin") version "2.3.7"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    // Added the application plugin
    application
}

group = "com.hotwords"
version = "0.13.50"

// This is what the Shadow plugin was looking for
application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-netty-jvm")
    implementation("io.ktor:ktor-server-websockets-jvm")
    implementation("ch.qos.logback:logback-classic:1.4.14")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm")

    implementation("io.ktor:ktor-server-content-negotiation-jvm")
    implementation("io.ktor:ktor-server-websockets-jvm") // Ensure this is present
    implementation("io.ktor:ktor-server-host-common-jvm")

    // Compression
    implementation("io.ktor:ktor-server-compression-jvm")
    // HTTP client for Claude API calls
    implementation("io.ktor:ktor-client-core-jvm")
    implementation("io.ktor:ktor-client-cio-jvm")
    implementation("io.ktor:ktor-client-content-negotiation-jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "21"
    }
}

tasks.named<ProcessResources>("processResources") {
    outputs.upToDateWhen { false }
    filesMatching("static/index.html") {
        filter { it.replace("@@VERSION@@", version.toString()) }
    }
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    archiveFileName.set("game-server.jar")
    // The mainClassName is now inherited from the application block
}