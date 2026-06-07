plugins {
    java
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Xlint:-options")
    }

    repositories {
        // robocode 1.11.0 is not yet on Maven Central; resolve it from the local
        // Maven repository (installed from the c:\robocode 1.11.0 build).
        mavenLocal()
        mavenCentral()
    }

    tasks.test {
        useJUnitPlatform()
    }
}
