plugins {
    java
    application
}

dependencies {
    implementation(project(":core"))
    implementation(libs.robocode.battle)
    implementation(libs.robocode.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("cz.zamboch.autopilot.pipeline.Main")
}

tasks.test {
    useJUnitPlatform()
}
