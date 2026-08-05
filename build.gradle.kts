plugins {
    id("com.android.kotlin.multiplatform.library") version "9.2.1" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.4.0" apply false
    id("org.sonarqube") version "7.3.1.8318"
}

sonar {
    properties {
        property("sonar.projectKey", "ecu-core")
        property("sonar.projectName", "ecu-core")
        property(
            "sonar.host.url",
            providers.gradleProperty("sonar.host.url").getOrElse("http://localhost:9000")
        )
        providers.gradleProperty("sonar.token").orNull?.let { property("sonar.token", it) }
    }
}
