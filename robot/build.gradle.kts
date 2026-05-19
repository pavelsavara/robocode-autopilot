plugins {
    java
}

dependencies {
    implementation(project(":core"))
    compileOnly(libs.robocode.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.robocode.api)
    testRuntimeOnly(libs.robocode.battle)
    testRuntimeOnly(libs.robocode.core)
    testRuntimeOnly(libs.robocode.host)
    testRuntimeOnly(libs.robocode.repository)
}

// Robot JAR: shade core classes into the robot JAR
tasks.jar {
    from(project(":core").sourceSets.main.get().output)
    archiveBaseName.set("cz.zamboch.Autopilot")
    archiveVersion.set("")
    archiveClassifier.set("")
    archiveAppendix.set("")
    // Robocode convention: ClassName_version.jar
    archiveFileName.set("cz.zamboch.Autopilot_${project.version}.jar")
    manifest {
        attributes["robots"] = "cz.zamboch.Autopilot"
    }
}
