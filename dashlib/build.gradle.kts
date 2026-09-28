import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("maven-publish")
}

kotlin {
    androidTarget {
        publishLibraryVariants("release")
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        val jvmCommonMain by creating {
            dependencies { api(libs.guava) }
        }
        val androidMain by getting {
            // Sources come from AGP's own `main` source set; adding them here too would
            // list the same file in two fragments.
            dependsOn(jvmCommonMain)
            // AGP packs a file dependency into the AAR; the JVM jar inlines it instead.
            dependencies { api(files("libs/dashj-bls-0.15.3.jar")) }
        }
        val jvmMain by getting {
            // The desktop natives are attested against this path, so the shared code stays put.
            kotlin.srcDir("src/main/kotlin")
            dependsOn(jvmCommonMain)
            dependencies { compileOnly(files("libs/dashj-bls-0.15.3.jar")) }
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.junit)
                // The bindings are compileOnly in jvmMain; the test needs them at runtime.
                implementation(files("libs/dashj-bls-0.15.3.jar"))
            }
        }
    }
}

tasks.named<Jar>("jvmJar") {
    // The bindings have no coordinates: ship their classes inside the jar.
    from(zipTree("libs/dashj-bls-0.15.3.jar")) { exclude("META-INF/MANIFEST.MF") }
}

android {
    namespace = "io.horizontalsystems.dashlib"
    compileSdk = 34
    ndkVersion = "23.1.7779620"

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cFlags += "-DHAVE_CONFIG_H -DWORD=32"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("cpp/CMakeLists.txt")
        }
    }
}
