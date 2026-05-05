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

tasks.test {
    useJUnitPlatform()
    // Point RobotTestBed to our built robot JAR
    systemProperty("robocode.robot.test.path", layout.buildDirectory.dir("libs").get().asFile.absolutePath)
    dependsOn(tasks.jar)
}

// Robot JAR: shade core classes into the robot JAR
tasks.jar {
    from(project(":core").sourceSets.main.get().output)
    archiveBaseName.set("cz.zamboch.Autopilot")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")
    manifest {
        attributes["robots"] = "cz.zamboch.Autopilot"
    }
}
