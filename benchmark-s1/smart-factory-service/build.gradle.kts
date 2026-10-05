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

    implementation("org.apache.fory:fory-core:0.15.0")
    implementation("org.apache.fory:fory-kotlin:0.15.0")

    implementation("org.eclipse.zenoh:zenoh-kotlin:1.7.2")

    implementation("com.microsoft.onnxruntime:onnxruntime:latest.release")

    implementation("org.slf4j:slf4j-api:2.0.16")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.16")

    implementation(files("libs/cirrina-2.2.0-all.jar"))
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("org.example.MainKt")
}

sourceSets {
    main {
        java { srcDir("build/generated/fory/foryGenJava/java")}
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.register("generateForyTypes", Exec::class.java) {
    commandLine(
        ".venvs/bin/foryc",
        "--lang",
        "java",
        "-o",
        "build/generated/fory/foryGenJava",
        "fdl/Event.fdl",
    )
}

tasks.shadowJar {
    archiveFileName.set("backend.jar")

    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }

    mergeServiceFiles()
}