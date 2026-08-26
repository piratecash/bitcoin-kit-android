import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("com.google.devtools.ksp")
    id("maven-publish")
}

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
                implementation(libs.kotlin.stdlib.jdk7)
                implementation(libs.room.runtime)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.mwebd.kmp)
                api(project(":bitcoincore"))
                api(project(":hodler"))
            }
        }
        val androidMain by getting {
            // Sources come from AGP's own `main` source set; adding them here too would
            // list the same file in two fragments.
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(libs.annotation)
                api(libs.kotlinx.coroutines.android)
            }
        }
        val jvmMain by getting {
            kotlin.srcDir("src/main/kotlin")
            resources.srcDir("src/main/resources")
            dependsOn(jvmCommonMain)
            dependencies { api(libs.kotlinx.coroutines.core) }
        }
        val jvmTest by getting {
            dependencies { implementation(libs.junit) }
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
    namespace = "io.horizontalsystems.litecoinkit"
    compileSdk = 34

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("test").java.srcDirs("src/test/kotlin")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    // Room
    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.secp256k1.jni.jvm)
    testImplementation(libs.core)
    testImplementation(libs.androidx.startup)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
