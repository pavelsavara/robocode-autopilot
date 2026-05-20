plugins {
    java
    application
}

dependencies {
    implementation(project(":core"))
    implementation(libs.robocode.battle)
    implementation(libs.robocode.core)
    implementation(libs.robocode.host)
    implementation(libs.robocode.repository)
    implementation(libs.robocode.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("cz.zamboch.autopilot.pipeline.Main")
}

// Stage all battle JARs into a single directory for ROBOTPATH
val stageBattle = tasks.register<Copy>("stageBattle") {
    dependsOn(":robot:jar", ":test-bots:jar")
    from(project(":robot").tasks.named<Jar>("jar").get().archiveFile)
    from(project(":test-bots").tasks.named<Jar>("jar").get().archiveFile)
    from("c:/robocode/robots/kc.mega.BeepBoop_2.0.jar")
    into(layout.buildDirectory.dir("battle-stage"))
}

// --- Quick headless battle task ---
// Usage: ./gradlew :pipeline:runBattle
//        ./gradlew :pipeline:runBattle -Poutput=build/csv -Prounds=5
tasks.register<JavaExec>("runBattle") {
    dependsOn(stageBattle)
    group = "robocode"
    description = "Run a headless battle with streaming CSV pipeline"

    val stageDir = layout.buildDirectory.dir("battle-stage").get().asFile
    val robotJar = project(":robot").tasks.named<Jar>("jar").get().archiveFile.get().asFile

    classpath = files(
        sourceSets.main.get().output,
        configurations.named("runtimeClasspath"),
        robotJar
    )
    mainClass.set("cz.zamboch.autopilot.pipeline.BattleRunner")

    jvmArgs(
        "-Djava.awt.headless=true",
        "--add-opens", "java.base/sun.net.www.protocol.jar=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.net=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
        "--add-opens", "java.base/java.io=ALL-UNNAMED"
    )

    systemProperty("robot.jar", robotJar.absolutePath)
    systemProperty("battle.stage", stageDir.absolutePath)
    systemProperty("battle.rounds", "5")
    systemProperty("battle.opponent", "test.SittingDuck")

    // CSV output directory (enables streaming pipeline)
    val csvOut = layout.buildDirectory.dir("csv").get().asFile.absolutePath
    systemProperty("battle.output", csvOut)

    if (project.hasProperty("opponent")) {
        systemProperty("battle.opponent", project.property("opponent")!!)
    }
    if (project.hasProperty("rounds")) {
        systemProperty("battle.rounds", project.property("rounds")!!)
    }
    if (project.hasProperty("output")) {
        systemProperty("battle.output", project.property("output")!!)
    }
}

// --- Battle integration test (runs actual Robocode battle) ---
// Usage: ./gradlew :pipeline:battleTest
//        ./gradlew :pipeline:battleTest -Prounds=5 -Popponent=test.SittingDuck
tasks.register<Test>("battleTest") {
    dependsOn(stageBattle)
    group = "verification"
    description = "Run battle integration test with real Robocode engine"

    useJUnitPlatform()

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath

    filter {
        includeTestsMatching("cz.zamboch.autopilot.pipeline.BattleLoopTest")
    }

    val stageDir = layout.buildDirectory.dir("battle-stage").get().asFile
    systemProperty("battle.stage", stageDir.absolutePath)
    jvmArgs(
        "-Djava.awt.headless=true",
        "--add-opens", "java.base/sun.net.www.protocol.jar=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.net=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
        "--add-opens", "java.base/java.io=ALL-UNNAMED"
    )

    if (project.hasProperty("opponent")) {
        systemProperty("battle.opponent", project.property("opponent")!!)
    }
    if (project.hasProperty("rounds")) {
        systemProperty("battle.rounds", project.property("rounds")!!)
    }

    testLogging {
        showStandardStreams = true
    }
}
