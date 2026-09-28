import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("maven-publish")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    androidTarget {
        publishLibraryVariants("release")
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // 21, not 17: sqlcipher-driver publishes Java 21 bytecode only.
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    sourceSets {
        // Not commonMain: src/main/kotlin belongs to the two JVM-backed targets only.
        val jvmCommonMain by creating {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                api(libs.room.runtime)
            }
        }
        val androidMain by getting {
            // Sources come from AGP's own `main` source set.
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(libs.sqlcipher.android)
            }
        }
        val jvmMain by getting {
            kotlin.srcDir("src/main/kotlin")
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(project(":sqlcipher-driver"))
            }
        }
        // Runs on a device only; never part of the published AAR.
        val androidInstrumentedTest by getting {
            dependencies {
                implementation("androidx.test.ext:junit:1.1.5")
                implementation("androidx.test:runner:1.5.2")
                implementation(libs.core)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.junit)
                implementation(libs.sqlite.bundled)
            }
        }
    }
}

android {
    namespace = "io.horizontalsystems.sqlcipher.room"
    compileSdk = 34

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("test").java.srcDirs("src/test/kotlin")
        getByName("androidTest").java.srcDirs("src/androidTest/kotlin")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
}
