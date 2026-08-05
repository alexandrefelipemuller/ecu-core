import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

@CacheableTask
abstract class GenerateSharedVersionTask : org.gradle.api.DefaultTask() {
    @get:Input
    abstract val sharedVersion: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package com.speeduino.manager.shared

            /**
             * Metadata da versão do módulo shared.
             */
            object SharedModuleVersion {
                const val VERSION_NAME: String = "${sharedVersion.get()}"
                const val GROUP: String = "com.speeduino.manager"
                const val MODULE_NAME: String = "shared"
            }
            """.trimIndent() + "\n"
        )
    }
}

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("jacoco")
}

val sharedVersion = providers.gradleProperty("sharedVersion").orElse("0.0.0-dev")
val sharedVersionName = sharedVersion.get()
val generatedSharedVersionDir = layout.buildDirectory.dir("generated/sharedVersion/commonMain")

group = "com.speeduino.manager"
version = sharedVersionName

kotlin {
    android {
        namespace = "com.speeduino.manager.shared"
        compileSdk = 36
        minSdk = 24
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
        withHostTest {}
    }

    jvm("desktop") {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    iosArm64()
    iosSimulatorArm64()

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    sourceSets {
        getByName("commonMain") {
            kotlin.srcDir(generatedSharedVersionDir)
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val jvmMain = create("jvmMain") {
            dependsOn(getByName("commonMain"))
        }
        val jvmTest = create("jvmTest") {
            dependsOn(getByName("commonTest"))
        }

        getByName("androidMain") {
            dependsOn(jvmMain)
        }

        getByName("desktopMain") {
            dependsOn(jvmMain)
        }
        getByName("desktopTest") {
            dependsOn(jvmTest)
        }

        val iosMain = create("iosMain") {
            dependsOn(getByName("commonMain"))
        }
        getByName("iosArm64Main") { dependsOn(iosMain) }
        getByName("iosSimulatorArm64Main") { dependsOn(iosMain) }
    }
}

val generateSharedVersion = tasks.register<GenerateSharedVersionTask>("generateSharedVersion") {
    sharedVersion.set(sharedVersionName)
    outputFile.set(
        generatedSharedVersionDir.map {
            it.file("com/speeduino/manager/shared/SharedModuleVersion.kt")
        }
    )
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateSharedVersion)
}

jacoco {
    toolVersion = "0.8.11"
}

tasks.create<JacocoReport>("jacocoTestReport") {
    dependsOn("desktopTest")

    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("classes/kotlin/desktop/main")) {
            exclude("**/SharedModuleVersion.*")
        }
    )

    sourceDirectories.setFrom(
        "${projectDir}/src/commonMain/kotlin",
        "${projectDir}/src/jvmMain/kotlin",
        "${projectDir}/src/desktopMain/kotlin"
    )

    executionData.from(layout.buildDirectory.file("jacoco/desktopTest.exec"))

    reports {
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/jacocoTestReport/jacocoTestReport.xml"))
        html.required.set(true)
    }

    outputs.file(layout.buildDirectory.file("reports/jacoco/jacocoTestReport/jacocoTestReport.xml"))
}

sonar {
    properties {
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            "build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml"
        )
    }
}
