import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("jacoco")
}

val sharedVersion = providers.gradleProperty("sharedVersion").orElse("0.0.0-dev")
val sharedVersionName = sharedVersion.get()

group = "io.ecucore"
version = sharedVersionName

kotlin {
    android {
        namespace = "io.ecucore.core.protocol"
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
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                api(project(":core-model"))
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
            dependencies {
                implementation("com.fazecast:jSerialComm:2.11.0")
            }
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
