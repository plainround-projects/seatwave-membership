plugins {
    java
    jacoco
    alias(libs.plugins.spotless)
}

group = "io.plainround"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // The simulator needs only the JDK. The tests need a runner and readable assertions.
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Transitive versions are written to gradle.lockfile, so two builds can never resolve different jars.
dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    // Every warning is an error: a warning that is allowed to stay is a defect nobody owns.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

spotless {
    java {
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        removeUnusedImports()
        endWithNewline()
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    reports {
        xml.required = true
    }
}

// The programs (*Cli) are run by hand and their output is printed in the README and the module.
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(sourceSets.main.get().output.asFileTree.matching { exclude("**/*Cli*") })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

// One task per program, so a reader runs one command and sees one result.
mapOf(
    "sweepRun" to ("io.plainround.seatwave.membership.DetectorSweepCli" to "Detection time against false positives for five timeouts and three phi thresholds"),
    "timelineRun" to ("io.plainround.seatwave.membership.SuspicionTimelineCli" to "One watcher's view of a 2 s pause through four detectors"),
    "gossipRun" to ("io.plainround.seatwave.membership.GossipCli" to "Gossip rounds and messages at 10, 16, 1,000 and 10,000 nodes"),
    "swimRun" to ("io.plainround.seatwave.membership.SwimProbeCli" to "SWIM-style probing for one hour on one shared membership view"),
    "partitionRun" to ("io.plainround.seatwave.membership.PartitionCli" to "Failure mode 5: a cut splits ten per-node lists, 5 against 5 and 7 against 3, then heals"),
    "scaleRun" to ("io.plainround.seatwave.membership.ClusterScaleCli" to "Removal time at 10 and 1,000 nodes, and traffic per node at 1,000"),
).forEach { (taskName, program) ->
    tasks.register<JavaExec>(taskName) {
        group = "seatwave"
        description = program.second
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = program.first
    }
}
