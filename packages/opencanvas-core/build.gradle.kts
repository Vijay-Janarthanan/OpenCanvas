import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.security.MessageDigest
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
    // AGP does not inherit resources from the custom intermediate Kotlin source set.
    sourceSets.getByName("main").resources.srcDir("src/jvmSharedMain/resources")
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

// A JVM inference test cannot catch an Android archive missing its Java resources.
tasks.register("verifyAndroidFramingResources") {
    dependsOn("bundleDebugAar")
    doLast {
        val expected = mapOf(
            "models/yunet.onnx" to "ebafce4e3c118d6554634be5c27ab333b4c047a9a8c3faf1d7cf93101c22f0f0",
            "models/yolox-nano.onnx" to "c789161ed43c8269fcd4e67c67eeeb4e80c622da2eb296a20bc6007bd18a0b7d",
        )
        val found = mutableMapOf<String, String>()
        ZipFile(layout.buildDirectory.file("outputs/aar/opencanvas-core-debug.aar").get().asFile).use { aar ->
            ZipInputStream(aar.getInputStream(aar.getEntry("classes.jar"))).use { jar ->
                var entry = jar.nextEntry
                while (entry != null) {
                    if (entry.name in expected) {
                        found[entry.name] = MessageDigest.getInstance("SHA-256")
                            .digest(jar.readBytes()).joinToString("") { "%02x".format(it) }
                    }
                    entry = jar.nextEntry
                }
            }
        }
        check(found == expected) { "Android framing models missing or changed: $found" }
        println("Android AAR contains both pinned framing models")
    }
}
