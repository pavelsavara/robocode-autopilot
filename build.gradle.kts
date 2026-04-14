plugins {
    java
    jacoco
}

description = "Robocode Autopilot - ML pipeline and competition robot"

subprojects {
    apply(plugin = "java")
    apply(plugin = "jacoco")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(8))
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    repositories {
        mavenCentral()
    }

    tasks.test {
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.test)
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }
}
