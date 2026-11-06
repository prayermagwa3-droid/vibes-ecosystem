plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("io.ktor.plugin") version "3.5.2"
}

group = "com.vibes.quantum"
version = "2.9.0"

repositories { mavenCentral() }

application { mainClass.set("com.vibes.quantum.server.ApplicationKt") }

dependencies {
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-netty-jvm")
    implementation("io.ktor:ktor-server-content-negotiation-jvm")
    implementation("io.ktor:ktor-server-default-headers-jvm")
    implementation("io.ktor:ktor-server-body-limit-jvm")
    implementation("io.ktor:ktor-server-websockets-jvm")
    implementation("io.ktor:ktor-client-cio-jvm")
    implementation("io.ktor:ktor-client-websockets-jvm")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    runtimeOnly("org.postgresql:postgresql:42.7.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("ch.qos.logback:logback-classic:1.6.5")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm")
}

kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }


tasks.register<JavaExec>("rollbackDatabase") {
    group = "database"
    description = "Rolls back the latest applied Vibes database migration. Back up the database first."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.vibes.quantum.server.DatabaseMigrationCli")
    args("rollback")
}

tasks.register<JavaExec>("databaseStatus") {
    group = "database"
    description = "Prints applied Vibes database migration versions."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.vibes.quantum.server.DatabaseMigrationCli")
}
