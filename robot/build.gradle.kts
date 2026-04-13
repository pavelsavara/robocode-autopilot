plugins {
    java
}

dependencies {
    implementation(project(":core"))
    compileOnly(libs.robocode.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}

// Robot JAR: shade core classes into the robot JAR
tasks.jar {
    from(project(":core").sourceSets.main.get().output)
    manifest {
        attributes["robots"] = "cz.zamboch.Autopilot"
    }
}
