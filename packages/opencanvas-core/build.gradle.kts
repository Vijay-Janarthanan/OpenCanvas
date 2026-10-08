import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("maven-publish")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // The sync engine (frame tables -> envelopes -> alignment -> policy) is common code, so it also runs
    // in a browser: the docs page measures and plays a song with exactly these classes.
    js(IR) {
        browser()
        binaries.library()
        useEsModules()
        generateTypeScriptDefinitions()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                compileOnly("com.microsoft.onnxruntime:onnxruntime:1.20.0")
                api("com.squareup.okhttp3:okhttp:4.12.0")
                api("org.jsoup:jsoup:1.18.1")
            }
        }
        androidMain.get().dependsOn(jvmSharedMain)
        jvmMain.get().dependsOn(jvmSharedMain)

        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }

        jvmTest.dependencies {
            implementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
            implementation("junit:junit:4.13.2")
        }
    }
}

android {
    namespace = "com.opencanvas.core"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
}

tasks.withType<Test> {
    maxHeapSize = "192m"
    systemProperty("java.awt.headless", "true")
}

group = "com.github.Vijay-Janarthanan.OpenCanvas"
version = "1.0.0"
