pluginManagement {
    repositories {
//        maven { url = uri("file:///C:/maven-local") }
        google()                  // Required for AGP
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
//        maven { url = uri("file:///C:/maven-local") }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "KotlinApp"   // adjust if needed
include(":app")
