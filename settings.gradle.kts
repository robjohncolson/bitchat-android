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
        // Guardian Project raw GitHub Maven (hosts info.guardianproject:arti-mobile-ex)
        maven { url = uri("https://raw.githubusercontent.com/guardianproject/gpmaven/master") }
        // JitPack — hosts com.github.dogecoin:libdohj (Dogecoin params for bitcoinj SPV); see
        // docs/dogecoin-spv-integration-plan.md. Must live here (FAIL_ON_PROJECT_REPOS forbids module repos).
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "bitchat-android"
include(":app")
// Using published Arti AAR; local module not included
