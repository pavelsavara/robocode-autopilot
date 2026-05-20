plugins {
    java
}

tasks.withType<JavaCompile> {
    options.release.set(8)
}

dependencies {
    compileOnly(libs.robocode.api)
}

tasks.jar {
    archiveFileName.set("test-bots_1.0.jar")
    manifest {
        attributes["robots"] = "test.SittingDuck,test.Aggressive,sample.Fire,sample.Walls,sample.Crazy"
    }
}
