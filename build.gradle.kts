plugins {
    kotlin("jvm") version "2.0.21"
    application
    distribution
}

repositories { mavenCentral() }

dependencies {
    implementation("com.google.code.gson:gson:2.8.5")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin { jvmToolchain(22) }

application { mainClass.set("ru.polytech.jira.MainKt") }

tasks.test { useJUnitPlatform() }

distributions {
    main {
        contents {
            from("config/app.properties") { into("config") }
            from("scripts/run.bat") { into("") }
            from("scripts/run.sh") { into(""); filePermissions { unix("755") } }
            from("README.md")
        }
    }
}
