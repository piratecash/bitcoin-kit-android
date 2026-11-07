import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(21)

    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

dependencies {
    implementation(project(":demo-shared"))
    implementation(compose.desktop.currentOs)

    // Supplies Dispatchers.Main on the JVM; without it the first launch on the main scope throws.
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "io.horizontalsystems.bitcoinkit.demo.desktop.MainKt"
    }
}
