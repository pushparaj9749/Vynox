plugins {
    id("java-library")
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(project(":core:math"))
    api(project(":core:animation"))
    api(project(":core:model"))
    api(project(":core:effects"))

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}
