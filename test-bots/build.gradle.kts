plugins {
    java
}

dependencies {
    compileOnly(libs.robocode.api)
}

tasks.jar {
    archiveFileName.set("test.SittingDuck_1.0.jar")
    manifest {
        attributes["robots"] = "test.SittingDuck"
    }
}
