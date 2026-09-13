import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The seed never reaches the repository: it is read from local.properties at build time and
// baked into a generated object, so the demo has no seed-entry screen and no runtime storage.
val generateDemoConfig = tasks.register<GenerateDemoConfig>("generateDemoConfig") {
    localProperties.from(rootProject.layout.projectDirectory.file("local.properties"))
    outputDirectory.set(layout.buildDirectory.dir("generated/demo"))
}

kotlin {
    androidLibrary {
        namespace = "io.horizontalsystems.bitcoinkit.demo.shared"
        compileSdk = 36
        minSdk = 24

        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // 21 to match the kits' JVM target, which secp256k1-kmp-jni-jvm forces.
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    sourceSets {
        // Holds the dependencies only: the KMP Android library plugin has no AGP `main`
        // source set, so the shared sources are attached to both leaves instead.
        val jvmCommonMain by creating {
            dependencies {
                api(project(":bitcoinkit"))
                api(project(":bitcoincashkit"))
                api(project(":ecashkit"))
                api(project(":litecoinkit"))
                api(project(":dogecoinkit"))
                api(project(":dashkit"))
                api(project(":cosantakit"))
                api(project(":piratecashkit"))
                api(project(":hodler"))

                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.compose.multiplatform.runtime)
                implementation(libs.compose.multiplatform.foundation)
                implementation(libs.compose.multiplatform.material3)
                implementation(libs.compose.multiplatform.ui)
            }
        }
        val androidMain by getting {
            kotlin.srcDir("src/main/kotlin")
            kotlin.srcDir(generateDemoConfig)
            dependsOn(jvmCommonMain)
        }
        val jvmMain by getting {
            kotlin.srcDir("src/main/kotlin")
            kotlin.srcDir(generateDemoConfig)
            dependsOn(jvmCommonMain)
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.junit)
                // Kit construction derives HD keys through hd-wallet-kit, which needs the JNI build.
                implementation(libs.secp256k1.jni.jvm)
                // Kit callbacks are delivered on Dispatchers.Main; on the JVM that provider is the
                // desktop app's dependency, so the tests have to bring it in themselves.
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
    }
}

abstract class GenerateDemoConfig : DefaultTask() {

    // A file collection, not an InputFile: local.properties is absent on a fresh checkout and an
    // absent InputFile fails validation instead of generating an empty config.
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val localProperties: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val properties = Properties()
        localProperties.files.filter { it.isFile }.forEach { file ->
            file.inputStream().use { properties.load(it) }
        }
        // This repo stores `words` with the surrounding quotes included, because the old
        // app/build.gradle interpolated the value straight into a buildConfigField literal.
        val words = properties.getProperty("words").orEmpty().trim()
            .removeSurrounding("\"")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

        val directory = outputDirectory.get().asFile.resolve("io/horizontalsystems/bitcoinkit/demo")
        directory.mkdirs()
        directory.resolve("DemoConfig.kt").writeText(
            """
            package io.horizontalsystems.bitcoinkit.demo

            internal object DemoConfig {
                const val WORDS: String = "$words"
            }

            """.trimIndent()
        )
    }
}
