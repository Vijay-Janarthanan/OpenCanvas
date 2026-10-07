pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "OpenCanvas"

include(":packages:opencanvas-core")
project(":packages:opencanvas-core").projectDir = file("packages/opencanvas-core")

include(":packages:opencanvas-compose")
project(":packages:opencanvas-compose").projectDir = file("packages/opencanvas-compose")

include(":demo-desktop")
project(":demo-desktop").projectDir = file("demo-desktop")
