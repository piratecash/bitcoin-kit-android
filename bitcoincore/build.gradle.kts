import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("maven-publish")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val kotlinVersion = rootProject.extra["kotlin_version"] as String

kotlin {
    androidTarget {
        publishLibraryVariants("release")
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // 21, not 17: secp256k1-kmp-jni-jvm (via hd-wallet-kit) publishes Java 21 bytecode only.
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    sourceSets {
        // Not commonMain: src/main/kotlin belongs to the two JVM-backed targets only, so a
        // JVM-only artifact declared in commonMain would break metadata compilation.
        val jvmCommonMain by creating {
            dependencies {
                implementation(libs.kotlin.stdlib.jdk8)
                api(libs.rxjava)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.5")
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.room.runtime)
                implementation(libs.bcprov.jdk15to18)
                implementation("com.eclipsesource.minimal-json:minimal-json:0.9.5")
                implementation("com.squareup.okhttp3:okhttp:4.5.0")
                api(libs.hd.wallet.kit)
                api(libs.kermit)
            }
        }
        val androidMain by getting {
            // Sources come from AGP's own `main` source set; adding them here too would
            // list the same file in two fragments.
            dependsOn(jvmCommonMain)
            dependencies {
                implementation("androidx.annotation:annotation:1.1.0")
                implementation(libs.androidx.startup)
                implementation(libs.sqlcipher.android)
            }
        }
        val jvmMain by getting {
            kotlin.srcDir("src/main/kotlin")
            dependsOn(jvmCommonMain)
            // Android opens databases through Room's SupportSQLite compat path; the JVM has none.
            dependencies {
                implementation(libs.sqlite.bundled)
                implementation(project(":sqlcipher-driver"))
            }
        }
        val jvmTest by getting {
            dependencies { implementation(libs.junit) }
        }
    }
}

// The 19 Java sources sit in the pre-KMP layout, which only AGP's `main` source set knows about.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    sourceSets["jvmMain"].java.srcDir("src/main/java")
}

// Room rejects blocking DAO functions unless android.content.Context is visible to the processor
// (Context.isAndroidOnlyTarget). The 187 DAO functions are shared with Android, so hand KSP a bare
// marker type instead of forking the storage layer around suspend. It generates byte-identical
// output: same schema version and identityHash as the Android target.
val roomAndroidMarker = java.sourceSets.create("roomAndroidMarker") {
    java.setSrcDirs(listOf(rootProject.file("gradle/room-android-marker/java")))
}

dependencies {
    "jvmMainCompileOnly"(roomAndroidMarker.output)
}

android {
    namespace = "io.horizontalsystems.bitcoincore"
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

// Parity with the pre-KMP artifact: the Kotlin Android plugin passed -parameters to javac.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    // Room
    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
    // KMP naming trap: kspAndroidTest is the JVM unit-test source set, kspAndroidAndroidTest the instrumented one.
    add("kspAndroidTest", libs.room.compiler)

    // Test helpers
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.5.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.6.1")
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.mockito.core)
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlinVersion")
    testImplementation(libs.robolectric)
    testImplementation(libs.core)

    // Spek
    testImplementation("org.spekframework.spek2:spek-dsl-jvm:2.0.9")
    testRuntimeOnly("org.spekframework.spek2:spek-runner-junit5:2.0.9")
    testImplementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")

    testImplementation("com.linkedin.dexmaker:dexmaker-mockito-inline:2.28.3")
    testImplementation("androidx.test.ext:junit:1.1.1")

}
