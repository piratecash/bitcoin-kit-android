import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("com.google.devtools.ksp")
    id("maven-publish")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val kotlinVersion = rootProject.extra["kotlin_version"] as String

kotlin {
    androidTarget {
        publishLibraryVariants("release")
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // 21 to match :bitcoincore's JVM target, which secp256k1-kmp-jni-jvm forces.
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    sourceSets {
        val jvmCommonMain by creating {
            dependencies {
                implementation(libs.kotlin.stdlib.jdk8)
                implementation("com.eclipsesource.minimal-json:minimal-json:0.9.5")
                implementation(libs.room.runtime)
                implementation(libs.saphir.hash.jca)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.bcprov.jdk15to18)
                implementation(libs.lyra2)
                api(project(":bitcoincore"))
                api(project(":dashlib"))
            }
        }
        val androidMain by getting {
            // Sources come from AGP's own `main` source set; adding them here too would
            // list the same file in two fragments.
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(libs.annotation)
                implementation(libs.kotlinx.coroutines.android)
            }
        }
        val jvmMain by getting {
            kotlin.srcDir("src/main/kotlin")
            resources.srcDir("src/main/resources")
            dependsOn(jvmCommonMain)
            dependencies { implementation(libs.kotlinx.coroutines.core) }
        }
    }
}

// Same marker as :bitcoincore — Room's KSP needs android.content.Context to accept blocking DAOs.
val roomAndroidMarker = java.sourceSets.create("roomAndroidMarker") {
    java.setSrcDirs(listOf(rootProject.file("gradle/room-android-marker/java")))
}

dependencies {
    "jvmMainCompileOnly"(roomAndroidMarker.output)
}

android {
    namespace = "io.horizontalsystems.piratecashkit"
    compileSdk = 34

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("test").java.srcDirs("src/test/kotlin")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    // Room
    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)

    // Test helpers
    testImplementation(libs.junit)
    testImplementation("org.junit.jupiter:junit-jupiter:5.6.1")
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)

    // Spek
    testImplementation("org.spekframework.spek2:spek-dsl-jvm:2.0.9")
    testRuntimeOnly("org.spekframework.spek2:spek-runner-junit5:2.0.9")
    testRuntimeOnly("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")

    // Android Instrumentation Test
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation("com.linkedin.dexmaker:dexmaker-mockito-inline:2.28.3")
    androidTestImplementation(libs.mockito.kotlin)
}
