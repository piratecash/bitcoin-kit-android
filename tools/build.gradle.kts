import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Internal dev utility (checkpoint generation): not published, as before the KMP move.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // 21 to match :bitcoincore's JVM target, which secp256k1-kmp-jni-jvm forces.
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    sourceSets {
        val jvmCommonMain by creating {
            dependencies {
                implementation(libs.kotlin.stdlib.jdk7)

                implementation(project(":bitcoincore"))
                implementation(project(":bitcoinkit"))
                implementation(project(":dashkit"))
                implementation(project(":bitcoincashkit"))
                implementation(project(":litecoinkit"))
                implementation(project(":ecashkit"))
                implementation(project(":dogecoinkit"))
                implementation(project(":cosantakit"))
                implementation(project(":piratecashkit"))
            }
        }
        val androidMain by getting {
            // Sources come from AGP's own `main` source set; adding them here too would
            // list the same file in two fragments.
            dependsOn(jvmCommonMain)
        }
        val jvmMain by getting {
            kotlin.srcDir("src/main/java")
            dependsOn(jvmCommonMain)
        }
    }
}

android {
    namespace = "io.horizontalsystems.tools"
    compileSdk = 34

    defaultConfig {
        minSdk = 24

        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
}
