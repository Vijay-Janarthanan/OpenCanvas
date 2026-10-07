import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
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

    applyDefaultHierarchyTemplate()

    sourceSets {
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
        }
        androidMain.get().dependsOn(jvmSharedMain)
        jvmMain.get().dependsOn(jvmSharedMain)

        commonMain.dependencies {
            api(project(":packages:opencanvas-core"))
            api(compose.runtime)
            api(compose.foundation)
            api(compose.ui)
            api(compose.animation)
            api(compose.material3)
        }

        jvmMain.dependencies {
            api(compose.desktop.currentOs)
        }

        androidMain.dependencies {
            implementation("androidx.media3:media3-exoplayer:1.4.1")
            implementation("androidx.media3:media3-ui:1.4.1")
        }
    }
}

android {
    namespace = "com.opencanvas.compose"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
}

tasks.withType<Test> {
    maxHeapSize = "192m"
    systemProperty("java.awt.headless", "true")
}
