plugins {
    `java-library`
}

tasks.withType<JavaCompile> {
    options.release.set(8)
}

dependencies {
    api(libs.robocode.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
