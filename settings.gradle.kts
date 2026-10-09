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
        // sherpa-onnx (Spracherkennung): AAR und Linux-Bibliothek fuer JVM-Tests kommen von JitPack, Version v1.13.8
        // (SHA-256 des AAR 633c2432...bd96, identisch mit dem GitHub-Release-Asset).
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.k2-fsa.sherpa-onnx") }
        }
    }
}
rootProject.name = "ChatLens"
include(":app")
include(":fakewa")
