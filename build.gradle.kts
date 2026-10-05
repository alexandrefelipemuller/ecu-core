plugins {
    jacoco
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

// Cobertura agregada dos 4 módulos (target desktop). Uso:
//   ./gradlew jacocoAggregateReport                       -> HTML/XML em build/reports/jacoco/aggregate
//   ./gradlew jacocoCoverageGate -PcoverageMinimum=0.40   -> falha se linhas < 40% (padrão 0.52)
val coverageModules = listOf("core-model", "core-protocol", "core-runtime", "core-tuning")

fun JacocoReportBase.wireAggregate() {
    val mods = coverageModules.map { project(":$it") }
    dependsOn(mods.map { it.tasks.named("desktopTest") })
    executionData.setFrom(
        files(mods.map { it.layout.buildDirectory.file("jacoco/desktopTest.exec") }).filter { it.exists() }
    )
}

val aggregateClasses = files(coverageModules.map { m ->
    fileTree(project(":$m").layout.buildDirectory.dir("classes/kotlin/desktop/main")) {
        exclude("**/SharedModuleVersion.*")
    }
})
val aggregateSources = files(coverageModules.flatMap { m ->
    listOf("commonMain", "jvmMain", "desktopMain").map { "$m/src/$it/kotlin" }
})

tasks.register<JacocoReport>("jacocoAggregateReport") {
    wireAggregate()
    classDirectories.setFrom(aggregateClasses)
    sourceDirectories.setFrom(aggregateSources)
    reports {
        xml.required.set(true)
        html.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/aggregate/aggregate.xml"))
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/aggregate/html"))
    }
}

tasks.register<JacocoCoverageVerification>("jacocoCoverageGate") {
    wireAggregate()
    classDirectories.setFrom(aggregateClasses)
    sourceDirectories.setFrom(aggregateSources)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = providers.gradleProperty("coverageMinimum").getOrElse("0.52").toBigDecimal()
            }
        }
    }
}
