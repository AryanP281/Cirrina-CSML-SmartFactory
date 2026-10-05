import sun.jvmstat.monitor.MonitoredVmUtil.mainClass

plugins {
    kotlin("jvm") version "2.3.10"
    id("application")
    id("com.gradleup.shadow") version "9.0.0"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))

    implementation("org.eclipse.zenoh:zenoh-kotlin:1.7.2")

    implementation("org.slf4j:slf4j-api:2.0.16")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.16")

    // Metrics
    implementation("io.dropwizard.metrics:metrics-core:4.2.38")

    implementation(files("libs/cirrina-2.2.0-all.jar"))
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("org.example.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveFileName.set("CSMMetricsCollector.jar")

    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }

    mergeServiceFiles()
}