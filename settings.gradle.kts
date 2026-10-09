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
        // sherpa-onnx (speech recognition): the AAR and the Linux library for JVM tests come from JitPack, version v1.13.8
        // (SHA-256 of the AAR 633c2432...bd96, identical to the GitHub release asset).
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.k2-fsa.sherpa-onnx") }
        }
    }
}
rootProject.name = "ChatLens"
include(":app")
include(":fakewa")
